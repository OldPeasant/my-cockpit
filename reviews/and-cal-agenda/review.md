# Code Review

Date: 2026-10-21

Task: and-cal-agenda - Android calendar agenda and day/week (re-review of the final code after fixer round 2 and the parent's scroll fix)

Scope: full task scope `git diff 13770a5c..91805bb3 -- android/`, with focus on the changes since the previous review, `git diff 53493021..91805bb3 -- android/`: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt` (resume refresh, infinite scroll trigger, initial scroll to the first day) and the new tests `CalendarAgendaInfiniteScrollTest.kt` and `CalendarRouteResumeTest.kt`. Committed changes only; working tree clean.

Requirements:
- tasks/and-cal-agenda.md acceptance criteria (agenda list with infinite scroll by range, day/week views, calendar visibility; `testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'` passes, covering range loading and grouping by day, Robolectric/Compose UI tests for screens) and its recorded assumption (offline cache deferred to and-cal-cache)
- SPEC.md §6, §9.2 item 4, §9.3; docs/ARCHITECTURE.md (Android MVVM); AGENTS.md project notes
- Shell navigation contract: `ui/CockpitShell.kt:127-139` ("each tab keeps its state", `saveState`/`restoreState`)
- Earlier reports: previous reviews/and-cal-agenda/review.md, reviews/and-cal-agenda/fixes.md (rounds 1 and 2), reviews/and-cal-agenda/tests.md (TW-1)

Git range:
- Branch: main
- HEAD: 91805bb3 (reviewed revision)
- Base/start: 13770a5c0be6b453abb8cf6187b233354e8c0242
- Commits reviewed: task commits up to 53493021 (reviewed before) plus 0181c227 and 91805bb3

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## Status of the earlier findings

- Round 1 CR-1 (overlapping visibility toggles), CR-3 (failure/supersession tests), CR-4 (static now line): resolved in round 1; code unchanged since, still correct.
- Round 1 CR-2 (offline agenda cache, SPEC §9.3): deferred to and-cal-cache by the recorded assumption; not counted.
- Round 2 CR-1 ("Today" stale on resume on a later day): resolved. `CalendarRoute` now calls `refresh()` from `LifecycleResumeEffect(viewModel)` (`CalendarScreen.kt:117`); the first resume is the only initial load (the view model's init does not load), and `refresh()` moves an agenda to the new today (`CalendarViewModel.kt:101`). Covered by `CalendarRouteResumeTest` (lifecycle CREATED -> clock to next morning -> RESUMED; asserts the new range is loaded and "Today" is on the new day).
- Test-writer TW-1 (infinite scroll not re-triggered after a short range, lost triggers while busy): resolved. The trigger now emits `(state.from, totalItemsCount)` only while near the end and not busy (`CalendarScreen.kt:358-368`), so it fires once per list length, again after a range that added any items, not while a load runs (then once it ends), and not range after range for empty ranges. `busy` and `rangeStart` are read through `rememberUpdatedState`, so the snapshot flow observes them. Covered by the five tests of `CalendarAgendaInfiniteScrollTest`.

Verified on the final code: one shared `loadJob` cancelled by each new load, so a resume refresh supersedes a running `loadMore` and `loadingMore` is reset; a resume refresh of an unchanged day keeps `from`, so neither the occurrences nor the scroll position (within the same composition) are reset; the resume refresh goes through `withCalendars`, so pending visibility writes are preserved. The parent reported `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' lintDebug createDebugUnitTestCoverageReport` -> BUILD SUCCESSFUL, 94 tests, 0 failures on 91805bb3 (not re-run by the reviewer).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | The agenda jumps back to today after a rotation or a tab switch**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt:371-374`
    - Category: Correctness
    - Problem: `LaunchedEffect(state.from, hasItems) { if (hasItems) listState.scrollToItem(0) }` also runs on the first composition of `Agenda` when the days are already there. `rememberLazyListState()` is saveable, and the view model survives both a configuration change (the activity declares no `configChanges`, so rotation recreates it) and a bottom-bar tab switch (`CockpitShell.kt:134-139` pops with `saveState = true` and navigates back with `restoreState = true`, documented as "each tab keeps its state"). In both cases the restored list state is non-empty at the first composition, so the effect immediately scrolls to item 0 and discards the position the user had scrolled to (e.g. weeks ahead). Before 91805bb3 that position was restored.
    - Why it matters: rotating the phone or checking another tab while browsing later weeks throws the user back to today, and the shell's "each tab keeps its state" contract is broken for the calendar. Normal use, not an edge case.
    - Suggested fix: scroll to the top only on the transition the fix targets (no days -> days for a new `from`), not when the composable starts with days already present. For example keep the `from` already positioned in a `rememberSaveable` value and scroll only when `hasItems` and `state.from` differs from it; or scroll only if the first visible item is still the footer. Add a test with `StateRestorationTester` (or recreating the content with the same view model state) asserting the scroll position survives.

## P3 Low

- [ ] **CR-2 | No test for the "agenda opens at its first day" fix**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt:369-374`
    - Category: Tests
    - Problem: the parent's fix for the agenda opening scrolled past today (days inserted above the footer key) has no regression test; no test in `calendar/agenda` asserts the initial scroll position (`firstVisibleItemIndex`, or that the today header is displayed after the first load). The existing infinite-scroll tests start with the days already present, so they do not exercise the empty -> loaded transition.
    - Why it matters: the bug was found only by hand, and the fix is coupled with CR-1; changing it for CR-1 could reintroduce the original bug unnoticed.
    - Suggested fix: a Compose test that starts with `loaded = false` and no occurrences, then sets a state with 20+ days, and asserts the today header is displayed (`assertIsDisplayed`) and the footer is not; together with the restoration test of CR-1.

## Review conclusion

Material issues found; fix before completing the task.

The earlier findings (round 2 CR-1, TW-1) are resolved and tested, and the acceptance criteria are implemented. CR-1 (P2) is a regression introduced by the last parent fix: the agenda loses its scroll position on rotation and tab switches. It should be fixed (with CR-2's test) before `done`; if the parent decides to accept it, record that disposition in quality.json.
