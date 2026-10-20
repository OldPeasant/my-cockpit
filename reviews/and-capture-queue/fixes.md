# Fix Report

Date: 2026-10-21

Task: and-capture-queue - Android offline capture queue

Scope: current task. Findings CR-1 to CR-5 from `reviews/and-capture-queue/review.md` and the same-millisecond ordering observation from `reviews/and-capture-queue/tests.md` ("Bugs found"). Changes are limited to the task's `touches`: `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/` and `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/`. Base for this fix run: HEAD e968a6fa (uncommitted working-tree changes; the parent commits them).

## Fixed findings

### CR-1 — New captures queue behind a retry backoff that can last hours

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureSync.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueue.kt` (calls the now suspending `schedule()`)
- `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/CaptureSyncTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/CaptureQueueTestSupport.kt` (`RecordingScheduler` overrides the suspending `schedule()`)

Problem:
`schedule()` always used `APPEND_OR_REPLACE`. When the unique job was ENQUEUED and waiting out its exponential backoff, a new capture was chained behind it and could wait hours.

Fix:
`CaptureSyncScheduler.schedule()` is now `suspend` (a plain `interface`; there are no callers outside the package). `WorkManagerCaptureSyncScheduler` reads the unique work's states (`getWorkInfosForUniqueWorkFlow(...).first()`). It picks `APPEND_OR_REPLACE` only while a run is RUNNING and `REPLACE` in every other case (`policyFor`). This is safe because the capture is idempotent and the uploaded file ids are stored. The enqueue is awaited. The KDoc on `policyFor` explains why.

Login re-schedule (the second part of the suggestion): not done. See "Not fixed".

Validation:
- New `CaptureSyncTest` test `a new capture replaces a job waiting out its retry backoff instead of queueing behind it`. It uses the WorkManager test driver with a worker that returns retry. The job is ENQUEUED with `runAttemptCount` 1, then a second `schedule()` runs. Expected result: the old job is cancelled or pruned and a fresh ENQUEUED job (not BLOCKED, 0 attempts) is created — Pass
- New `a running job gets a follow-up run appended, any other state is replaced` (policy table) — Pass
- Updated `scheduling enqueues one unique job that needs a network`: two schedules with nothing running now leave exactly one active ENQUEUED job — Pass

### CR-2 — Relative due tokens in offline captures resolve to the upload day

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/RelativeDueDates.kt` (new)
- `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueue.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/RelativeDueDatesTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/CaptureQueueTest.kt`

Problem:
`!today` and `!tomorrow` were stored as typed. The server resolves them against its own clock when the request arrives, so a capture made offline before midnight and sent the next day got a due date one day late.

Fix:
`RelativeDueDates.pin(text, today)` copies the token rules of the server's `gtd/domain/CaptureParser.kt`:
- only the title line (the first non-blank line) is considered;
- the server's token regex is used: a token starts at the beginning of the line or after whitespace and runs to the next whitespace;
- trailing `.,;:!?)` is not part of the token value and is kept;
- values are case-insensitive;
- only the first *recognized* `!` token counts (`!today`, `!tomorrow` or a valid `!YYYY-MM-DD`). Unrecognized `!` tokens are skipped, and later due tokens stay as text.

When the first recognized token is `!today` or `!tomorrow`, its value is replaced with the ISO date. All other text, the notes and the line breaks are left as they are. `capture()` computes `today` from the injected `Clock` and a zone provider (`() -> ZoneId`, default `ZoneId::systemDefault`, read at capture time). It writes the pinned text to Room.

Validation:
- `RelativeDueDatesTest` (5 tests: case, punctuation, first-recognized-wins incl. invalid dates and bare `!`, in-word/other sigils, title-line-only with CRLF and leading blank lines) — Pass
- `CaptureQueueTest` `relative due dates are pinned to the capture day in the device time zone`: 22:30 UTC in Europe/Zurich gives `!2026-10-21` / `!2026-10-22`; the notes and the second line are unchanged — Pass

### CR-3 — Attachment copies orphaned on non-IOException failures during copy

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueue.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/AttachmentStoreTest.kt`

Problem:
Partial copies were removed only on `IOException`. A `SecurityException`, another exception or a cancellation left `files/capture_queue/<id>/` behind, and the `SecurityException` broke the documented `IOException` contract.

Fix:
- Cleanup now runs in a `finally` block guarded by a `queued` flag. The delete runs under `NonCancellable`, so it also completes when the caller is cancelled.
- A `SecurityException` from copying an attachment is rethrown as an `IOException`, with the original exception as its cause.
- Once the copies exist, the Room insert and the scheduling run under `NonCancellable`. Otherwise a cancellation that arrives right after the commit would delete the copies of a capture that is already queued.
- The KDoc states the contract.

Validation:
- New `a revoked share grant is reported as an IOException and leaves no copies` (provider `openFile` throws `SecurityException`) — Pass
- New `cancelling a capture while it copies leaves no copies` (the second copy suspends, the job is cancelled after the first copy exists) — Pass
- Existing `a failing database write removes the copies and schedules nothing`, `a content uri that cannot be opened queues nothing`, `an unreadable attachment queues nothing and leaves no copies` — Pass

### CR-4 — Unexpected exceptions fail the whole job chain

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureUploader.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureSync.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/CaptureUploaderFailureTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/CaptureSyncTest.kt`

Problem:
Only `IOException` led to a retry. Any other exception failed the worker and every run appended to it, so the queue stalled until the next capture.

