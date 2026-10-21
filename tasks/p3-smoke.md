---
title: Phase 3 demo script (emulator)
status: in_progress
owner: dell26/agent-1
deps: [and-share-target,and-capture-entrypoints,and-inbox,and-next-today,and-projects-lists,and-reminders,and-notes,p2-smoke]
touches: [android/app/src/androidTest,android/app/build.gradle.kts]
prio: 1
lease_until: 2026-10-21T12:01:35Z
---
## Goal
Automate SPEC.md §13.2 Phase 3 where an emulator allows.

## Acceptance criteria
- [ ] Instrumented tests (`./gradlew connectedDebugAndroidTest` on an emulator, or a Gradle managed device) cover steps 1–5 against the e2e stack (10.0.2.2); if no emulator/KVM is available: skip with the reason, and the same flows run as Robolectric tests
- [ ] ARCHITECTURE.md documents how to run them (system image, AVD)

## Notes
