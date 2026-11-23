# Code Review

Date: 2026-11-23

Task: and-gtd-tickler-view - Android: Tickler list view (what comes back when)

Scope: `git diff ad19e25d7..6cd38d1ca` (one implementation commit, committed changes only)

Requirements:
- tasks/and-gtd-tickler-view.md acceptance criteria and recorded assumptions
- SPEC.md §5.1, §5.3
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- Server contract of `GET /api/v1/tasks` (`TaskController.list`, `TaskService.specification`)

Git range:
- Branch: main
- HEAD: 6cd38d1ca
- Base/start: ad19e25d7d90d000f8910e2aa566b04e765a09c6
- Commits reviewed: 1

Summary: 0 P0, 1 P1, 0 P2, 0 P3

Gradle was not run for this review (a test-writer was using the build directory at the same time). The review is based on reading the code.

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Reviewed revision has no tests for the Tickler view**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/` (no Tickler test in the reviewed range; the only test change is one line in `ui/navigation/CockpitDestinationTest.kt:35`)
    - Category: Tests
    - Problem: Criterion 6 says "unit/Compose tests cover the criteria above". At 6cd38d1ca nothing tests `TaskListsRepository.observeTickler`/`loadTickler` (the 10 yearly windows, `to` inclusive at most 365 days, the filter to `ticklerDate` after today, and `distinctBy` for a task whose due date and tickler date fall in different windows), `byTicklerDate`, `TicklerViewModel`, `TicklerContent` (the "Back on" row text, the empty text "Nothing in the tickler", loading, offline and error states, completing a row), `backOnDate` (adds the year when the date is not in the current year), or the new EN/DE strings.
    - Why it matters: The acceptance criterion is not met at the reviewed revision. The window arithmetic and the client-side filter are the parts most likely to break later.
    - Suggested fix: Add tests modeled on `ScheduledViewModelTest`, `ScheduledListTest`, `TaskListsRepositoryTest` and `ScheduledStringsTest`. Check the server's 366-day limit by asserting the `from`/`to` pairs the fake API receives. This finding is resolved if the test-writer working in parallel adds this coverage and it passes.

## P2 Medium

None.

## P3 Low

None.

## Notes (verified, no finding)

- Server contract: with `from`/`to`, `TaskService.specification` returns open, non-trashed tasks whose `dueDate` or `ticklerDate` is in the range, including tasks still in a tickler. The client's windows `[start, start+1y-1d]` give `DAYS.between` of at most 365, below the server's `>= 366` rejection. Windows start at today+1 and do not overlap. Paging uses the existing `allPages`. `completed` defaults to false, so done tasks are excluded.
- The tickler `today` is read again on every load (`{ LocalDate.now(clock) }`), so the view stays correct after midnight. Tasks whose tickler date has come are filtered out on the client and are also moved to the Inbox on the server before the list is read.
- Tapping a row opens the task detail through `LiveTaskRow` and `opensTask`. Edits reload the list through `TaskEdits.changes`. Completing, the stale banner, the loading, error, offline and empty states, and Retry all reuse the shared `LiveTaskListContent` and `LiveTaskListViewModel`, the same way Scheduled, Someday and Waiting do. There is no pull to refresh, which matches the existing live lists and is recorded as an assumption.
- The secondary constructor that refactors `LiveTaskListViewModel` keeps the existing subclasses' behavior unchanged.
- The destination is in `NavSection.More` right after Scheduled, with its own icon (`Alarm`). EN and DE strings were added, and SPEC.md §5.3 was updated as criterion 1 requires.
- The 10-year horizon, the `GtdModels.kt` change outside `touches`, and the date-format choice are recorded assumptions.

## Review conclusion

Material issues found; fix before completing the task. CR-1 (tests for the Tickler view) is the only finding. It is resolved if the test-writer's tests cover it. The production code was found correct.
