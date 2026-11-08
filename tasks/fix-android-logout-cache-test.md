---
title: Android: AppStartRoutingTest 'logging out from settings clears the cached files' fails on main (capture_queue.db.lck left in cache dir)
status: todo
owner:
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/auth,android/app/src/main/java/ch/sonensei/cockpit/capture,android/app/src/test/java/ch/sonensei/cockpit/auth]
prio: 2
---
## Goal
`sh scripts/check.sh` fails on origin/main (2026-11-08, after and-capture-improve), which blocks every `done`.
`AppStartRoutingTest > logging out from settings clears the cached files` times out at
AppStartRoutingTest.kt:167 (cached file never deleted), reproducible alone on a clean origin/main worktree:
`cd android && ./gradlew testDebugUnitTest --tests '*AppStartRoutingTest'` -> 1 of 11 failed.
In the full suite `SessionCacheTest > the cache directory is emptied...` also failed with
`expected:<[]> but was:<[capture_queue.db.lck]>`: the capture queue database (lock file) seems to live in,
or be recreated in, the cache directory that logout clears. Find the cause and fix it (production or test).

## Acceptance criteria
- [ ] Logout clears the session cache, including when the capture queue database is open; the cause is fixed, not the test loosened
- [ ] `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass on main

## Notes
