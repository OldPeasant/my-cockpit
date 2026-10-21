# Code Review

Date: 2026-10-21

Task: and-capture-queue - Android offline capture queue

Scope: `git diff 9ab97b9282b72018867805d12a9ea2f3c4389035..908508eb6239d9b721cf95625d66ae32123ee5e7`, the final revision after the fixes in `reviews/and-capture-queue/fixes.md`. Production code: `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/`. Tests: `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/`. Also the exported Room schema `android/app/schemas/.../CaptureQueueDatabase/1.json`. Re-review of CR-1..CR-5 from the previous review, and of the TW-1 fix (UUIDv7 ordering).

Reviewed revision: 908508eb6239d9b721cf95625d66ae32123ee5e7

Requirements:
- tasks/and-capture-queue.md: acceptance criteria #1-#4 and the recorded assumptions (7 lines, including the "no re-schedule on login" assumption)
- SPEC.md §4.5, §9.3
- api/openapi.yaml `CaptureRequest` (text maxLength 100000, fileIds maxItems 50); server `gtd/api/CaptureDtos.kt`, `gtd/domain/CaptureService.kt`, `gtd/domain/CaptureParser.kt`
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- reviews/and-capture-queue/fixes.md

Git range:
- Branch: main
- HEAD: 908508eb6239d9b721cf95625d66ae32123ee5e7
- Base/start: 9ab97b9282b72018867805d12a9ea2f3c4389035
- Commits reviewed: 7. The task commits are 442d42f2 (implementation), 7e57fcc2 (tests), debfce2e (test-writer tests and reports) and 908508eb (fixes). The other three are `assume:` commits that touch only `tasks/`. The hashes in the review request (ca37372f, 36e4d181, 430d2200, 3ca2d00e) do not exist in this clone. The commits above are the ones actually present between the base and HEAD.
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 3 P3

### Earlier findings: re-check

- **CR-1 (backoff):** resolved. `WorkManagerCaptureSyncScheduler.schedule()` reads the unique work states and uses `APPEND_OR_REPLACE` only while a run is RUNNING, `REPLACE` otherwise (`CaptureSync.kt:28-60`). `CaptureSyncTest` drives a job into retry backoff (ENQUEUED, `runAttemptCount` 1). It then checks that a new `schedule()` creates a fresh ENQUEUED job with 0 attempts and does not leave it BLOCKED behind the old job. A run that returns RETRY while a follow-up is appended is harmless: its retry rereads the whole queue. Login re-schedule is out of scope and recorded as an assumption, as the request states. The small read-then-enqueue race is CR-2 below.
- **CR-2 (relative due dates):** resolved. `RelativeDueDates.pin` follows the server's `CaptureParser` rules. Compared line by line: same token regex, title line = first non-blank line, same trailing punctuation set, case-insensitive values, first recognized `!` token wins, and invalid dates are skipped. `capture()` pins the date in the device zone at capture time. The server resolves in the user's configured zone instead (`CaptureService.today()`). That choice is covered by the recorded assumption. Notes are not parsed by the server and are left untouched.
- **CR-3 (orphaned copies):** resolved. A `finally` guarded by the `queued` flag deletes the copies under `NonCancellable`, and `SecurityException` is wrapped into `IOException`. `withContext(NonCancellable)` may throw `CancellationException` on return because of the prompt-cancellation rule. In that case `queued` is already true, so the copies of a committed capture are kept, which is correct. Tests cover a revoked grant and cancellation during the copy.
- **CR-4 (unexpected exceptions):** resolved. `sendSafely` maps non-cancellation exceptions to `Outcome.Failed`: the capture is marked, the pass goes on, and the result is RETRY. `CaptureSyncWorker.doWork` maps leftover exceptions to `Result.retry()`. `CancellationException` is rethrown in both places, and nothing is logged.
- **CR-5 (input the server always rejects):** blank text and more than 50 attachments are now refused. The server's text and notes length limits are still not checked (CR-3 below).
- **TW-1 (ordering within one millisecond):** resolved. `UuidV7Generator` implements RFC 9562 §6.2 method 1 with a synchronized 12-bit counter. Version and variant bits are still correct. `sendable()` orders by `createdAtEpochMs, id`. Lowercase fixed-width hex strings sort in the same order as the numeric ids.

