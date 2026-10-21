# Fix Report

Date: 2026-10-21

Task: and-cal-agenda - Android calendar agenda and day/week

Scope: current task; findings CR-1, CR-3, CR-4 of `reviews/and-cal-agenda/review.md` (CR-2 excluded by the parent worker: deferred to task and-cal-cache, assumption recorded in the task file). Changes limited to `android/app/src/{main,test}/java/ch/sonensei/cockpit/calendar/agenda`.

## Fixed findings

### CR-1 — Overlapping visibility toggles refuse themselves and stale calendar lists overwrite optimistic state

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModelEdgeCasesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreensTest.kt`

Problem:
A second tap on a calendar chip while its PATCH was running sent another PATCH with the stale `If-Match` version, which the server refused (412). The user's last choice was then reverted and "visibility failed" was shown. A failure restored the pre-tap snapshot even when newer data had arrived. Calendar lists loaded by `load()`/`refreshCalendars()` that had been read before the PATCH answered flipped the chip back.

Fix:
- `CalendarUiState.pendingVisibility` (calendar id -> chosen visibility) records the writes that are still running. `setVisible` ignores a calendar that has a pending write, and its chip is disabled (`FilterChip(enabled = ...)`).
- Fetched calendar lists are applied through a new `withCalendars` merge, used by both `load()` and `refreshCalendars()`. A calendar with a pending write keeps the visibility the user chose. A local calendar that already has a higher `version` (the answer of a write) is not replaced by an older list.
- On failure, the toggle goes back to the newest server state seen for that calendar (the newest list that arrived during the write, otherwise the pre-tap calendar) instead of always using the pre-tap snapshot.
- Test change: the earlier test `two quick toggles of the same calendar end with what the server has` asserted the old behavior (a second PATCH sent with a stale version, a 412, and VISIBILITY_FAILED), which is exactly what CR-1 describes as wrong. It was replaced by `a second toggle of a calendar while its change is written is ignored` (one PATCH, no error message, toggling works again with the new version once the answer is in).
- New tests: `a calendar list loaded while a change is written keeps the chosen visibility`, `a calendar list read before a change answered does not undo it`, `a failed change after a newer calendar list arrived goes back to that list`, and the Compose test `the chip of a calendar whose visibility is being written waits for the answer`.

Validation:
- `cd android && ./gradlew --max-workers=1 ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' lintDebug` — Pass

### CR-3 — Failure and supersession paths of the range loading are untested

Files changed:
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModelEdgeCasesTest.kt`

Problem:
The review asked for tests of a failed `loadMore`, of a slow load superseded by a view switch, and of `refresh()` after midnight.

Fix:
`CalendarViewModelEdgeCasesTest.kt` (committed in 00c1481e) already covered two of the three cases: `a failed load of later days keeps the range and the events, and a later success clears the message` and `refreshing on a new day starts the agenda at the new today`. The test for supersession by a view switch was missing (the existing ones covered only `move` and `refresh`), so `a slow agenda load superseded by a view switch does not overwrite the new range` was added. It uses a `CompletableDeferred` agenda load that finishes after `select(DAY)`.

Validation:
- `cd android && ./gradlew --max-workers=1 ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' lintDebug` — Pass

### CR-4 — The "now" line in the day/week grid never moves

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreensTest.kt`

Problem:
`NowLine` read `LocalTime.now(zone)` once during composition and never recomposed.

Fix:
- The time is held in state, and a `LaunchedEffect` loop updates it at the start of every minute.
- The time source is `LocalNowClock`, an internal `staticCompositionLocalOf<Clock>` that defaults to the system clock, so tests can supply their own clock.
- The offset now uses the lambda overload `Modifier.offset { IntOffset(...) }`, as lint (`UseOfNonLambdaOffsetOverload`) requires for state-backed values.
- New Compose test `the now line follows the time while the day is shown`: it moves a test clock 30 minutes ahead, advances `mainClock` by one minute, and asserts that the line moved down 24dp.

Validation:
- `cd android && ./gradlew --max-workers=1 ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' lintDebug` — Pass

## Not fixed

### CR-2 — Agenda has no offline read cache (SPEC 9.3)

Status:
Out of scope

Reason:
The parent worker excluded it from this fix round. It is deferred to task and-cal-cache, and the assumption is recorded in `tasks/and-cal-agenda.md`.

## Validation summary

Focused tests:
- `cd android && ./gradlew --max-workers=1 ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' lintDebug` — Pass (BUILD SUCCESSFUL; 88 agenda tests, 0 failures, 0 skipped; ktlint and lint clean). The first two attempts without `--max-workers=1` ended with "Gradle build daemon disappeared unexpectedly" because the host was low on memory (about 2 GB available), not because of a test or lint result.

Full test suite:
- `sh scripts/check.sh` — first run failed in an unrelated server test, `ch.sonensei.cockpit.core.persistence.ClockConfigurationTests` "the application clock is the system clock in UTC". It compares a microsecond-truncated clock with a nanosecond `Instant.now()`, so it is a timing flake, and this task changes no server code. That test class passed on its own (`cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.core.persistence.ClockConfigurationTests'` — 4 tests, 0 failures). The second full run passed: "all checks passed" (web: 114 test files / 2275 tests passed; server and Android steps green).

Lint/type-check/build:
- Android ktlintCheck and lintDebug — Pass (part of the commands above)

