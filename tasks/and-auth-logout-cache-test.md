---
title: Fix failing AppStartRoutingTest 'logging out from settings clears the cached files' on main
status: todo
owner:
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/auth,android/app/src/test/java/ch/sonensei/cockpit/auth]
prio: 2
---
## Goal
On origin/main (9eb8ee93c) `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.AppStartRoutingTest' --rerun-tasks`
fails reproducibly: 'logging out from settings clears the cached files' times out at AppStartRoutingTest.kt:167
(`waitUntil { !cached.exists() }`). This blocks `.taskcheck` for every Android task.

## Acceptance criteria
- [ ] The test passes reliably (3 consecutive runs); the cause is fixed in code or test, not by deleting the assertion
- [ ] `sh scripts/check.sh` passes

## Notes
Found by felix/agent-1 while working on and-mail-chip-a11y.
