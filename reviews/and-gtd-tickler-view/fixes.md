# Fix Report

Date: 2026-11-23

Task: and-gtd-tickler-view - Android: Tickler list view (what comes back when)

Scope: current task. Finding CR-1 from the round-1 code review (review.md as committed in 76b14ce7e).

## Fixed findings

### CR-1 — Reviewed revision has no tests for the Tickler view

Files changed (by the test-writer in commit 76b14ce7e; the fixer changed no code):
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/TicklerRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/TicklerViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/TicklerListTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/TicklerStringsTest.kt`

Problem:
At the implementation commit 6cd38d1ca, acceptance criterion 6 ("unit/Compose tests cover the criteria above") was not met: nothing tested `observeTickler`/`loadTickler` (10 yearly windows, at most 365 days each, filter to tickler dates after today, `distinctBy` across windows), `byTicklerDate`, `TicklerViewModel`, `TicklerContent` (Back-on row text, empty, loading, offline, error, completing), `backOnDate`, or the new EN/DE strings.

Fix:
No production change was needed (the round-1 review found the production code correct). The test-writer added the missing tests in 76b14ce7e. The fixer checked by reading each test file that every item named in CR-1 is covered:
- Yearly windows: `TicklerRepositoryTest` "asks for the next ten years..." asserts the exact `from`/`to` pairs of the first, second and last window (2026-11-24..2027-11-23 through 2035-11-24..2036-11-23 with today 2026-11-23), that there are 10 windows, that consecutive windows have no gap, and that no `list` filter is sent. Each window is 365 days, below the server's 366-day limit.
- Client-side filter and de-duplication: "keeps only tasks with a tickler date after today..." drops a tickler date of today and of an earlier day, drops a task with only a due date, carries `ticklerDate`, and lists a task returned in two windows once.
- Paging and failure: "follows the next page within a year" (cursor) and "a failing year makes the whole load an error, not a partial list".
- `byTicklerDate` and `TicklerViewModel`: clock's today passed to the repository, order by date then position with undated last, completing (optimistic hide, undo, reload), retry, reload after a date change, offline read-only, first-load error.
- `TicklerContent` and `backOnDate`: Back-on row text with and without the year, EN and DE, no back-on line for an undated task, completing checkbox with busy row disabled, tap opens detail, empty text, stale read-only banner, loading, first-load error with Retry, German empty state.
- Strings: EN/DE keys match, German texts are translated, only `tickler_back_on` has exactly one placeholder.

Production code is unchanged between 6cd38d1ca and HEAD; the only change after 76b14ce7e is the round-2 review report.

Validation:
- Gradle was not run by the fixer (the project check was running in the background; a parallel Gradle run would clash on the build directory).
- The round-2 code review (reviews/and-gtd-tickler-view/review.md) recorded `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.lists.Tickler*'` — Pass (22 tests: TicklerListTest 8, TicklerRepositoryTest 4, TicklerStringsTest 3, TicklerViewModelTest 7; 0 failures) and marks CR-1 resolved.

## Not fixed

None.

## Validation summary

Focused tests:
- Not run by the fixer (see above); round-2 review run of the 22 Tickler tests — Pass.

Full test suite:
- Not run by the fixer; the parent worker is running the project check in the background.

Lint/type-check/build:
- Not run by the fixer, for the same reason.

`.taskcheck`:
- Not run by the fixer; being run by the parent worker.

Remaining unresolved findings:
0

## Final status

No valid findings required code changes. CR-1 was resolved by the test-writer's tests in commit 76b14ce7e, confirmed by reading the tests and by the round-2 review.
