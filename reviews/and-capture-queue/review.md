# Code Review

Date: 2026-10-21

Task: and-capture-queue - Android offline capture queue

Scope: `git diff e1aa2ae18e6e82e2a6e54e94884fd064b9e1d889..2cb18d36cb06f456557ae1576d8e909db55e2f68`, the whole task. Production code: `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/` and the exported Room schema `android/app/schemas/.../CaptureQueueDatabase/1.json`. Tests: `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/`. This includes a re-check of the earlier P3 findings CR-1..CR-3, which commit f0c029fa addresses, and of the orphaned-copies fix in `CaptureUploader.sendAll`.

Reviewed revision: 2cb18d36cb06f456557ae1576d8e909db55e2f68

Requirements:
- tasks/and-capture-queue.md: acceptance criteria #1-#4 and the recorded assumptions
- SPEC.md §4.5, §9.3
- Server contract: `gtd/api/CaptureDtos.kt` (`text` max 100000, `fileIds` max 50) and `gtd/domain/CaptureService.kt` (combined notes max `TaskService.MAX_NOTES_LENGTH` = 100000)
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- reviews/and-capture-queue/review.md (previous round), fixes.md, tests.md

Git range:
- Branch: main
- HEAD: 2cb18d36cb06f456557ae1576d8e909db55e2f68
- Base/start: e1aa2ae18e6e82e2a6e54e94884fd064b9e1d889
- Commits reviewed: 7 (0e9097b6, d07aa2d4, 62dcee54, 9a629ea6, 314c9d2c, f0c029fa, 2cb18d36)
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 2 P3

### Acceptance criteria

- **#1 Room `pending_capture`, local write first:** met. The entity has the UUIDv7 id, text, notes and createdAt. Attachment copies sit in `files/capture_queue/<id>/` with their own table, which cascades on delete. `capture()` validates the input, copies the files and inserts in one transaction before it schedules anything. No network is used on that path.
- **#2 WorkManager job:** met. The job has a `CONNECTED` constraint and exponential backoff of 30 s. Attachments are uploaded in order and their file ids are stored. `/capture` is then called with the client id. 2xx and 409 count as sent, as the recorded assumption says, and the retry/reject mapping matches it.
- **#3 Flow:** met. `CaptureQueue.status` reads the pending count and the newest error in one Room query, with `distinctUntilChanged`.
- **#4 Tests:** I ran `cd android && ./gradlew --offline -q testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.queue.*'` in this session. It exited 0, and the JUnit XML written at 02:46 shows 63 tests, 0 failures, 0 errors, 0 skipped. The tests cover offline write, upload order, idempotent retry through MockWebServer, and attachment cleanup (including cancellation and a failed delete). The task has no screens, so the Compose UI part of the criterion does not apply.
- `python tasks.py verify and-capture-queue` still shows #1-#4 unticked. The parent must record evidence before `done`.

### Earlier findings: re-check

- **CR-1 (`schedule()` failure after commit):** resolved. `CaptureQueue.kt:118-124` ignores non-cancellation exceptions from `scheduler.schedule()` after `queued = true`, and still rethrows `CancellationException`. The KDoc (lines 70-71) states the contract. `CaptureQueueContractTest` and `CaptureQueueConsistencyTest` cover a failing scheduler, a scheduler that recovers, a scheduler that throws a cancellation, and a caller cancelled while scheduling.
- **CR-2 (check-then-enqueue race):** resolved by documenting it, as suggested. The `policyFor` KDoc (`CaptureSync.kt:52-57`) names the race and what it costs: an orphaned file on the server, but no capture is lost.
- **CR-3 (text over the server limit):** resolved for `text`. `require(pinnedText.length <= MAX_TEXT_LENGTH)` (`CaptureQueue.kt:84`) runs after pinning and before any copy. Boundary tests cover `!today` and `!tomorrow` growing the text, and a token outside the title line. The notes limit, which the previous review called optional, is still not checked (CR-1 below).
- **Orphaned copies after a cancelled send run:** resolved. `CaptureUploader.kt:62-68` deletes the copies in a `finally` under `NonCancellable`, but only when `dao.attachments(id)` is empty. If the row delete did not commit (an exception or a cancellation before the commit), the attachment rows still exist and the copies stay, which is correct. The check is also correct for a capture without attachments, where it deletes a folder that does not exist. Both paths are tested: a cancellation right after the delete, and a delete that throws `SQLiteFullException` and is cleaned up by the next run.

Checked without findings: UUIDv7 monotonic generation, `sendable()` ordering, the `RelativeDueDates` parity with the server parser, the Hilt bindings, `CaptureSyncWorker` exception mapping, and that no capture content is logged or stored in errors.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Notes over the server's limit still create a capture that is never sent**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueue.kt:78-84`
    - Category: Correctness
    - Problem: `capture()` checks blank text, the attachment count and the pinned text length, but not the notes. The server joins the text after the title line with `notes` and returns 400 when the result is longer than 100000 characters (`CaptureService.kt:61-63`). `CaptureRequest.notes` itself has no size limit. Such a capture is marked `rejected`. It stays in the pending count with "Rejected by the server (HTTP 400)", and the queue has no way to remove it.
    - Why it matters: This is the same dead-end queue entry that the text check now prevents, through the notes. It is unlikely with typed input and possible with large shared text sent as notes.
    - Suggested fix: Add `require` checks next to `MAX_TEXT_LENGTH` for the combined notes length, computed as the server does (the text after the first non-blank line, plus `"\n\n"`, plus non-blank `notes`), or at least `notes.length <= 100_000`. Add a boundary test. Otherwise record the gap as an assumption.

- [ ] **CR-2 | Copies orphaned by a process kill are never removed**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueue.kt:86-128`, `CaptureUploader.kt:62-68`
    - Category: Quality
    - Problem: The cleanup relies on `finally` blocks under `NonCancellable`. Those cover coroutine cancellation, but not a process kill. Copies can be orphaned in two places: after `files.copy` and before `dao.insert` commits, or after `dao.delete` commits and before `files.delete` runs. In both cases they stay in `files/capture_queue/<id>/` with no row, and nothing ever sweeps them.
    - Why it matters: The windows are small. Each orphan is a full copy of a user file, which permanently wastes app storage and keeps user data on the device after it should have been removed.
    - Suggested fix: At the start of `sendAll()`, delete every folder under the queue root that has no `pending_capture` row. Skip folders younger than a short grace period, so a capture still copying is not affected. Otherwise record the gap as an assumption.

## Review conclusion

No material issues found. The earlier findings CR-1..CR-3 and the orphaned-copies bug are resolved and tested. The two remaining P3 findings are optional robustness improvements, which the parent may fix or record as assumptions. Before `done`, the parent must record acceptance-criterion evidence with `tasks.py verify`. None is recorded yet.
