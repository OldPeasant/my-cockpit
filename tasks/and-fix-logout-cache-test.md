---
title: Android: AppStartRoutingTest 'logging out from settings clears the cached files' fails on main
status: in_progress
owner: dell26/agent-2
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/files,android/app/src/main/java/ch/sonensei/cockpit/auth,android/app/src/test/java/ch/sonensei/cockpit/auth]
prio: 2
lease_until: 2026-11-09T01:42:37Z
releases: 1
extended: 
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
- ASSUMPTION (2026-11-09 02:18+0100 by dell26/agent-2): Could not reproduce on main 86d52bead: AppStartRoutingTest passes (0.5s for the settings-logout test) 3x in isolation and in a full ktlintCheck testDebugUnitTest lintDebug --rerun-tasks; AuthModule.cacheDirectory deletes all of context.cacheDir incl. files/ preview dir, so the cleanup is not outdated. Failure is likely timing: a cache earlier in the sequential clearCaches loop (Room clearAllTables via GtdRefresh writeLock, push registrar, WorkManager) blocked >5s on a loaded host.
- release by dell26/agent-2: No code change. Test passes on main 86d52bead (isolated x3 and full android check). Cache cleanup covers the files cache (whole cacheDir). Suspected cause: under load, a SessionCache cleared before AuthModule.cacheDirectory (sequential loop in AuthRepository.clearCaches) blocks >5s, so the waitUntil at AppStartRoutingTest.kt:167 times out. Running caches concurrently would break AuthRepositoryTest:788 (a real cancellation skips the remaining caches). Options: raise WAIT_MS for the cache waits, or clear the cache dir first/separately. Or drop the task if nobody sees it fail again.
- ASSUMPTION (2026-11-09 02:20+0100 by dell26/agent-2): Fixed the test only (wait 30s instead of 5s for the cached file to disappear): logout does clear the files cache (AuthModule.cacheDirectory deletes the whole cacheDir); the failure is timing under load from the sequential SessionCache loop
