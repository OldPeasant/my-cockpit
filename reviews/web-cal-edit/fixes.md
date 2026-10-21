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

---

# Round 2

Date: 2026-10-21

Task: web-cal-edit - Calendar event editing

Scope: current task; finding CR-1 (P2) of the round-2 review in `reviews/web-cal-edit/review.md`. Base ae67cd27, task commits c622626c, 8bc57227, bbcb7d26, e4f89d8e, 2a34cb24. All changes stay in `web/src/app/features/calendar/edit`.

## Fixed findings

### CR-1 (round 2) — A generated occurrence whose series changed elsewhere can't be reread; "Try again" never succeeds and the view isn't refreshed

Files changed:
- `web/src/app/features/calendar/edit/event-dialog.ts`
- `web/src/app/features/calendar/edit/event-editor.ts`
- `web/src/app/features/calendar/edit/event-dialog.spec.ts`
- `web/src/app/features/calendar/edit/event-editor.spec.ts`

Problem:
When `EventEditService.reread` found no occurrence (series moved with ALL, split with FOLLOWING, override moved more than a day), `load()` threw and showed "The event could not be loaded" with "Try again", which could never succeed. Cancel closed with `undefined`, so `EventEditor.open` returned `false` and the view kept the stale occurrence.

Fix:
- `EventDialogResult` gets a new value `'changed'`. When `reread` returns `undefined`, `load()` closes the dialog with `'changed'` instead of showing the load-failed banner. This happens on first open and on Reload after a 409/412. Other load failures (network, 404 of a single event) still show "could not be loaded" with "Try again", where a retry can work.
- `EventEditor.open` shows an info snackbar with the new i18n id `@@calendar.edit.goneElsewhere` ("\"Swim\" was changed elsewhere and is no longer at this time. The calendar shows its current events."). It returns `true` for `'changed'`, so `calendar-view.ts` refetches its occurrences (`if (await this.editor.open(...)) void this.occurrences.refresh()`, already covered by the view spec "opens a clicked event in the editor and refetches after a change").
- Specs:
  - dialog: "closes as changed when the series no longer has the occurrence". This replaces the old spec that expected the load-failed state, which was the reported bug. It also checks there is no "Try again".
  - dialog: "closes as changed when Reload after a 412 finds the occurrence gone".
  - editor: "asks for a refetch and says why when the occurrence changed elsewhere" (result `'changed'` gives `true` and the info message). The existing `open` spec now also checks that no info message appears for a cancel.
  - editor with the real `MatDialog`: open a generated occurrence, save, get a 412, click Reload, `reread` returns `undefined`. The dialog closes, `open` resolves `true` (the view refetches), and the info message is shown once.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts'` — Pass (11 files, 212 tests)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts'` — Pass (11 files, 212 tests)
- `npx prettier --check` on the four changed files — Pass

Full test suite:
- `cd web && npm run check` — Pass (api:check up to date, lint "All files pass linting", e2e typecheck, test:ci 114 files / 2274 tests, ng build; the initial-bundle budget warning was already there and is unrelated)

Lint/type-check/build:
- Included in `npm run check` above — Pass

`.taskcheck`:
- Not run by the fixer (only web code changed; `npm run check` is the web part of it). The parent runs it before `done`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
