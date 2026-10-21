# Code Review

Date: 2026-10-21

Task: and-cal-agenda - Android calendar agenda and day/week views, calendar visibility (re-review of the final code after fixer round 4)

Scope: full task scope `git diff 13770a5c..da1e9457 -- android/`, with focus on the changes since the previous review (of 90ea2896), `git diff 90ea2896..da1e9457 -- android/`. The parent worker and fixes.md round 4 say this delta changes only the `positionedFrom` effect in `Agenda` (`android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt:369-384`) and adds one test case to `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarAgendaPositionTest.kt`. Bash was not available to the reviewer in this round, so the delta could not be listed with `git diff`. Instead, the reviewer read the current code of both files and the view model paths the fix depends on (`CalendarViewModel.kt` `refresh`, `show`, `load`, `loadMore`) and checked them against the round-4 description. The rest of the task code was reviewed in earlier rounds. Only committed code was reviewed; the start-of-session git status showed a clean working tree.

Requirements:
- tasks/and-cal-agenda.md acceptance criteria (agenda list with infinite scroll by range, day/week views, calendar visibility; `testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'` passes, covering range loading and grouping by day, plus Robolectric/Compose UI tests for the screens) and its recorded assumption (offline cache deferred to and-cal-cache)
- SPEC.md §6, §9.2 item 4, §9.3; docs/ARCHITECTURE.md (Android MVVM); AGENTS.md project notes
- Earlier reports: the previous review.md (of 90ea2896), reviews/and-cal-agenda/fixes.md (rounds 1-4), reviews/and-cal-agenda/tests.md

Git range:
- Branch: main
- HEAD: da1e945755bd2eb609bf748950175f5d3a6a1f88 (reviewed revision)
- Base/start: 13770a5c0be6b453abb8cf6187b233354e8c0242
- Commits reviewed: all task commits up to da1e9457. The only commit new since the previous review is da1e9457 ("open restored-but-empty agenda at its first day").

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of the earlier findings

- Round 1 CR-1, CR-3, CR-4; round 2 CR-1; round 3 CR-1 and CR-2; test-writer TW-1; parent-found FX-1: resolved in earlier rounds. Round 4 does not change the code behind them, except for the `positionedFrom` guard covered below.
- Round 1 CR-2 (offline agenda cache, SPEC §9.3): deferred to and-cal-cache by the recorded assumption. Not counted.
- Round 4 CR-1 (P2, after process death the agenda opens at its footer and keeps loading range after range): resolved. The effect `LaunchedEffect(state.from, hasItems)` (`CalendarScreen.kt:376-384`) now sets `positionedFrom = null` while the list has no days, and scrolls to item 0 only when days are present and `positionedFrom` differs from `state.from`. These paths were checked:
  - Process death: the new view model starts without occurrences, so the first composition clears the restored `positionedFrom`. When the days arrive, `hasItems` changes, the effect runs again and scrolls to the first day. The agenda no longer opens at the footer, so the footer is not kept in view and the infinite-scroll chain does not start.
  - Rotation or a return to the tab with the view model kept: `Agenda` is composed with its days already there, `hasItems` is true from the start and the restored value equals `from`, so the restored position is kept (first test of `CalendarAgendaPositionTest`, unchanged).
  - Resume on the same day: `refresh()` (`CalendarViewModel.kt:96-109`) keeps the occurrences when the day has not changed, so `hasItems` stays true and the position is kept. Resume on a later day: `from` changes and the occurrences are cleared, so the agenda opens at its new first day, as before.
  - A list that becomes empty for another reason (e.g. every calendar hidden) only resets a position that has no days to keep, so this has no visible cost.
- New test `after a restart that kept the saved state but not the days, the days open the agenda at today` (`CalendarAgendaPositionTest.kt:74-95`): it scrolls to day +15, restores through `StateRestorationTester` with a state that has no occurrences, the same `from` and `loading = true`, then lets the days arrive. It asserts that today's header is displayed and the footer's "more" button is not. This is the case the finding asked for. Without the fix, the restored `positionedFrom` would equal `from` and the agenda would stay at the footer, so the test would catch a regression. The plain `afterRestart` field is read only during the restored composition, so the composition before the restore is not changed by the test setup.

The parent ran this on da1e9457: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' lintDebug createDebugUnitTestCoverageReport` -> BUILD SUCCESSFUL, 98 tests, 0 failures, ktlint and lint clean. The reviewer did not re-run it (no Bash in this round).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.

The round-4 fix resolves the process-death case from the previous review, keeps the rotation and tab-restore behaviour from round 3, and has a test for the new case. The acceptance criteria are implemented: agenda with infinite scroll by range, day and week views, and calendar visibility. The focused unit suite passes, as reported by the parent. The offline cache (round 1 CR-2) stays deferred to and-cal-cache by the recorded assumption.
