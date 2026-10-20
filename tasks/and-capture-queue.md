---
title: Android offline capture queue
status: in_progress
owner: dell26/agent-1
deps: [and-login,gtd-capture-api,files-api]
touches: [android/app/src/main/java/ch/sonensei/cockpit/capture/queue,android/app/src/test/java/ch/sonensei/cockpit/capture/queue]
prio: 1
lease_until: 2026-10-20T23:54:31Z
---
## Goal
Offline-first capture (SPEC.md §9.3).

## Acceptance criteria
- [ ] Room `pending_capture` (UUIDv7 id, text, notes, attachment copies in app storage, createdAt); capture always writes locally first
- [ ] WorkManager job (network constraint) uploads attachments then calls `/capture` with the id; retries with backoff; duplicate id → treated as success
- [ ] Pending count + last error exposed as a Flow
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.queue.*'` passes, covering offline write, upload ordering, idempotent retry (MockWebServer), attachment cleanup (Robolectric/Compose UI tests for screens)

## Notes
- ASSUMPTION (2026-10-21 01:15+0200 by dell26/agent-1): Pending captures are NOT cleared on logout (not a SessionCache): they exist nowhere else yet; the job just waits (retry with backoff) until someone is logged in again. Single-user app, so they go to the next login.
- ASSUMPTION (2026-10-21 01:15+0200 by dell26/agent-1): 409 on POST /capture (id belongs to a task in the Trash) counts as sent like 200, since the capture already reached the server. Other 4xx (400/413/...) mark the capture 'rejected': kept in the queue with lastError, counted as pending, but not retried automatically. 401/408/429/5xx/IO errors retry with exponential backoff (30 s).
- ASSUMPTION (2026-10-21 01:15+0200 by dell26/agent-1): Queue is its own Room DB (capture_queue.db, no destructive migration since it holds unsent data); schema exported to android/app/schemas/...CaptureQueueDatabase (outside touches, required by the room plugin). CaptureQueueModule provides CaptureApi and FilesApi from Retrofit; a later files task must reuse this FilesApi binding instead of adding a second one.
- ASSUMPTION (2026-10-21 01:15+0200 by dell26/agent-1): lastError is a short English string (Server unreachable / Not logged in / Server error (HTTP n) / Rejected by the server (HTTP n) / Attachment missing) of the most recently failed pending capture; UI tasks may map it. Each attachment's server file id is stored after upload so retries never upload it twice.
- ASSUMPTION (2026-10-21 01:36+0200 by dell26/agent-1): !today/!tomorrow in the capture title are pinned to the ISO date in the device zone at capture time, since the server would resolve them on the (possibly next-day) upload day
- ASSUMPTION (2026-10-21 01:36+0200 by dell26/agent-1): capture() throws IllegalArgumentException for blank text or >50 attachments (server limits), unexpected upload errors are stored as lastError 'Unexpected error' and retried
- ASSUMPTION (2026-10-21 01:36+0200 by dell26/agent-1): No re-schedule on login: AuthSession exposes no login signal; queued captures sync on the next capture or after WorkManager backoff. Follow-up needed if this matters
