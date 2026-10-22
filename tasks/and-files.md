---
title: Android files
status: in_progress
owner: dell26/agent-1
deps: [and-login,files-preview]
touches: [android/app/src/main/java/ch/sonensei/cockpit/files,android/app/src/test/java/ch/sonensei/cockpit/files]
prio: 3
lease_until: 2026-10-22T11:13:15Z
---
## Goal
Files on the phone (SPEC.md §9.2 item 7).

## Acceptance criteria
- [ ] Browse, download/open with intents (FileProvider), upload from device
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*'` passes, covering browse and upload states (Robolectric/Compose UI tests for screens)

## Notes
- ASSUMPTION (2026-10-22 12:56+0200 by dell26/agent-1): Uploads from Android always use the streaming multipart POST /api/v1/files (any size up to the server max), not the resumable chunked /uploads: the server accepts multipart up to cockpit.files.max-size and the body is streamed, so memory is not an issue; resumability for >50 MB on mobile is left for later.
- ASSUMPTION (2026-10-22 12:56+0200 by dell26/agent-1): 'Download' on Android = 'Save to device' via the system save-as picker (ACTION_CREATE_DOCUMENT); 'Open' downloads into cache/files-open/<id>/ (only the last opened file is kept) and hands it out with ACTION_VIEW through a dedicated FileProvider subclass (authority <app>.files).
