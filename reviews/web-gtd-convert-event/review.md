# Code Review

Date: 2026-10-21

Task: web-gtd-convert-event - Inbox/task detail: to calendar

Scope: `git diff 7de4e6a30..HEAD -- web` (task commits f89cf8772, d45cbf24a, 215bac8c8)

Requirements:
- `tasks/web-gtd-convert-event.md` acceptance criteria (1) the dialog picks a calendar and time and converts; (2) Vitest component/service tests cover the convert flow and `cd web && npm run check` passes
- Recorded assumptions #1-#3 for web-gtd-convert-event (`python tasks.py assumptions`)
- SPEC.md §5.3 (inbox processing "To calendar"), AGENTS.md "Project notes", docs/ARCHITECTURE.md
- Server contract: `TaskToEventController` / `ConvertToEventRequest` (POST /api/v1/tasks/{id}/convert-to-event)

Git range:
- Branch: main
- HEAD: 215bac8c860861e853d08fabf6f8733fea88e119
- Base/start: 7de4e6a303c48c0bff62fe777de0730c354fa9c2
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 1 P2, 2 P3

Notes on what was checked and found correct: the request matches `ConvertToEventRequest` (timed: `start`/`end` instants plus `timeZone`; all-day: `startDate` plus exclusive `endDate` = inclusive end + 1 day, with no times). Wall-clock to instant conversion reuses `calendar/edit/event-times`, which handles DST. The calendar view reads `?date=` in the settings zone, and the dialog uses the same zone. The inbox checks after the dialog closes that processing is still running and the item is still current. It blocks hotkeys while the dialog is open and uses `runSeq` for the status line. The task detail checks after the dialog closes that the task is the same, that no save runs and that no conversion runs. A conversion that finishes late does not navigate away from another task. Both buttons are disabled while a conversion runs. The ETag goes in `If-Match`. A calendar load failure shows an error with a retry, and a failed settings load falls back to the browser zone. I found no security issues: no unsafe HTML or logging was added, and the interpolations use Angular escaping.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Task detail does not show 400 field errors from convert-to-event (unknown calendar, invalid times), so the conversion fails without telling the user**
    - File: `web/src/app/features/gtd/task-detail/task-detail.ts:366` (called from `convert()` at `task-detail.ts:306`)
    - Category: Correctness
    - Problem: `TaskDetailService.convertToEvent` calls the API with `silent()`, so the HTTP layer shows nothing. On failure, `convert()` calls `failed(error)`. For a 400 with field errors, `failed()` only stores them in `serverErrors`, keyed by server field name, and then returns. The task form shows only task fields (`title`, `notesMd`, `scheduledDate`, `estimate`, ...). The 400 errors of this endpoint are about event fields: `calendarId` (an unknown calendar, or no default calendar; see `CalendarEventService.create` and the `TaskToEventService` KDoc), `start`, `end`, `startDate`, `endDate` and `timeZone` (`EventCommands`). None of these fields is rendered. So the button turns enabled again, nothing navigates, no message appears and `saveError` stays null. One realistic trigger: the calendar is deleted or its source removed after `CalendarStore` (a root singleton) cached the list, and the user then picks it in the dialog. The test `shows field errors of a 400 answer and opens no calendar` (`task-detail.spec.ts:1901`) uses a `title` field error, which the form happens to show, so it hides this gap.
    - Why it matters: the user thinks the task moved to the calendar or does not know why it didn't. Recovering requires guessing.
    - Suggested fix: in the "To calendar" path, map 400 errors to a visible message. For example, set `saveError` with the field messages, or the problem's `userMessage`, for fields the task form does not show. Or let `convert()` take its own error handler. Add a test with a `calendarId` and/or `end` field error that checks a visible alert.

## P3 Low

- [ ] **CR-2 | The helpers for "event start day" and "local zone" are copied three times**
    - File: `web/src/app/features/gtd/inbox/inbox-page.ts:357-367`, `web/src/app/features/gtd/task-detail/task-detail.ts:416-423`, `web/src/app/features/gtd/task-detail/to-calendar-dialog.ts:70`
    - Category: Quality
    - Problem: `movedEvent()` in the inbox and `eventDate()` in the task detail compute the same "start day of the event on its own clocks" logic. `localZone()` and `browserTimeZone()` are the same function, defined three times.
    - Why it matters: if the rule for which day the calendar opens on changes (for example, use the settings zone), it has to be changed in two places, and they can drift apart.
    - Suggested fix: export one `eventStartDay(event)` (and one zone helper) from `to-calendar-dialog.ts` or another shared gtd helper, and use it in both callers.

- [ ] **CR-3 | Ticking "All day" on the default times between 22:00 and 22:59 creates a two-day all-day event**
    - File: `web/src/app/features/gtd/task-detail/to-calendar-dialog.ts:177-186` (`resetTimes`) together with `convertRequest` at `:52-60`
    - Category: Correctness
    - Problem: the default is the next full hour plus 1 hour. When the dialog opens between 22:00 and 22:59, that is 23:00 until 00:00 the next day, so `endDate` is tomorrow. Ticking "All day" keeps both dates, so the request becomes `startDate` = today and `endDate` = today + 2. The user probably meant a single day. The dates are shown in the form, so the user can correct this.
    - Why it matters: a small UX surprise on a common path ("All day" right after the dialog opens).
    - Suggested fix: when switching to all-day while the times are untouched (`touchedTimes` is false), set `endDate` to `startDate`. Or derive the default all-day range from the start date only.

## Review conclusion

Material issues found; fix before completing the task. CR-1 should be fixed (P2: the convert flow can fail without any message). CR-2 and CR-3 are optional cleanups. Both acceptance criteria are otherwise met by the implementation and its tests. I did not re-run the test suite; the test report (`reviews/web-gtd-convert-event/tests.md`) records gtd specs passing and `npm run check` passing.