Checked without findings: local write before any network use, the Room transaction, the FK cascade, a remote file id stored per attachment, 2xx/409 counted as sent, the retry/reject mapping, the status Flow, Hilt bindings, and no capture content in logs or errors.

Test evidence: I could not run Gradle in this review session (permission denied). The JUnit XML from the fixer run (`android/app/build/test-results/testDebugUnitTest/TEST-ch.sonensei.cockpit.capture.queue.*.xml`, written 01:35, one minute before commit 908508eb) shows 49 tests, 0 failures, 0 errors, 0 skipped. No acceptance criterion has recorded evidence yet: `python tasks.py verify and-capture-queue` shows #1-#4 unticked. The parent must record evidence before `done`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A failed `schedule()` makes `capture()` throw after the capture was committed**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueue.kt:100-114`, `CaptureSync.kt:42-44`
    - Category: Correctness
    - Problem: `schedule()` now suspends on `getWorkInfosForUniqueWorkFlow(...).first()` and on `enqueueUniqueWork(...).await()`. Before the fix, an enqueue error stayed inside WorkManager's asynchronous operation. Now any failure there (a WorkManager database error, an uninitialized WorkManager) is thrown out of `capture()` after `dao.insert` has committed the capture and `queued = true`. The caller gets an exception for a capture that is in fact stored, and the KDoc does not mention this case.
    - Why it matters: A share target or widget that shows "capture failed" invites the user to capture again. The original is still sent later, so the server gets two tasks.
    - Suggested fix: In `capture()`, catch non-cancellation exceptions from `scheduler.schedule()` and return the id anyway. The capture is stored, and the next `schedule()` or the job sends it. Otherwise, document that the capture may already be queued when `schedule()` fails.

- [ ] **CR-2 | Check-then-enqueue race can REPLACE a run that has just started**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureSync.kt:42-44`
    - Category: Correctness
    - Problem: The policy is chosen from a snapshot of the work states. If the job moves from ENQUEUED to RUNNING between the snapshot and `enqueueUniqueWork`, `REPLACE` cancels the running worker in the middle of an upload. A file the server has already stored, but whose id was not yet saved by `markUploaded`, is uploaded again by the next run and the first copy stays on the server as an orphan. The capture itself is idempotent, so no data is lost.
    - Why it matters: The window is narrow, but it leaks unreferenced files on the server, and the KDoc says REPLACE is used only when no run is sending.
    - Suggested fix: Accept the race and say so in the `policyFor` KDoc. Or avoid it, for example by serializing `schedule()` calls and only replacing work that has `runAttemptCount > 0` (actually in backoff). A longer-term option is a server-side cleanup of unreferenced files. This finding is low priority.

- [ ] **CR-3 | `capture()` still queues texts over the server's length limits**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueue.kt:75-76`
    - Category: Correctness
    - Problem: The CR-5 fix checks only blank text and the attachment count. The server also always returns 400 in two more cases. First, `text` longer than 100000 characters (`@Size(max = MAX_CAPTURE_TEXT_LENGTH)` in `CaptureDtos.kt:23`, `maxLength: 100000` in api/openapi.yaml). Second, combined notes (the extra text lines plus `notes`) longer than `TaskService.MAX_NOTES_LENGTH` (`CaptureService.kt:62`). Pinning also makes `!today` 5 characters longer, so a text just under the limit can go over it after pinning. Such a capture is marked rejected, stays in the pending count, and cannot be removed.
    - Why it matters: This is the dead-end queue entry that CR-5 was meant to prevent, through another input. It is unlikely with typed text and more likely with large shared text.
    - Suggested fix: Add `require(pinnedText.length <= 100_000)` on the pinned text (and optionally a notes length check) before copying, as a named constant next to `MAX_ATTACHMENTS`, and test it.

## Review conclusion

No material issues found. CR-1 to CR-5 from the previous review are resolved: CR-1 apart from the login re-schedule, which is recorded as an assumption. The three remaining findings are low-priority robustness points that the parent may fix or record. Before `done`, the parent must record acceptance-criterion evidence with `tasks.py verify`; none is recorded yet.
