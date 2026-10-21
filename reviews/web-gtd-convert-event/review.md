# Code Review

Date: 2026-10-21

Task: web-gtd-convert-event - Inbox/task detail: to calendar

Scope: `git diff c099b9d14..HEAD -- web`, round 2 on the final code. It includes the fixer commit b840b0add and the round-2 test commit fc2fc03a3.

Requirements:
- `tasks/web-gtd-convert-event.md` acceptance criteria: (1) the dialog picks a calendar and a time and converts; (2) Vitest component and service tests cover the convert flow, and `cd web && npm run check` passes
- Assumptions recorded in the task file (the dialog's scope, navigation after converting, task-store.ts touched outside `touches`)
- SPEC.md §5.3 ("To calendar"), AGENTS.md "Project notes", docs/ARCHITECTURE.md
- The parent's instruction: the fixer's CR-1 change to the normal Save path is accepted. A 400 error for a field the form does not show now goes to the save bar.
- Reports: round-1 review, `reviews/web-gtd-convert-event/fixes.md`, `reviews/web-gtd-convert-event/tests.md`

Git range:
- Branch: main
- HEAD: fc2fc03a3904723945a8b9ac948305d733671abc
- Base/start: c099b9d147c92bfdaa0ad675e2d3ce41bf5a5a9e
- Commits reviewed: 6 (7c9695f3e, afd0f01bd, 69fcd18fd, 1b0b6f95a, b840b0add, fc2fc03a3). 1b0b6f95a only adds the review report. There are no uncommitted changes.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Round 1 (summary)

Round 1 reviewed the implementation before the rebase onto the current main. It found 0 P0, 0 P1, 1 P2 and 2 P3:
- CR-1 (P2): task detail did not show 400 errors from convert-to-event for fields the form does not show (`calendarId`, `start`, `end`, ...). The conversion failed silently.
- CR-2 (P3): the "event start day" and "local zone" helpers existed in three copies.
- CR-3 (P3): ticking "All day" on the default times between 22:00 and 22:59 created a two-day all-day event.

## Disposition of the round-1 findings

- **CR-1: resolved.** In `task-detail.ts:365-375`, `failed()` maps the server field names first. It still puts every error in `serverErrors`, so a field the form shows keeps its inline error. Errors for fields not listed in `SHOWN_FIELDS` (`task-detail.ts:425-434`) now go to `saveError`, which the save bar always shows (`task-detail.html:340`), not only when the form is dirty. `SHOWN_FIELDS` is typed as `Record<DraftField, true>`, so it stays in step with `DraftField`. New tests: `task-detail.spec.ts` "shows 400 errors of the calendar in the save bar…" (`calendarId`) and "…of the times in the save bar next to errors of the form" (`end` plus `title`). Both check the alert, that nothing navigates, and that the button is enabled again. The Save path has three tests for the accepted behavior change.
- **CR-2: resolved.** `browserTimeZone()` and `eventStartDay()` are now exported once from `to-calendar-dialog.ts:70-79`. The inbox's `movedEvent` and the task detail's navigation both use them, and the copies are removed. The logic did not change. `eventStartDay` has its own unit tests: event zone, browser zone, all-day, no start instant, and the fallback to today. `gtd/data` does not import `task-detail`, so there is no import cycle.
- **CR-3: resolved.** `resetTimes()` (`to-calendar-dialog.ts:187-197`) sets `endDate` to the start day while all-day is ticked. The `allDay` change handler re-applies the defaults while `touchedTimes` is false. Dates the user typed are kept, because every date and time input calls `timesChanged()`. Unticking brings back the timed default. The tests cover 22:30 in Zurich, a late settings answer while all-day is ticked, and a typed end date.

## Whole-diff check (round 2)

I re-checked the final code for:
- request/contract alignment with `ConvertToEventRequest` (timed instants and zone; all-day with an exclusive end date);
- stale-dialog and stale-result guards in the inbox (`processing()`, current item, `asking`) and in the task detail (same task, no save running, no conversion running; a late conversion does not navigate);
- the loading and error states of the dialog (calendar load failure with retry, no writable calendar, settings fallback);
- the ETag/If-Match path.

The fixes did not regress any of these. Re-running `resetTimes()` on an all-day toggle uses the current time again. This only affects untouched defaults, which are defined as "the next full hour", so it is acceptable. I found no security concerns: there are no new HTML sinks or logging, and the interpolations use Angular escaping.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-4 | The fallback to `userMessage` fails when several hidden field errors all have empty messages**
    - File: `web/src/app/features/gtd/task-detail/task-detail.ts:373`
    - Category: Correctness
    - Problem: `hidden.map((field) => field.message).join(' ') || error.userMessage` falls back only when the joined string is empty. With two or more hidden errors whose messages are all empty, the join gives `' '`, which is truthy. `saveError` is then set to whitespace, and the save bar shows an empty `role=alert`. The test "falls back to the message of the answer…" covers only one empty field, so it does not catch this. Server validation messages are normally not empty, so this is unlikely in practice.
    - Why it matters: when it happens, the user sees an empty error and does not know what failed. That is the failure CR-1 was meant to remove.
    - Suggested fix: drop empty messages before joining, for example `hidden.map((f) => f.message).filter(Boolean).join(' ') || error.userMessage`. Optionally, extend the test to two hidden fields with empty messages.

## Review conclusion

No material issues found. CR-1, CR-2 and CR-3 from round 1 are resolved, and both acceptance criteria are met by the implementation and its tests. CR-4 is an optional P3 cleanup. I did not re-run the test suite. The fixer and test-writer reports record that 735 gtd tests pass, that `npm run check` passes and that lint is clean.
