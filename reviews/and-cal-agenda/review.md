# Code Review

Date: 2026-10-21

Task: and-cal-agenda - Android calendar agenda and day/week views, calendar visibility (re-review of the final code after fixer round 3)

Scope: full task scope `git diff 13770a5c..90ea2896 -- android/`, with focus on the changes since the previous review (of 91805bb3), `git diff 91805bb3..90ea2896 -- android/`. In production code only `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt` changed (the `rememberSaveable` `positionedFrom` guard in `Agenda`, lines 369-380). Two new tests: `CalendarAgendaPositionTest.kt` and `CalendarAgendaStartTest.kt`. The rest of the task code is unchanged since 91805bb3 and was reviewed in the earlier rounds. Only committed changes were reviewed; the working tree was clean.

Requirements:
- tasks/and-cal-agenda.md acceptance criteria (agenda list with infinite scroll by range, day/week views, calendar visibility; `testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'` passes, covering range loading and grouping by day, plus Robolectric/Compose UI tests for the screens) and its recorded assumption (offline cache deferred to and-cal-cache)
- SPEC.md §6, §9.2 item 4, §9.3; docs/ARCHITECTURE.md (Android MVVM); AGENTS.md project notes
- Shell navigation contract: `ui/CockpitShell.kt:127-139` ("each tab keeps its state", `saveState`/`restoreState`)
- Earlier reports: the previous review.md (of 91805bb3), reviews/and-cal-agenda/fixes.md (rounds 1-3), reviews/and-cal-agenda/tests.md

Git range:
- Branch: main
- HEAD: 90ea2896406ebe8d7b9dd6294eefe6aab0140082 (reviewed revision)
- Base/start: 13770a5c0be6b453abb8cf6187b233354e8c0242
- Commits reviewed: all task commits up to 90ea2896. The commits new since the previous review are 90ea2896 (and 91805bb3/0181c227, reviewed before).

Summary: 0 P0, 0 P1, 1 P2, 0 P3

## Status of the earlier findings

- Round 1 CR-1, CR-3, CR-4; round 2 CR-1; test-writer TW-1: resolved in earlier rounds. The code behind them has not changed since.
- Round 1 CR-2 (offline agenda cache, SPEC §9.3): deferred to and-cal-cache by the recorded assumption. Not counted.
- Round 3 CR-1 (P2, the agenda jumps back to today after a rotation or a tab switch): resolved for the paths the finding named. `positionedFrom` is saved with `rememberSaveable`. When `Agenda` is composed again with its days already there and the same `from` (rotation with the view model kept, or a tab restored with `saveState`/`restoreState`), the effect does not scroll. `CalendarAgendaPositionTest` covers this with `StateRestorationTester`: it scrolls to day +15, restores, and asserts that day +15 is displayed and today is not. Without the guard this test would fail, because the effect would scroll to item 0. A resume on a later day still repositions: `refresh()` clears the occurrences and changes `from`, so the key differs. Switching Agenda -> Day -> Agenda drops `Agenda`'s saveable state, so it starts at today again, which is intended.
- Round 3 CR-2 (P3, no test for "the agenda opens at its first day"): resolved. `CalendarAgendaStartTest` covers the change from footer-only to loaded, and a reload from a later day while scrolled to the footer.

The parent ran this on 90ea2896: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' lintDebug createDebugUnitTestCoverageReport` -> BUILD SUCCESSFUL, 97 tests, 0 failures, ktlint and lint clean. The reviewer did not re-run it.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | After process death the agenda opens at its footer again and keeps loading range after range**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt:373-380`
    - Category: Correctness
    - Problem: `positionedFrom` is saved in the saved-instance state, so it survives process death (the NavHost keeps each entry's saveable state in the activity bundle). The view model does not: it starts again with `from = NO_DATE` and no occurrences (`CalendarViewModel.kt:77-87`, no `SavedStateHandle`). On the same day, the first resume's `refresh()` sets `from` back to today, which equals the restored `positionedFrom`. Before the data arrives, the list holds only the footer, so the restored `LazyListState` lands on the footer. When the days arrive they are inserted above the footer, and `hasItems && positionedFrom != from` is false, so no `scrollToItem(0)` runs. This is the exact situation the comment at lines 369-371 describes, and the guard now skips the fix for it. The agenda opens past today, at the footer. The footer is then near the end of a scrollable list, so the infinite-scroll trigger (lines 358-368) fires. Each loaded range again inserts its items above the footer, which stays in view, so the next range loads too. This repeats as long as ranges add events (for example a daily recurring event).
    - Why it matters: Android often kills a backgrounded app and restores it with its saved state. When the user comes back the same day, the original "opens scrolled past today" bug returns, and requests keep chaining against the server. No current test covers this: `CalendarAgendaPositionTest` restores with the days already present, which is the rotation case.
    - Suggested fix: forget the positioned day whenever the list has no days, e.g. `if (!hasItems) positionedFrom = null` in the effect (or before it). Rotation and tab restores compose with their days present, so they keep their position. A restore after process death, or any start from an empty list, positions at the first day again. Add a `StateRestorationTester` case where the state at restore has no occurrences (`loaded = false`) after the user scrolled down, then the days arrive, and assert that today is displayed and the footer is not.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task.

The round-3 fixes work as intended for rotation, tab switches and the first open, and they are tested. The acceptance criteria are implemented and the unit suite passes, as reported by the parent. CR-1 (P2) is a gap in the new guard: the restore after process death goes back to the original bug, and the trigger then keeps loading. The fix is one line plus a test. If the parent decides to accept it instead, record that disposition in quality.json.