`.taskcheck`:
- Pass (second run of `sh scripts/check.sh`; the first run hit the unrelated server flake described above)

Remaining unresolved findings:
1 (CR-2, out of scope, deferred to and-cal-cache)

Round 1 status: All validated findings fixed and validation passed.

## Round 2 (HEAD 0181c227, base 13770a5c)

Scope: TW-1 of `reviews/and-cal-agenda/tests.md` and CR-1 of the re-review `reviews/and-cal-agenda/review.md`, as supplied by the parent worker. Changes limited to `android/app/src/{main,test}/java/ch/sonensei/cockpit/calendar/agenda`.

### Fixed findings

#### TW-1 — The agenda does not load later days again after a range with few events

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarAgendaInfiniteScrollTest.kt`

Problem:
The infinite-scroll trigger in `Agenda` fired only when the "near the end" flag flipped from false to true. A range that added fewer than about 5 items left the flag true, so the next end was never loaded. A trigger ignored by `CalendarViewModel.loadMore` (a load running) was lost the same way.

Fix:
- The `snapshotFlow` now emits a key, `state.from` paired with `layoutInfo.totalItemsCount`, while the list can scroll, its end is near, and nothing is loading (`loading`, `loadingMore`, or not `loaded` yet; read through `rememberUpdatedState`). Otherwise it emits `null`. The flow is `filterNotNull().distinctUntilChanged()`, so a request goes out once per length of the list.
- Effects: a range that adds items, even a few, fires again while the end is still near. A trigger is no longer sent while a load runs, so it can't be lost; the request goes out when the load ends. A range without events leaves the length unchanged, so the agenda stops loading by itself, and the footer's "more" button still works. A list that does not fill the screen still never loads by itself (the `scrollable` condition is unchanged).
- The test class keeps both existing tests unchanged in what they assert. Its host state moved to a class property so tests can change it. New tests: `a range without events does not make the agenda load range after range by itself` (exactly 1 load after two scrolls to the end), `later days that arrive after a while near the end load the next days again` (asynchronous answer like the view model: 2 loads), and `the end reached while the agenda reloads asks for later days once the reload is done` (0 loads while `loading`, 1 once it ends).

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' lintDebug` (run by the parent worker) — Pass for all `CalendarAgendaInfiniteScrollTest` tests; ktlintCheck and lintDebug pass

#### CR-1 — "Today" and the agenda go stale when the app resumes on a later day

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarRouteResumeTest.kt` (new)

Problem:
`CalendarRoute` called `refresh()` only from `LaunchedEffect(Unit)`. An agenda left open in the background and resumed on a later day kept the old "today" and range.

Fix:
- `LaunchedEffect(Unit) { viewModel.refresh() }` was replaced by `LifecycleResumeEffect(viewModel) { viewModel.refresh(); onPauseOrDispose {} }`, the pattern used in `reminders/ReminderPermissionPrompt.kt`. The effect also runs on the first resume, so the first showing still loads exactly once (no double load). The existing `refresh()` already moves an agenda to the new today when the day changed.
- `CalendarRoute` takes `viewModel: CalendarViewModel = hiltViewModel()` as its last parameter, like `ReminderPermissionPrompt`, so a test can pass a view model. The only caller (`CalendarScreen`) is unchanged.
- New Robolectric/Compose test `CalendarRouteResumeTest`: it provides a `LifecycleOwner` with a `LifecycleRegistry` through `LocalLifecycleOwner` and uses a movable clock and a mocked `CalendarRepository`. It asserts exactly one load of [today, today+30) and none starting at tomorrow on the first showing, then moves the lifecycle to CREATED, moves the clock to the next morning, and resumes. (A first version asserted exactly one occurrences call of any range, and failed: with the end of the short agenda in view, the infinite scroll legitimately loaded [2026-11-20, 2026-12-20) as well. The assertion was narrowed to the refresh's range; production behavior was not changed for it.) It then asserts the load of [tomorrow, tomorrow+30), `today`/`from` == tomorrow, the "Today" header on the new day, and that the old day is gone.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' lintDebug` (run by the parent worker, first version of the test) — Fail: `CalendarRouteResumeTest` found 2 occurrences calls instead of 1 (the extra one was the legitimate infinite-scroll load); the assertion was narrowed as described above. Re-run with the narrowed assertion pending

### Not fixed

None in this round.

### Validation summary

Focused tests:
- The fixer could not run Gradle (Bash denied); the parent worker ran `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' lintDebug` — 94 tests, 93 passed, 1 failed (`CalendarRouteResumeTest`, the over-broad assertion described under CR-1, since narrowed in the test only). Re-run with the narrowed assertion pending.

Full test suite:
- Not run (`sh scripts/check.sh` is left to the parent worker)

Lint/type-check/build:
- ktlintCheck — Pass; lintDebug — Pass (parent worker's run above)

`.taskcheck`:
- Not run

Remaining unresolved findings:
1 (CR-2 of round 1, out of scope, deferred to and-cal-cache)

## Final status

All validated findings of both rounds are fixed in code. Round 2: 93 of 94 agenda tests, ktlintCheck and lintDebug passed in the parent worker's run. The one failure was an over-broad assertion in the new `CalendarRouteResumeTest`, now narrowed; the parent worker's re-run is pending.
