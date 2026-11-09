# Code Review

Date: 2026-11-09

Task: fix-flaky-app-start-logout-test - Stabilize AppStartRoutingTest 'logging out from settings clears the cached files' (times out at AppStartRoutingTest.kt:167 under load, blocks .taskcheck)

Scope: task commit a60ec23da on top of origin/main (efa76d6fb); test-only change to `android/app/src/test/java/ch/sonensei/cockpit/auth/AppStartRoutingTest.kt`

Requirements:
- tasks/fix-flaky-app-start-logout-test.md (acceptance criteria and assumption note)
- AGENTS.md (lint conventions, evidence rules)
- android/.editorconfig (ktlint_official)
- Production code read for the root-cause check: `android/app/src/main/java/ch/sonensei/cockpit/auth/SessionViewModel.kt`, `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthRepository.kt`

Git range:
- Branch: main
- HEAD: a60ec23da
- Base/start: efa76d6fb3ed2599a2db987214cad3713cd301c3 (origin/main)
- Commits reviewed: 1 code commit (a60ec23da). The claim/assume commits between base and HEAD only touch `tasks/`

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Note on method: Bash was not available to this reviewer, so the diff was not printed with git. The review used the full
current file, the production code the test exercises, and the task file. No Gradle suites were run, as the parent asked.

## Verification of the change

- Root cause: `SessionViewModel.logout()` launches in `viewModelScope`, which uses `Dispatchers.Main.immediate`.
  `AuthRepository.logout()` runs the logout hooks, then `session.logout(USER)` and `clearCaches()` on that dispatcher.
  Every cache step that switches to IO resumes by posting to the main looper. Under Robolectric's paused looper, those
  resumptions run only when the main looper is idled. Compose `waitUntil` only spins on the condition and advances frames,
  so the logout moves forward only as a side effect of that. The explanation in the KDoc and in the task assumption holds.
- Calling `shadowOf(Looper.getMainLooper()).idle()` from the test: Robolectric runs the test body on the main looper
  thread, so `idle()` runs the posted resumptions in place. `Thread.sleep(POLL_MS)` gives IO threads time to post the next
  step. That is correct, and it doesn't conflict with the compose rule: no compose node is queried inside the loop. The
  login screen was already asserted, which synced compose first.
- The network-layer test (`a logout by the network layer also clears the cached files`) clears on the app scope
  (`Dispatchers.Default`). Pumping the main looper there does no harm, and both cache waits now share one helper, which
  meets criterion 1 ("the cache-clearing waits").
- The deadline uses `System.nanoTime()` (monotonic), and the failure message holds the file name, the timeout and a
  thread dump, which meets criterion 1's dump requirement.
- Style: ktlint_official allows 140 columns. The longest new line (197) is about 127 columns. Constants are named, with
  no magic numbers. KDoc explains why. No suppressions. No debug output is left behind.
- Criteria 2 (passes under full CPU load) and 3 (android check) need run evidence. The parent records these; they
  weren't checked here.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The timeout dump can't show a logout parked on main-looper work, and it includes the polling thread itself**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/auth/AppStartRoutingTest.kt:204`
    - Category: Quality
    - Problem: `appThreads()` keeps every thread whose stack has a `ch.sonensei.cockpit` frame. The test thread always
      matches (its stack has `AppStartRoutingTest.awaitDeleted`), so the dump always holds the poller itself. The logout
      runs as a coroutine on the main dispatcher. While it waits for the next main-looper turn, it has no thread stack at
      all. `idle()` also runs only tasks that are due now and never advances Robolectric's clock. So a step that waits on
      delayed main-thread work (for example a `withTimeoutOrNull` in a `LogoutHook` running on Main) would never finish
      and would not show up in the dump. The dump helps only when an IO or Default thread is blocked inside app code.
    - Why it matters: The dump exists so that the stuck step "shows in the failure" (KDoc, line 191). In the main-dispatcher
      case it would show only the test thread, which could send a later investigation the wrong way.
    - Suggested fix (optional): Leave out `Thread.currentThread()` from the dump. Also add the main looper's state, e.g.
      `shadowOf(Looper.getMainLooper()).isIdle` and `nextScheduledTaskTime`, so a pending delayed task is visible.

## Review conclusion

No material issues found.
