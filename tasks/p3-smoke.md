---
title: Phase 3 demo script (emulator)
status: in_progress
owner: dell26/agent-1
deps: [and-share-target,and-capture-entrypoints,and-inbox,and-next-today,and-projects-lists,and-reminders,and-notes,p2-smoke]
touches: [android/app/src/androidTest,android/app/build.gradle.kts]
prio: 1
lease_until: 2026-10-21T12:41:53Z
---
## Goal
Automate SPEC.md §13.2 Phase 3 where an emulator allows.

## Acceptance criteria
- [ ] Instrumented tests (`./gradlew connectedDebugAndroidTest` on an emulator, or a Gradle managed device) cover steps 1–5 against the e2e stack (10.0.2.2); if no emulator/KVM is available: skip with the reason, and the same flows run as Robolectric tests
- [ ] ARCHITECTURE.md documents how to run them (system image, AVD)

## Notes
- ASSUMPTION (2026-10-21 13:53+0200 by dell26/agent-1): No emulator here: KVM is readable but the SDK has no emulator/system image and writes outside the clone (SDK dir) are denied. The flows run as Robolectric tests against the real e2e stack (podman, 127.0.0.1); the instrumented variant (same shared code) compiles (compileDebugAndroidTestKotlin) but was never run.
- ASSUMPTION (2026-10-21 13:54+0200 by dell26/agent-1): Robolectric 'airplane mode' = WorkManager network constraint left unmet (test WorkManager); the emulator variant uses real airplane mode (cmd connectivity airplane-mode). Share from the browser/gallery = the ACTION_SEND intents those apps send; the widget = the CaptureActivity intent its pill starts; the pending count is read from CaptureQueue.status (what the widget shows), not from the rendered widget.
- ASSUMPTION (2026-10-21 13:54+0200 by dell26/agent-1): 'Web shows them' / 'views match the web' is checked through the API the web uses (GET /api/v1/tasks list/due, /projects, /links), with a second device token logged in by recovery code so the app's TOTP login stays the first use of its code. A task due today is seeded via the API so Today is not empty.
- ASSUMPTION (2026-10-21 13:54+0200 by dell26/agent-1): Step 5 'emulator clock': the reminder is created via the API (the app has no reminder editor) 20 s ahead and the emulator waits on the real clock; Robolectric checks the exact alarm is set for the reminder time and fires it. Shared test code lives in android/app/src/sharedTest (test+androidTest source dir) and the Robolectric runner in src/test/.../smoke (outside touches; new package). libs.versions.toml got androidx.test:runner 1.7.0 (one line + version).
