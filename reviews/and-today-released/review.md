# Code Review

Date: 2026-10-26

Task: and-today-released - Android Today: show tasks released to Next today under 'Scheduled for today' (releasedOn / scheduled=today)

Scope: the whole task diff `git diff 8a5b73fa2..8a3a65622`, final revision 8a3a65622f523d6b9b97bc27619df013c488bbba. It is made of the implementation commit 0ff2cfc2b plus the test-writer's additions (`git diff 0ff2cfc2b..8a3a65622 -- android`, test files only). Changes under tasks/ in the range are board bookkeeping and were not reviewed.

Requirements:
- tasks/and-today-released.md (acceptance criterion and recorded assumption)
- api/openapi.yaml (`TaskResponse.releasedOn`, `GET /api/v1/tasks` `scheduled=today`)
- server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskService.kt (`releaseDue`, `move`, the `scheduledToday` filter)
- web/src/app/features/gtd/today/today-view.ts (reference behavior)
- AGENTS.md project notes (lint, Room cache, conventions)

Git range:
- Branch: main
- HEAD: 8a3a65622f523d6b9b97bc27619df013c488bbba
- Base/start: 8a5b73fa2048571afb147f3dd61df1852be170f8
- Commits reviewed: implementation 0ff2cfc2b and the test-writer's test commit(s) up to 8a3a65622

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## What was checked

- Semantics match the server. `releaseDue` sets `releasedOn = today` and clears `scheduledDate` when it moves a task to NEXT. `move` clears `releasedOn` when the task changes list. The `scheduled=today` filter is "NEXT and releasedOn == today, or SCHEDULED and scheduledDate <= today". The new `sectionOf` branch `task.releasedOn == today -> SCHEDULED` gives the same result for NEXT tasks. A task released on an earlier day is excluded, as the server excludes it. Because Android only loads the TODAY and NEXT views, it does not show tasks that are still SCHEDULED and not yet released. This gap is negligible: the server runs `releaseDue` before tasks are read, so by the time Android loads them those tasks are already in NEXT with `releasedOn` set. The section precedence (overdue, then due, then scheduled) is unchanged.
- Data path. `releasedOn` is passed through every place a task is built: `TaskResponse.toEntity`, `CachedTaskEntity.toDomain` and `TaskResponse.toTask` in gtd/lists. A grep finds no other place that constructs `GtdTask` or `CachedTaskEntity` and would drop the field. NEXT refresh loads every page through cursor paging (GtdRefresh.kt), so released tasks at the end of a long NEXT list are not cut off.
- Cache schema. Room version 1 -> 2, the exported schema `2.json` contains the `releasedOn TEXT` column, and `GtdDataModule` already uses `fallbackToDestructiveMigration(dropAllTables = true)`. Dropping the cache only forces a reload, which is acceptable for a read-only copy (as recorded in the assumption).
- Tests in the implementation commit. TaskRepositoryTest checks the round trip of `releasedOn` from JSON through the Room cache. TodayViewModelTest covers `sectionOf` for released today, released earlier and due-with-release, plus a ViewModel-level test that only the task released today appears under SCHEDULED.
- Tests the test-writer added (0ff2cfc2b..8a3a65622, test files only, no production code changed):
  - TaskRepositoryTest: a NEXT task without `releasedOn` comes back from the cache with a null `releasedOn` and a null `scheduledDate`.
  - TaskListsRepositoryTest: `TaskResponse.toTask` keeps `releasedOn` for a released task and leaves it null for one that was not released. The test uses the file's existing `TASKS` dispatcher key and `taskJson` extra-JSON helper.
  - TodayViewModelTest: overdue still wins over released today; released today with a future due date is SCHEDULED; an earlier release does not hide a schedule for today; a `releasedOn` in the future is not for today.
  - These tests assert observable behavior (the cached domain model, the loaded list items and section membership), not implementation details. They are deterministic and use fictitious data.
- Lint and unit tests: the parent reported `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` on 8a3a65622 -> BUILD SUCCESSFUL. Gradle could not be re-run in this review.
- The edits outside `touches` (gtd/data, gtd/lists) are minimal field pass-throughs and are disclosed in the recorded assumption.
- No security, logging or secret concerns. The change adds one date field.

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
