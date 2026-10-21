---
title: Android offline capture queue
status: in_progress
owner: dell26/agent-4
deps: [and-login,gtd-capture-api,files-api]
touches: [android/app/src/main/java/ch/sonensei/cockpit/capture/queue,android/app/src/test/java/ch/sonensei/cockpit/capture/queue]
prio: 1
lease_until: 2026-10-21T01:26:58Z
releases: 1
extended: 
---
## Goal
Offline-first capture (SPEC.md §9.3).

## Acceptance criteria
- [x] Room `pending_capture` (UUIDv7 id, text, notes, attachment copies in app storage, createdAt); capture always writes locally first
  - verified (2026-10-21 02:50+0200 by dell26/agent-4): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.queue.*' --rerun-tasks -> 63/63 passed; CaptureQueueTest/CaptureQueueContractTest/AttachmentStoreTest cover Room pending_capture row (UUIDv7 id, text, notes, createdAt) + copies under files/capture_queue/<id> written before any network call (offline write)
- [x] WorkManager job (network constraint) uploads attachments then calls `/capture` with the id; retries with backoff; duplicate id → treated as success
  - verified (2026-10-21 02:50+0200 by dell26/agent-4): same run -> CaptureUploaderTest/CaptureUploaderFailureTest/CaptureSyncTest (MockWebServer, WorkManager test driver): attachments uploaded in order before POST /capture with the client id, network constraint + exponential backoff, retry/backoff on 5xx/IO, 200 for repeated id and 409 treated as sent, no re-upload of stored file ids
- [x] Pending count + last error exposed as a Flow
  - verified (2026-10-21 02:50+0200 by dell26/agent-4): same run -> CaptureQueueTest status Flow tests: pending count and lastError emitted via CaptureQueue.status (Flow<CaptureQueueStatus>)
- [x] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.queue.*'` passes, covering offline write, upload ordering, idempotent retry (MockWebServer), attachment cleanup (Robolectric/Compose UI tests for screens)
  - verified (2026-10-21 02:50+0200 by dell26/agent-4): cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.queue.*' --rerun-tasks -> BUILD SUCCESSFUL, 63 tests 0 failures (Robolectric + MockWebServer): offline write, upload ordering, idempotent retry, attachment cleanup incl. cancellation (CaptureQueueConsistencyTest). No screens in this task, so no Compose UI tests

## Notes
- ASSUMPTION (2026-10-21 01:15+0200 by dell26/agent-1): Pending captures are NOT cleared on logout (not a SessionCache): they exist nowhere else yet; the job just waits (retry with backoff) until someone is logged in again. Single-user app, so they go to the next login.
- ASSUMPTION (2026-10-21 01:15+0200 by dell26/agent-1): 409 on POST /capture (id belongs to a task in the Trash) counts as sent like 200, since the capture already reached the server. Other 4xx (400/413/...) mark the capture 'rejected': kept in the queue with lastError, counted as pending, but not retried automatically. 401/408/429/5xx/IO errors retry with exponential backoff (30 s).
- ASSUMPTION (2026-10-21 01:15+0200 by dell26/agent-1): Queue is its own Room DB (capture_queue.db, no destructive migration since it holds unsent data); schema exported to android/app/schemas/...CaptureQueueDatabase (outside touches, required by the room plugin). CaptureQueueModule provides CaptureApi and FilesApi from Retrofit; a later files task must reuse this FilesApi binding instead of adding a second one.
- ASSUMPTION (2026-10-21 01:15+0200 by dell26/agent-1): lastError is a short English string (Server unreachable / Not logged in / Server error (HTTP n) / Rejected by the server (HTTP n) / Attachment missing) of the most recently failed pending capture; UI tasks may map it. Each attachment's server file id is stored after upload so retries never upload it twice.
- ASSUMPTION (2026-10-21 01:36+0200 by dell26/agent-1): !today/!tomorrow in the capture title are pinned to the ISO date in the device zone at capture time, since the server would resolve them on the (possibly next-day) upload day
- ASSUMPTION (2026-10-21 01:36+0200 by dell26/agent-1): capture() throws IllegalArgumentException for blank text or >50 attachments (server limits), unexpected upload errors are stored as lastError 'Unexpected error' and retried
- ASSUMPTION (2026-10-21 01:36+0200 by dell26/agent-1): No re-schedule on login: AuthSession exposes no login signal; queued captures sync on the next capture or after WorkManager backoff. Follow-up needed if this matters
- release by dell26/agent-1: Done: Room pending_capture queue, AttachmentStore, CaptureUploader, WorkManager job (CaptureSync), status Flow, tests (49 passing in round 1), round-1 test-writer/review/fixer reports, round-2 review.md (0 P0-P2, 3 P3). Commit b6ca2583 adds CaptureQueueConsistencyTest from a round-2 test-writer run whose tests.md was NOT written. Left: (1) sh scripts/check.sh fails with exactly 1 Android test: CaptureQueueConsistencyTest 'a send run cancelled right after the capture row is removed leaves no orphaned attachment copies' -> production bug: attachment copies are not deleted if the send run is cancelled after the row delete (make the cleanup in CaptureUploader NonCancellable or sweep orphans at the start of a run); (2) decide on review P3s CR-1 (catch schedule() failure in capture() after commit), CR-2 (document REPLACE race), CR-3 (require text <= 100000 after pinning); (3) re-run test-writer (writes tests.md) and code-reviewer on final code; (4) tasks.py verify #1-#4, write reviews/and-capture-queue/quality.json, done. Note: gradle may be denied inside sub-agents, run tests in the parent.
- ASSUMPTION (2026-10-21 02:47+0200 by dell26/agent-4): Review P3 CR-1 (round 3) deferred: capture() does not pre-check the server's combined notes limit (extra text lines + notes <= 100000); such a capture ends as 'rejected' in the queue. Unlikely with typed input; a UI task can add a check or a discard action
- ASSUMPTION (2026-10-21 02:47+0200 by dell26/agent-4): Review P3 CR-2 (round 3) deferred: copies orphaned by a process kill between copy and insert (or row delete and file delete) are not swept; a later task may add an orphan sweep in sendAll() skipping recent folders
