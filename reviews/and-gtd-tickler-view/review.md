# Code Review

Date: 2026-11-23

Task: and-gtd-tickler-view - Android: Tickler list view (what comes back when)

Scope: `git diff ad19e25d7..76b14ce7e` (implementation commit 6cd38d1ca plus the test commit 76b14ce7e, committed changes only). This is round 2. Production code is unchanged since round 1 (6cd38d1ca), so this round reviews the new tests and rechecks CR-1.

Requirements:
- tasks/and-gtd-tickler-view.md acceptance criteria and recorded assumptions
- SPEC.md §5.1, §5.3
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- Server contract of `GET /api/v1/tasks` (`TaskController.list`, `TaskService.specification`)

Git range:
- Branch: main
- HEAD: 76b14ce7e
- Base/start: ad19e25d7d90d000f8910e2aa566b04e765a09c6
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Validation run for this review: `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.lists.Tickler*'` -> BUILD SUCCESSFUL. TicklerListTest 8, TicklerRepositoryTest 4, TicklerStringsTest 3, TicklerViewModelTest 7: 22 tests, 0 failures, 0 errors, 0 skipped.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Status of round-1 findings

- [x] **CR-1 | Reviewed revision has no tests for the Tickler view**: resolved in 76b14ce7e.
    - `TicklerRepositoryTest` runs the real `TaskListsRepository` against MockWebServer. It asserts the exact `from`/`to` pairs of all 10 yearly windows (start tomorrow, no gaps, each window at most 365 days, so under the server's 366-day limit), that no `list` filter is sent, the client-side filter (a tickler date of today or earlier is dropped, a task without a tickler date is dropped), `distinctBy` across windows, paging within a year, and that a failing year makes the whole load an error instead of a partial list.
    - `TicklerViewModelTest` checks that the clock's today is passed to the repository, the `byTicklerDate` order (date, then position, undated last), completing (optimistic hide, undo offer, reload), retry, the new order after a reload (the date was changed in the detail), read-only when offline, and the first-load error state.
    - `TicklerListTest` (Robolectric Compose) checks the "Back on" row text with and without the year, no back-on line for an undated task, the complete checkbox (a busy row is disabled), tapping a row opens the detail through `LocalTaskOpener`, the empty text "Nothing in the tickler", the stale banner with rows read-only, loading, the first-load error with Retry, and the German row text and empty text.
    - `TicklerStringsTest` checks that the EN/DE keys match, the German texts are translated, and only `tickler_back_on` has a placeholder.
    - The tests check visible behavior and the HTTP requests, not internals. Mocks are only used where the view model test isolates the view model; the repository logic is covered against a real HTTP fake. No existing test was weakened. The only change to an existing test is the added Tickler entry in `CockpitDestinationTest`.

## Notes (verified, no finding)

- Production code is identical to round 1. The round-1 notes still apply: the server contract and window arithmetic are correct, today is read again on every load, open/edit/complete/retry reuse the shared live-list components, the destination is placed next to Scheduled with its own icon, EN/DE strings and SPEC.md §5.3 were updated, and the 10-year horizon, the `GtdModels.kt` change outside `touches`, no pull to refresh, and the date format are recorded assumptions.
- `ktlintCheck` and `lintDebug` (criterion 6) were not run for this review. The parent worker runs them in `.taskcheck` and records them as evidence.

## Review conclusion

No material issues found. CR-1 is resolved by the tests in 76b14ce7e, which pass.
