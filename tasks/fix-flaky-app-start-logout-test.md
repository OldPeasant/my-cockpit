---
title: Stabilize AppStartRoutingTest 'logging out from settings clears the cached files' (times out at AppStartRoutingTest.kt:167 under load, blocks .taskcheck)
status: in_progress
owner: dell26/agent-2
deps: []
touches: [android/app/src/test/java/ch/sonensei/cockpit/auth]
prio: 2
lease_until: 2026-11-09T04:16:44Z
---
## Goal
The settings-logout cache test must not time out on a loaded host.

## Acceptance criteria
- [x] The cache-clearing waits in AppStartRoutingTest run Robolectric's main looper explicitly (a user logout runs on the main dispatcher) and dump the app threads on a timeout
  - verified (2026-11-09 05:00+0100 by dell26/agent-2): Read AppStartRoutingTest.kt at a60ec23da: both cache waits use awaitDeleted, which calls shadowOf(Looper.getMainLooper()).idle() every 10ms until a 30s deadline, then throws an AssertionError with the app thread stacks; code-reviewer confirmed it
- [ ] AppStartRoutingTest passes repeatedly while every CPU core is busy with other work
- [ ] `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes

## Notes
- ASSUMPTION (2026-11-09 04:56+0100 by dell26/agent-2): The task had no acceptance criteria; I added three: explicit main-looper pumping plus a thread dump on timeout, passing under CPU load, and the android check. Root cause assumed: the user logout runs on Dispatchers.Main, and compose waitUntil only runs Robolectric's paused looper as a side effect of frames
