---
title: Android: AppStartRoutingTest 'logging out from settings clears the cached files' fails on main
status: todo
owner:
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/files,android/app/src/main/java/ch/sonensei/cockpit/auth,android/app/src/test/java/ch/sonensei/cockpit/auth]
prio: 2
---
## Goal
`sh scripts/check.sh` fails on main (2026-11-09): `android/gradlew -p android testDebugUnitTest --tests
'ch.sonensei.cockpit.auth.AppStartRoutingTest'` -> `logging out from settings clears the cached files`
fails deterministically with ComposeTimeoutException at AppStartRoutingTest.kt:167 (cached file still
exists after logout). Likely cause: and-files-preview (per-load cache file / new cache location) so the
logout cleanup no longer deletes what the test creates, or the test's `cachedFile()` is outdated. Blocks
every task's `done`.

## Acceptance criteria
- [ ] Logout clears the files cache actually used by the files feature (fix the code, or the test if only it is outdated)
- [ ] `AppStartRoutingTest` passes; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
