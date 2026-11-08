# Code Review

Date: 2026-11-08

Task: and-scheduled-list - Android: Scheduled list screen

Scope: task commit a8dedeb2c on top of base 6a993ed68 (`git diff 6a993ed68..a8dedeb2c`), committed changes only

Requirements:
- tasks/and-scheduled-list.md acceptance criteria and recorded assumption (Scheduled is a More entry after Someday; checkbox completes, tap opens detail; Yesterday/full date for past dates; 'No date' group last)
- SPEC.md section 5.1 (Scheduled list), docs/PARITY.md row "No Scheduled list"
- AGENTS.md project conventions (i18n EN+DE, lint, feature folders, touches)
- Web reference: web/src/app/features/gtd/lists/scheduled-page.{html,ts}, list-views.ts (groupByScheduledDate, relativeDay)
- Android siblings: WaitingScreen.kt, SomedayScreen.kt, LogbookScreen.kt, ListViewModels.kt, ListUi.kt

Git range:
- Branch: main
- HEAD: a8dedeb2c87d8def60a74514bf84226fea9b17a7
- Base/start: 6a993ed682dd38426131fa2e8eaa4c852ef0d734
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 1 P2, 1 P3

Files reviewed: gtd/lists/ScheduledScreen.kt (new), gtd/lists/ListViewModels.kt (ScheduledViewModel, byScheduledDate), gtd/lists/ListUi.kt (ListSection, groupConsecutiveBy, shared DayHeader), gtd/lists/LogbookScreen.kt (DayHeader extracted), ui/CockpitShell.kt, ui/navigation/CockpitDestination.kt, strings_projects_lists.xml (values, values-de), ScheduledListTest.kt, ScheduledInAppTest.kt, CockpitDestinationTest.kt.

Parity check against web: same ordering (scheduledDate ascending, undated last, ties by position), same grouping into consecutive runs, same header labels (Today / Tomorrow / Yesterday / full date / No date), same empty text, no extra row action (web has none). Navigation order Waiting, Someday, Scheduled, Logbook matches the assumption. EN and DE strings are present for every new resource. The CockpitShell.kt edit (one line in the exhaustive `when`) is outside `touches` but required by the enum entry and is recorded as an assumption. The Logbook refactor keeps its tag and heading semantics unchanged.

Verification note: Gradle was not run by the reviewer; the parent reported `./gradlew ktlintCheck lintDebug` clean and the lists/navigation unit tests passing (ScheduledListTest 6/6, ScheduledInAppTest 1/1).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | ScheduledViewModel has no view-model test; its list and order wiring is unverified**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/ListViewModels.kt:188`
    - Category: Tests
    - Problem: `WaitingViewModel` and `SomedayViewModel` are covered by `ListViewModelsTest.kt` (lines 82-84) and `ListRoutesTest.kt` (lines 69, 93, 107), but `ScheduledViewModel` is constructed by no unit test. `ScheduledListTest` calls `byScheduledDate` directly and drives `ScheduledContent` with a hand-built state; `ScheduledInAppTest` only waits for any of LOADING/EMPTY/LIST/ERROR, so it would pass with any list. Nothing checks that the view model observes `TaskList.SCHEDULED` and applies `::byScheduledDate`.
    - Why it matters: the grouped LazyColumn relies on that order. `groupConsecutiveBy` opens a new section for every change of key, so unsorted input with a date repeated in two runs would produce the duplicate lazy key `section-{date}`, and Compose throws on duplicate keys. Dropping or changing the order argument, or requesting the wrong list, would not be caught by any test.
    - Suggested fix: add a `scheduled()` factory in `ListViewModelsTest` (or a route test in `ListRoutesTest`) that loads tasks for SCHEDULED from the fake repository out of date order and asserts `uiState.tasks` is in date order with undated tasks last. Completing a task from that view model could be covered at the same time.

## P3 Low

- [ ] **CR-2 | ListSection key is untyped, so the Scheduled header needs an unchecked cast**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/ScheduledScreen.kt:57`
    - Category: Quality
    - Problem: `ListSection` (ListUi.kt:125) takes `key: (GtdTask) -> Any?` and `header: @Composable (Any?) -> Unit`, so `ScheduledContent` casts `date as LocalDate?` in the header. The compiler cannot connect the key type to the header type.
    - Why it matters: if the key lambda ever returns another type (for example a formatted string), this compiles but fails at runtime with a ClassCastException. A typed section would catch that at compile time.
    - Suggested fix: make it `class ListSection<K>(val key: (GtdTask) -> K, val header: @Composable (K) -> Unit)` with `section: ListSection<*>?` (or a generic parameter on `LiveTaskListContent`), and remove the cast.

## Review conclusion

Material issues found; fix before completing the task. (No P0 or P1. CR-1 is a test gap that is worth closing before `done`. CR-2 is optional cleanup.)
