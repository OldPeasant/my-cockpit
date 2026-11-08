# Fix Report

Date: 2026-11-08

Task: and-scheduled-list - Android: Scheduled list screen

Scope: current task; findings CR-1 and CR-2 from reviews/and-scheduled-list/review.md

## Fixed findings

### CR-2 - ListSection key is untyped, so the Scheduled header needs an unchecked cast

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/ListUi.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/ScheduledScreen.kt`

Problem:
`ListSection` took `key: (GtdTask) -> Any?` and `header: @Composable (Any?) -> Unit`, so `ScheduledContent` had to cast `date as LocalDate?`. A key lambda of a different type would compile and fail at runtime.

Fix:
`ListSection` is now `ListSection<K>(key: (GtdTask) -> K, header: @Composable (K) -> Unit)`. The section rendering (group into consecutive runs, a header item keyed `section-{key}`, then the rows with dividers) moved from `LiveTaskListContent` into an internal member `ListSection.items(scope, tasks, row)`, where `K` is known, so the header gets a typed key. `LiveTaskListContent` takes `section: ListSection<*>? = null`, so the Waiting, Someday and project screens, which pass no section, are unchanged. `groupConsecutiveBy` is generic too: `fun <K> List<GtdTask>.groupConsecutiveBy(key: (GtdTask) -> K): List<Pair<K, List<GtdTask>>>`. `ScheduledContent` builds `ListSection<LocalDate?>` and passes `date` without a cast. ListSectionTest compiles unchanged, because `List` and `Pair` are covariant. The rendered output, lazy keys and order are the same as before.

Validation:
- `cd android && ./gradlew --offline -q ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.lists.*'` - Pass (ktlint clean; all 14 lists test classes passed with 0 failures, among them ListSectionTest 6/6, ScheduledListTest 11/11, ScheduledInAppTest 1/1, ScheduledViewModelTest 9/9, ListScreensTest 14/14, ListRoutesTest 3/3, ListViewModelsTest 13/13, LogbookScreenTest 17/17)

### CR-1 - ScheduledViewModel has no view-model test

Files changed:
- None by the fixer. The test-writer had already added `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/ScheduledViewModelTest.kt`.

Problem:
No unit test constructed `ScheduledViewModel`, so nothing checked that it observes `TaskList.SCHEDULED` and applies the date order.

Fix:
I checked that the added test covers the finding. It builds `ScheduledViewModel(repository, contexts, completion, clock)` with a mocked repository that returns the SCHEDULED list out of date order (`later, undated, soon, sameDayFirst`). It checks `observe(TaskList.SCHEDULED, any())` and that `uiState.tasks` is `soon, later, sameDayFirst, undated`: earliest date first, ties kept in list position, undated last. Other tests in it cover completion, undo (the task comes back in date order), reload after completion, retry, a reload with new data in date order, read-only, and a first-load error. That is everything CR-1 suggested. No code change was needed.

Validation:
- `cd android && ./gradlew --offline -q testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.lists.*'` - Pass (ScheduledViewModelTest 9/9)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew --offline -q ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.lists.*'` - Pass (14 classes, 0 failures, 0 errors)

Full test suite:
- Not run by the fixer (the parent runs the full Android suite and `.taskcheck`)

Lint/type-check/build:
- ktlintCheck - Pass; Kotlin compile of main and unit-test sources - Pass (part of the run above); lintDebug not run by the fixer

`.taskcheck`:
- Not run (the parent runs `sh scripts/check.sh`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
