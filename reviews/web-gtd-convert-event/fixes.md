# Fix Report

Date: 2026-10-21

Task: web-gtd-convert-event - Inbox/task detail: to calendar

Scope: current task; findings CR-1, CR-2, CR-3 of `reviews/web-gtd-convert-event/review.md`

## Fixed findings

### CR-1 — Task detail does not show 400 field errors from convert-to-event

Files changed:
- `web/src/app/features/gtd/task-detail/task-detail.ts`
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts`

Problem:
`failed()` stored every 400 field error in `serverErrors`, but the form only shows errors for task
fields (`DraftField`). Errors for event fields (`calendarId`, `start`, `end`, `startDate`, `endDate`,
`timeZone`, `allDay`) showed nowhere. The button was enabled again, nothing navigated, and the user
saw no message.

Fix:
A new `SHOWN_FIELDS` constant (`Record<DraftField, true>`, so it stays complete when the type changes)
lists the fields the form shows. `failed()` still stores every mapped field error in `serverErrors`,
so task-form fields such as `title` keep showing next to their field as before. It also writes the
messages of fields the form does not show to `saveError`, which is the `role=alert` in the save bar.
If those messages are empty, it uses `ApiError.userMessage`. The save path uses the same `failed()`,
so a 400 on a field the form does not show now also appears in the save bar there instead of being
silently dropped.

Tests added (`task-detail.spec.ts`, "to calendar"):
- `shows 400 errors of the calendar in the save bar and opens no calendar` (`calendarId`): the alert
  appears in the save bar, there is no navigation, and the button is enabled again with its label.
- `shows 400 errors of the times in the save bar next to errors of the form` (`end` + `title`): the
  `end` message appears in the save bar, and the `title` message appears only at the title field.
  There is no navigation and the button is enabled again.

Validation:
- `npx ng test --watch=false --include='src/app/features/gtd/**/*.spec.ts'` — Pass (31 files, 720 tests)

### CR-2 — "Event start day" and "local zone" helpers copied three times

Files changed:
- `web/src/app/features/gtd/task-detail/to-calendar-dialog.ts`
- `web/src/app/features/gtd/task-detail/task-detail.ts`
- `web/src/app/features/gtd/inbox/inbox-page.ts`

Problem:
`movedEvent()` (inbox) and `eventDate()` (task detail) each computed the event's start day.
`localZone()` (twice) and `browserTimeZone()` were the same function.

Fix:
`to-calendar-dialog.ts` now exports `browserTimeZone()` and a new `eventStartDay(event)`, which uses
the same logic as before: all-day or no start gives `startDate`, falling back to `localDate()`.
Otherwise it gives the wall-clock date of `start` in `event.timeZone`, falling back to the browser
zone. Task detail uses `eventStartDay` instead of `eventDate`/`localZone`, and the inbox's
`movedEvent` delegates to it. The imports that became unused (`wallClockOf`, `EventResponse`) are
removed. `gtd/data` does not import `task-detail`, so this adds no import cycle. The behavior is
unchanged: the existing task-detail tests (zone of the event, local zone without a zone, all-day
start date) and the inbox "To calendar" tests still pass.

Validation:
- `npx ng test --watch=false --include='src/app/features/gtd/**/*.spec.ts'` — Pass
- `npx ng lint --max-warnings=0` — Pass

### CR-3 — "All day" on the 22:xx default times creates a two-day event

Files changed:
- `web/src/app/features/gtd/task-detail/to-calendar-dialog.ts`
- `web/src/app/features/gtd/task-detail/to-calendar-dialog.spec.ts`

Problem:
When the dialog opens between 22:00 and 22:59, the default is 23:00 to 00:00 the next day. Ticking
"All day" kept the next-day end date, so the request became a two-day all-day event.

Fix:
`resetTimes()` now sets `endDate` to the start day while `allDay` is ticked. The `allDay` change
handler calls `resetTimes()` when the user has not edited the times (`touchedTimes` is false).
Ticking "All day" therefore gives a one-day event, and unticking it restores the default timed end
(23:00 to 00:00 the next day). A late settings answer that calls `resetTimes()` while "All day" is
ticked also keeps the one-day range. Dates the user typed are kept.

Tests added (`to-calendar-dialog.spec.ts`, fake clock at 2026-10-21T20:30Z, 22:30 in Zurich):
- `makes the default times spanning midnight a one-day event when ticking all day`: with "All day"
  ticked, the end date is the start day. Unticking restores 23:00, 2026-10-22 and 00:00. Ticking
  again and submitting sends `startDate 2026-10-21`, `endDate 2026-10-22` (exclusive), so one day.
- `keeps dates the user typed when ticking all day`: a typed end date stays.

Validation:
- `npx ng test --watch=false --include='src/app/features/gtd/**/*.spec.ts'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/gtd/**/*.spec.ts'` — Pass (31 files, 720 tests)

Full test suite:
- `cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, ng build) — Pass (121 files, 2421 tests; build OK)

Lint/type-check/build:
- `cd web && npx ng lint --max-warnings=0` — Pass
- `npx prettier --check` on the 5 changed source/spec files — Pass, after `prettier --write` reformatted `task-detail.ts`
- Type check and build are covered by `npm run check` — Pass

`.taskcheck`:
- Not run. `scripts/check.sh` also runs the Gradle builds for the server and Android, which this
  helper cannot run. The web part of it (`npm run check`) passes. The parent worker runs `.taskcheck`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
