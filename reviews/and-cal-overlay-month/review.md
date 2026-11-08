# Code Review

Date: 2026-11-08

Task: and-cal-overlay-month - Android calendar: task overlay and month view

Scope: `git diff caf3d9bd..HEAD -- android` (commits 4c48ce15 implementation, ec1ea2a0 tests); committed changes only

Requirements:
- tasks/and-cal-overlay-month.md (acceptance criteria and recorded assumptions)
- SPEC.md §6.3 (task overlay), docs/PARITY.md (calendar row)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- Server contract: server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/TaskController.kt (list filters, 366-day range), gtd/domain/TaskService.kt `releaseDue`
- Web reference: web/src/app/features/calendar/overlay/task-overlay.ts

Git range:
- Branch: main
- HEAD: ec1ea2a0eb2106d50e4528babf11470fe8a20135
- Base/start: caf3d9bd1b3b3008131b0143efa4f8448d4cb204
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 2 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A task scheduled for a day never shows on that day (today), only on future days**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/TaskOverlay.kt:118`
    - Category: Correctness
    - Problem: Scheduled marks come only from `GET /tasks?list=SCHEDULED` filtered by `scheduledDate`. Every task list read runs the tickler job first (`TaskController.list` calls `tickler.run()`), and `TaskService.releaseDue` (server `gtd/domain/TaskService.kt:239-244`) moves every task with `scheduledDate <= today` to NEXT, sets `scheduledDate = null` and `releasedOn = today`. So when the overlay loads, a task scheduled for today is no longer in the Scheduled list and has no `scheduledDate`. `taskMarks` (`TaskOverlay.kt:64`) therefore never produces a SCHEDULED mark on today; scheduled marks only ever appear on tomorrow and later. The day view of today, the most relevant day, is missing exactly the tasks the user scheduled for it.
    - Why it matters: Criterion 1 asks for scheduled tasks in day/week/agenda. As built, the scheduled overlay covers every day except the one the task is actually scheduled for, and the gap is not recorded as an assumption. (Ticklers behave the same way on the server, but the web has the same limitation there and the criterion's new part here is "scheduled".)
    - Suggested fix: Also mark tasks released into Next on a shown day. For example, when `today` is in [from, to), load `GET /tasks?today=true` (or list=NEXT filtered by `releasedOn`) and mark tasks with `releasedOn == today` as SCHEDULED on today, with a test. If this is left out on purpose, record it with `tasks.py assume`.

- [ ] **CR-2 | The toolbar no longer fits four view segments plus two icon toggles on phone widths**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt:249-280`
    - Category: Quality
    - Problem: The toolbar row now holds a weighted `SingleChoiceSegmentedButtonRow` with four segments (MONTH added through `CalendarView.entries`), the "Today" `TextButton` and two 48dp `IconToggleButton`s (the new overlay toggle added). On a 360dp-wide phone the row has 340dp (16dp + 4dp padding). The fixed children take about 166dp (Today about 70dp, plus 2 x 48dp), which leaves about 174dp, or about 43dp per segment. That is below the Material 3 button min width (58dp) and below what labels like "Agenda" need (text about 48dp + 24dp content padding). Even at 411dp it is about 56dp per segment. The labels are `maxLines = 1` without an overflow strategy, so they get clipped. Before this change (three segments, one icon button) each segment had about 74dp. Screen tests don't catch this because clipped text still exists in the semantics tree. This estimate comes from the layout arithmetic. Nobody has rendered it on a device yet.
    - Why it matters: The view switcher that the month view depends on becomes hard to read in normal phone use, in both English and German.
    - Suggested fix: Check it on a 360dp emulator or in a Compose screenshot. Then free up width, e.g. move the overlay and calendars toggles into an overflow menu or a second row, use icon-only segments, or replace the "Today" text button with an icon.

## P3 Low

- [ ] **CR-3 | Agenda task append is started before the extended range is written to the state**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModel.kt:249-258`
    - Category: Quality
    - Problem: `loadMore` calls `loadTasks(from, to, append = true)` before the `state.update` that sets `to = to`. The append coroutine drops its answer when `to > it.to` (`CalendarViewModel.kt:223`). This works today only because `TaskOverlayRepository.tasks` always suspends on the network before it updates the state, so the outer update runs first under `Dispatchers.Main.immediate`. A source that answers without suspending (a cache, a short-circuit error path) would have its appended tasks silently dropped. The tests use `StandardTestDispatcher`, which queues the launch, so they don't exercise this ordering.
    - Why it matters: This is a hidden ordering dependency between two state writers that could silently lose overlay tasks after a later change.
    - Suggested fix: Call `loadTasks(from, to, append = true)` after the `state.update` that extends `to`, inside the `Ok` branch.

## Notes (not findings)

- I checked the integration and it holds. `LocalTaskOpener.open(id, snapshot)` matches `gtd/task/TaskNavigation.kt`. The server's `from`/`to` filter is inclusive and allows at most 366 days, and the client sends `partEnd - 1` in parts of 366 days. Completed tasks are filtered out on the client, as on the web. Non-2xx answers are mapped like the calendar repository maps them, and the exception messages carry only the status.
- Lifecycle and coroutine handling look sound. Replace loads cancel the previous `tasksJob`. Stale replace or append answers are dropped by the overlay flag and range check. Switching the overlay off cancels the load and clears the tasks. There are tests for range change, toggle-off during a load, a stale append and a failed page.
- i18n: all 12 new strings and the plural exist in `values/` and `values-de/`, and a test checks that.
- No criterion has recorded evidence yet (`python tasks.py verify and-cal-overlay-month` shows all four unchecked). The parent worker must record it before `done`.

## Review conclusion

Material issues found; fix before completing the task.
