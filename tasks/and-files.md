---
title: Android files
status: in_progress
owner: dell26/agent-1
deps: [and-login,files-preview]
touches: [android/app/src/main/java/ch/sonensei/cockpit/files,android/app/src/test/java/ch/sonensei/cockpit/files]
prio: 3
lease_until: 2026-10-22T11:38:25Z
---
## Goal
Files on the phone (SPEC.md §9.2 item 7).

## Acceptance criteria
- [x] Browse, download/open with intents (FileProvider), upload from device
  - verified (2026-10-22 13:27+0200 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*' (Robolectric, real MainActivity + Hilt, FilesRepository faked) -> FilesRouteInAppTest 18/18 pass: browse folder + back, tap file downloads and fires ACTION_VIEW with content://<app>.files FileProvider URI + read grant (content readable through provider), save-as via CreateDocument writes the file, picked files upload into shown folder then reload; no physical device/emulator reachable from this sandbox
- [x] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*'` passes, covering browse and upload states (Robolectric/Compose UI tests for screens)
  - verified (2026-10-22 13:27+0200 by dell26/agent-1): cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*' createDebugUnitTestCoverageReport at ba712b4d2 -> BUILD SUCCESSFUL, 66 tests 0 failures (FilesViewModelTest browse/upload states, FilesScreenTest Compose UI, FilesRouteInAppTest Robolectric, DeviceFilesTest, FilesRepositoryTest); files coverage 97.7% lines

## Notes
- ASSUMPTION (2026-10-22 12:56+0200 by dell26/agent-1): Uploads from Android always use the streaming multipart POST /api/v1/files (any size up to the server max), not the resumable chunked /uploads: the server accepts multipart up to cockpit.files.max-size and the body is streamed, so memory is not an issue; resumability for >50 MB on mobile is left for later.
- ASSUMPTION (2026-10-22 12:56+0200 by dell26/agent-1): 'Download' on Android = 'Save to device' via the system save-as picker (ACTION_CREATE_DOCUMENT); 'Open' downloads into cache/files-open/<id>/ (only the last opened file is kept) and hands it out with ACTION_VIEW through a dedicated FileProvider subclass (authority <app>.files).
- ASSUMPTION (2026-10-22 12:56+0200 by dell26/agent-1): Touched files outside the task's touches, kept minimal: AndroidManifest.xml (one <provider>), res/xml/files_open_paths.xml and res/values/strings_files.xml (feature-own resource files, as contacts did).
- ASSUMPTION (2026-10-22 12:56+0200 by dell26/agent-1): Android Files browses, opens, saves and uploads only; rename/move/delete/new folder and previews stay web-only (SPEC.md §9.2 item 7 lists browse, download/open, upload).
- ASSUMPTION (2026-10-22 13:17+0200 by dell26/agent-1): Cleaning the open-cache folder (OpenedFiles.target) stays on the main thread inside the download coroutine (review CR-4, partly fixed): it is small local-disk work in the app's own cache, done once per download; moving it to Dispatchers.IO made Robolectric route tests time out. Picked-file provider queries do run on IO.
- ASSUMPTION (2026-10-22 13:27+0200 by dell26/agent-1): Criterion 1 (open with intents, FileProvider, pickers) verified with Robolectric in-app route tests, not on a physical device or emulator: none is reachable from the agent sandbox.
