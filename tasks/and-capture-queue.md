---
title: Android offline capture queue
status: in_progress
owner: dell26/agent-1
deps: [and-login,gtd-capture-api,files-api]
touches: [android/app/src/main/java/ch/sonensei/cockpit/capture/queue,android/app/src/test/java/ch/sonensei/cockpit/capture/queue]
prio: 1
lease_until: 2026-10-20T23:30:01Z
---
## Goal
Offline-first capture (SPEC.md §9.3).

## Acceptance criteria
- [ ] Room `pending_capture` (UUIDv7 id, text, notes, attachment copies in app storage, createdAt); capture always writes locally first
- [ ] WorkManager job (network constraint) uploads attachments then calls `/capture` with the id; retries with backoff; duplicate id → treated as success
- [ ] Pending count + last error exposed as a Flow
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.queue.*'` passes, covering offline write, upload ordering, idempotent retry (MockWebServer), attachment cleanup (Robolectric/Compose UI tests for screens)

## Notes