Fix:
- In the uploader, `sendSafely` maps a non-cancellation exception during one capture to a new `Outcome.Failed("Unexpected error")`. The capture is marked with that `lastError` and is not rejected. The pass then continues with the next captures and returns `RETRY`, so the failed capture is tried again.
- As a safety net, `CaptureSyncWorker.doWork` maps any remaining non-cancellation exception (for example from `dao.sendable()`) to `Result.retry()`.
- `CancellationException` is rethrown in both places.
- Nothing is logged, so no capture content ends up in logs.

Validation:
- New `an unreadable upload answer marks that capture and the next one is still sent`. A 201 answer with a non-JSON body gives `RETRY`. The next capture is sent and the status is `(1, "Unexpected error")`. On the next pass the failed capture is sent — Pass
- New `a corrupt stored file id fails only that capture, which is retried` — Pass
- New `CaptureSyncTest` `an unexpected failure retries the job instead of failing it, cancellation still propagates` — Pass

### CR-5 — `capture()` accepts input the server always rejects

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueue.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/CaptureQueueTest.kt`

Problem:
Blank text, or more than 50 attachments (`CaptureRequest.fileIds` `maxItems: 50` in api/openapi.yaml), were queued anyway. The server then rejected them, and they stayed in the pending count forever.

Fix:
`require(text.isNotBlank())` and `require(attachments.size <= MAX_ATTACHMENTS)` (`MAX_ATTACHMENTS = 50`) run before anything is copied, and throw `IllegalArgumentException`.

Validation:
- New `blank text or too many attachments are refused before anything is copied`. Covers `""`, whitespace and newlines, and 51 attachments: no rows, no copies, nothing scheduled. Exactly 50 attachments are accepted — Pass

### TW-1 (tests.md observation) — Same-millisecond captures could be sent in either order

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/UuidV7.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueueDatabase.kt` (KDoc of `sendable()` only)
- `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/UuidV7Test.kt`

Problem:
`rand_a` was random, so two ids generated in the same millisecond were in no particular order. That contradicted the KDocs ("ids of later captures sort later", "the Inbox gets them in capture order").

Fix:
`UuidV7Generator` implements RFC 9562 §6.2 method 1 and is synchronized:
- `rand_a` is a 12-bit counter;
- each new millisecond starts the counter at a random value below 0x800;
- a repeated or earlier millisecond increments the counter;
- an exhausted counter moves on to the next millisecond.

Ids are therefore strictly increasing within the process. `UuidV7.generate` delegates to one process-wide generator. Tests use fresh instances, so their timestamp assertions do not depend on test order. The KDocs now say this.

Validation:
- New `ids within the same millisecond are strictly increasing and keep the timestamp` (1000 ids) — Pass
- New `an exhausted counter moves to the next millisecond and a clock stepping back still sorts later` — Pass
- Indirectly: the two same-millisecond captures in the CR-2 `CaptureQueueTest` test come back from `sendable()` in capture order — Pass

## Not fixed

### CR-1 (part) — Re-schedule when the user logs in

Status:
Blocked (outside current task scope)

Reason:
`core/network/AuthSession.kt` exposes only `isLoggedIn` (a getter over `TokenStore.current()`) and a `loggedOut` SharedFlow. `TokenStore` has no observable state either. No login event or login-state flow exists, so a listener inside `capture/queue/` cannot detect a login. A hook would need changes outside the task's `touches`: a login flow in `AuthSession`/`TokenStore`, or a call from the `auth` login code. Per the parent's instruction, no files outside the two folders were touched.

What still helps: the next capture after login now replaces a job that is waiting out its backoff (main CR-1 fix). Captures queued while logged out still wait for the current backoff until either a new capture happens or the backoff expires. The parent may record this with `tasks.py assume` or create a follow-up task (e.g. an `AuthSession` login flow, then call `CaptureSyncScheduler.schedule()` on login).

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.queue.*' ktlintCheck lintDebug` — Pass (exit 0). The JUnit XML shows 49 tests, 0 failures, 0 errors, 0 skipped: AttachmentStoreTest 10, CaptureQueueTest 6, CaptureSyncTest 5, CaptureUploaderFailureTest 12, CaptureUploaderTest 7, RelativeDueDatesTest 5, UuidV7Test 4. The package had 33 tests before this run.
- One run before the final one failed: the new backoff-replace test expected the replaced work to remain as CANCELLED, but WorkManager prunes it on REPLACE. I corrected that assertion in the test (the old work is now expected to be gone or CANCELLED). Production code was not changed for it.

Full test suite:
- `sh scripts/check.sh` (server `./gradlew check`, web `npm run check`, android `ktlintCheck testDebugUnitTest lintDebug`) — Pass ("== all checks passed"). The web bundle-size budget warning is pre-existing and unrelated.

Lint/type-check/build:
- `ktlintCheck` and `lintDebug` — Pass (in both commands above)

`.taskcheck`:
- Pass (`sh scripts/check.sh`)

Remaining unresolved findings:
1 (the login re-schedule part of CR-1, blocked by scope; the main CR-1 fix is done)

## Final status

Some findings remain blocked. All validated findings are fixed and validation passed, except the optional login re-schedule part of CR-1. That part needs a login signal that does not exist inside the task's `touches`.

Notes for the parent:
- These behavior changes are worth recording with `tasks.py assume`:
  - `!today`/`!tomorrow` are pinned in the device zone at capture time;
  - the new lastError string "Unexpected error" (this extends assumption #4's list);
  - `capture()` throws `IllegalArgumentException` for blank text or more than 50 attachments.
- Production code changed, so per AGENTS.md step 6, test-writer and code-reviewer must run again on the final code.
- I did not stage or commit anything. My role rules forbid staging and committing.
