# Fix Report

Date: 2026-10-21

Task: web-cal-edit - Calendar event editing

Scope: current task; findings CR-1, CR-3, CR-4, CR-5, CR-6 of `reviews/web-cal-edit/review.md` (CR-2 was already resolved by commit 94ba2234 and was not redone). Base 411c8953, task commits 41e831c3, 4db7535e, 94ba2234. All changes stay in `web/src/app/features/calendar/edit` and `web/src/app/features/calendar/views`.

## Fixed findings

### CR-1 — Dialog load/Reload showed the view's stale times

Files changed:
- `web/src/app/features/calendar/edit/event-dialog.ts`
- `web/src/app/features/calendar/edit/event-edit.service.ts`
- `web/src/app/features/calendar/edit/event-dialog.spec.ts`
- `web/src/app/features/calendar/edit/event-edit.service.spec.ts`

Problem:
`load()` fetched the event again but took start/end/all-day from the `OccurrenceResponse` the view loaded earlier and only copied the new version. After a Reload, a change to just the end time sent the stale start with a current If-Match, which overwrote the remote change (a lost update).

Fix:
- Single event or override: the times (`allDay`, `start`, `end`, `startDate`, `endDate`, `timeZone`) and the version now come from the fetched `EventResponse`.
- Generated occurrence (new helper `isGenerated`): the dialog first re-reads the occurrence with the new `EventEditService.reread()`. It calls `GET /calendar/events?from=&to=&calendarIds=` over a range around the original start (±1 day for timed series; for all-day series from the day before to two days after) and matches on `seriesId` + `occurrence`. Times and version come from that answer. If the occurrence has become an override in the meantime, the override is loaded and edited. If the series no longer has the occurrence, the dialog shows the existing "could not be loaded" state.
- Tests: a single event whose server times differ from the view, both on first open and after a 412 Reload, which checks the PATCH carries the current start. Also tests for a generated occurrence re-read with new times and version, a generated occurrence that became an override, an occurrence that is gone, and `reread` range/matching (timed and all-day) in the service spec.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts'` — Pass

### CR-3 — Override dragged with FOLLOWING/ALL used a just-fetched master version

Files changed:
- `web/src/app/features/calendar/edit/event-edit.service.ts`
- `web/src/app/features/calendar/edit/event-dialog.ts`
- `web/src/app/features/calendar/edit/event-edit.service.spec.ts`
- `web/src/app/features/calendar/edit/event-dialog.spec.ts`

Problem:
For an override with FOLLOWING/ALL, `target()` addressed the master and fetched its version right before the PATCH, so If-Match checked nothing.

Fix:
An override is now always addressed by its own id with `occurrence.version` and `?scope=<scope>` (no `occurrence` parameter). The server's `resolve()` (CalendarEventService.kt) supports this and checks the override's own version. The extra GET and the now-unused `masterVersion` parameter of `update`/`delete` (and the dialog field) are gone. Tests check all three scopes on an override go to `o1` with `If-Match: "2"` and no GET, and that DELETE with ALL goes to the override too.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts'` — Pass

### CR-4 — createRequest sent attendees for a non-LOCAL target calendar

Files changed:
- `web/src/app/features/calendar/edit/event-form.ts`
- `web/src/app/features/calendar/edit/event-dialog.ts`
- `web/src/app/features/calendar/edit/event-form.spec.ts`
- `web/src/app/features/calendar/edit/event-dialog.spec.ts`

Problem:
Guests added while a local calendar was selected were still sent after switching a new event to a synced calendar.

Fix:
`createRequest(form, withAttendees)` sends `attendees` only when `withAttendees`. The dialog passes `attendeesEditable()`, the same rule `patchRequest` uses. Tests: a unit test for `createRequest(..., false)`, and a dialog test that adds a guest, switches to a Google calendar and checks that the create body has no `attendees`.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts'` — Pass

### CR-5 — instantOf resolved skipped DST times before the change west of UTC

Files changed:
- `web/src/app/features/calendar/edit/event-times.ts`
- `web/src/app/features/calendar/edit/event-times.spec.ts`

Problem:
`instantOf('2026-03-08','02:30','America/New_York')` returned 06:30Z (01:30 EST) instead of 07:30Z (03:30 EDT).

Fix:
When the two offset passes disagree and the corrected instant still doesn't show the typed wall clock (a skipped time), the code uses `wall - min(first, second)`. Times that are valid but sit near a change keep the corrected instant. New tests cover New York skipped times (02:00, 02:30, 01:59, 03:00), New York ambiguous times, a quarter-hour round-trip on the New York change day, and Zurich 02:30 → 03:30. The existing Zurich tests still pass.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts'` — Pass

### CR-6 — "New event" rounded to the next hour in the browser zone

Files changed:
- `web/src/app/features/calendar/edit/event-times.ts`
- `web/src/app/features/calendar/views/calendar-view.ts`
- `web/src/app/features/calendar/edit/event-times.spec.ts`
- `web/src/app/features/calendar/views/calendar-view.spec.ts`

Problem:
`setMinutes(60, 0, 0)` rounded in the browser's zone. With settings in a zone with a half-hour offset (Asia/Kolkata), the default start was :30 on the settings clock.

Fix:
New `nextFullHour(now, timeZone)` works out the next hour from `wallClockOf`, rolls over midnight with `addDays` and converts back with `instantOf`. `newEvent()` uses it with the settings zone. Tests cover Zurich, Kolkata, exactly on the hour, midnight rollover and the New York skipped hour, plus a view test with Kolkata settings that checks the start is a full hour in Kolkata (:30 UTC) and within the next hour.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts'` — Pass

## Not fixed

### CR-2 — Missing Vitest tests

Status:
Invalid (already resolved)

Reason:
Resolved before this fix round by commit 94ba2234 (the parent said so). This fix round added more tests for each fix above.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts'` — Pass (11 files, 204 tests)

Full test suite:
- `cd web && npm run test:ci` — Pass (114 files, 2266 tests). The first run had 127 failures in unrelated specs (app, files, links). An immediate re-run with no code change passed fully, so this looked like a flaky run under load, not a regression.

Lint/type-check/build:
- `cd web && npm run lint` — Pass (All files pass linting)
- `npx prettier --check src/app/features/calendar` — the changed files are formatted. `edit/scope-dialog.ts` (not changed in this round) still has a formatting warning that was there before.

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("all checks passed": gate tests, server check, web check including build, android). An earlier attempt failed only in the android step because the Gradle daemon was stopped from outside ("stop command received"); the re-run passed.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
