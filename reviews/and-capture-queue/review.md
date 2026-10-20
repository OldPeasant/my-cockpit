# Code Review

Date: 2026-10-21

Task: and-capture-queue - Android offline capture queue

Scope: `git diff 9a9b449f..b3fcc20e` (android/app/src/main/java/ch/sonensei/cockpit/capture/queue/, android/app/src/test/java/ch/sonensei/cockpit/capture/queue/, exported Room schema android/app/schemas/.../CaptureQueueDatabase/1.json)

Requirements:
- tasks/and-capture-queue.md acceptance criteria and recorded assumptions (#1-#4)
- SPEC.md §4.5, §9.3, §15 Phase 3 acceptance scenario
- api/openapi.yaml (`POST /api/v1/capture`, `POST /api/v1/files`, `CaptureRequest`)
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- Assumption recorded by gtd-capture-api on relative due tokens (review CR-5 there)

Git range:
- Branch: main
- HEAD: b3fcc20ea0db3deda25fb4d520edb517bc5694d9
- Base/start: 9a9b449fa95b41c2ae399b7d4923d3601fb57f10
- Commits reviewed: 2 (3f9f2f44 implementation, b3fcc20e tests)

Summary: 0 P0, 0 P1, 2 P2, 3 P3

Checked without findings: UUIDv7 bit layout (version, variant, 48-bit timestamp; operator precedence of `and`/`shl` is correct), local write before any network use, Room transaction for capture plus attachments, FK cascade on delete, remote file id persisted per attachment so retries do not re-upload, 2xx/409 treated as sent, 401/408/429/5xx/IO retried, stop-on-first-retry ordering, status Flow as one query, no duplicate Hilt bindings (`CaptureApi`/`FilesApi`/`CaptureQueueDatabase` are bound only in `CaptureQueueModule`; `Retrofit` comes from `NetworkModule`), `@HiltAndroidApp` present for the worker entry point, Room schema export configured (`room { schemaDirectory(...) }`), no logging of capture text, notes or file content. Tests cover offline write, upload order, idempotent retry, 409, rejected capture, logged-out and copy cleanup. Acceptance criterion evidence (`tasks.py verify`) was not yet recorded at review time; the parent must record it before `done`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | New captures queue behind a retry backoff that can last hours**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureSync.kt:40`
    - Category: Correctness
    - Problem: `schedule()` uses `ExistingWorkPolicy.APPEND_OR_REPLACE`. When the existing job is ENQUEUED waiting for a retry (not running), the new request is appended as a dependent and only runs after the parent finishes. Exponential backoff from 30 s grows up to WorkManager's 5 h cap after repeated `Result.retry()` (server down overnight, or "Not logged in" retries while logged out, assumption #1). Nothing reschedules on login. So after the user logs in again or the server comes back, both the old and new captures wait until the parent's backoff runs out, which can take hours. The pending count stays above zero the whole time.
    - Why it matters: SPEC.md §9.3/§15 expect captures to sync soon after connectivity returns. A capture the user makes after the problem is fixed still waits behind a stale backoff. The comment on line 38 says "a run waiting for its retry sends everything queued anyway", but that run may not start for hours.
    - Suggested fix: Replace a job that is only waiting (ENQUEUED with backoff) and append only when a run is RUNNING. One way is to check `getWorkInfosForUniqueWork` and choose REPLACE or APPEND_OR_REPLACE. A REPLACE of a waiting job is safe because the capture is idempotent and uploaded file ids are persisted. Also call `schedule()` after login, or leave a hook for that. Add a test for scheduling while the previous job is in backoff.

- [ ] **CR-2 | Relative due tokens in offline captures resolve to the upload day; decision not recorded**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueue.kt:61-101`
    - Category: Correctness
    - Problem: The gtd-capture-api assumption says the server resolves `!today`/`!tomorrow` with its own clock when the request arrives. It hands client-side resolution to this task ("The Android capture queue (and-capture-queue) may resolve them client-side before queueing; no capturedAt field was added"). `capture()` stores and sends the raw text unchanged, and this task records no assumption about it. Take "Pay rent !today" captured offline at 23:30 and uploaded the next morning: it is due a day late.
    - Why it matters: An offline capture's due date silently depends on when it syncs (SPEC.md §4.5 syntax, §9.3 offline capture). The open decision passed to this task was dropped without a trace.
    - Suggested fix: Either rewrite `!today`/`!tomorrow` in the title line to an absolute `!yyyy-MM-dd` (local zone) at capture time, or record a `tasks.py assume` that the upload-day behavior is accepted, so a human can revisit it.

## P3 Low

- [ ] **CR-3 | Attachment copies orphaned on non-IOException failures during copy**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueue.kt:68-83`
    - Category: Quality
    - Problem: Cleanup of partial copies happens only for `IOException`. Other failures leave `files/capture_queue/<id>/` behind, and no row refers to it, so nothing ever deletes it. Examples: `ContentResolver.openInputStream` throwing `SecurityException` (a share grant that has expired or been revoked) or `IllegalArgumentException`, or cancellation of the calling coroutine mid-copy. The `SecurityException` also escapes `capture()`, although the KDoc promises `IOException`.
    - Why it matters: Shared photos and videos can be large, and these invisible leaks pile up in app storage.
    - Suggested fix: Clean up in a `catch (e: Throwable)` or `finally` guarded by a success flag (as the insert block already does with `Exception`). Optionally wrap `SecurityException` in `IOException` so the documented contract holds.

- [ ] **CR-4 | Unexpected exceptions fail the whole job chain; pending captures stall until the next capture**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureSync.kt:62-66`, `CaptureUploader.kt:79-101`
    - Category: Correctness
    - Problem: Only `IOException` is mapped to a retry. Any other exception makes `CoroutineWorker` return failure, and every appended job in the unique chain fails with it. Examples: a kotlinx `SerializationException` on an unexpected 2xx body from `uploadFile`/`capture`, `IllegalArgumentException` from `UUID.fromString`, a Room `SQLiteException`. Pending captures are then not sent again until a new `capture()` replaces the failed chain.
    - Why it matters: Captures can stay unsent with no retry and no `lastError` shown.
    - Suggested fix: In `doWork`, catch non-cancellation exceptions and return `Result.retry()`, or record them per capture as a failure, without logging content.

- [ ] **CR-5 | `capture()` accepts input the server always rejects**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueue.kt:61-65`
    - Category: Correctness
    - Problem: Two inputs are queued that the server always answers with 400 ("a text without a title" and `fileIds` `maxItems: 50` in api/openapi.yaml): blank `text`, and more than 50 attachments. The uploader marks such a capture `rejected`. It then stays in the pending count forever, and nothing can retry or remove it.
    - Why it matters: Callers (share target, widget) get no error at capture time. Instead the queue holds an item that can never be sent.
    - Suggested fix: Reject blank text and more than 50 attachments in `capture()` with `IllegalArgumentException`, before copying anything.

## Review conclusion

Material issues found; fix before completing the task. CR-1 and CR-2 should be fixed, or CR-2 explicitly recorded as an assumption. CR-3 to CR-5 are small robustness fixes.
