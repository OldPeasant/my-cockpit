# Code Review

Date: 2026-10-21

Task: web-cal-edit - Calendar event editing

Scope: the task's committed changes from base to HEAD. The parent named base 2215690d and commits bb14ce67/de88a74c. The branch has since been rebased onto d25a4e1b, and the commits are now 0c25d690 (implementation) and 26643b8f (e2e). `git diff --stat de88a74c 26643b8f -- web` is empty, so the code is the same. Reviewed: production code in `web/src/app/features/calendar/edit/` and `web/src/app/features/calendar/views/`, plus `web/e2e/calendar-edit/edit.spec.ts`. Uncommitted `*.spec.ts` files from the parallel test-writer were left out.

Requirements:
- tasks/web-cal-edit.md (acceptance criteria 1-4, and the three recorded assumptions)
- SPEC.md §6.2 (conflict policy, recurring scopes), §6.3 (UI), §6.4 (API)
- docs/ARCHITECTURE.md (web stack, i18n, conventions), AGENTS.md project notes
- api/openapi.yaml contract as implemented in server/.../calendar/api/CalendarEventController.kt and CalendarEventService.kt (`resolve`, `update`, `delete`, `splitSeries`)

Git range:
- Branch: main (local clone; task commits not yet on origin/main)
- HEAD: 26643b8f03d306fe076a6f54a6a4b487cb16d9e1
- Base/start: d25a4e1b2c2c11c5696b50c15a9ff6cee78f10a5 (merge-base with origin/main; originally 2215690d2dfb060e4775929706126add895a07c8)
- Commits reviewed: 2

Summary: 0 P0, 2 P1, 1 P2, 3 P3

What was checked and found correct:
- Event id, scope, occurrence and ETag for each kind of target. A single event goes to its own id with no scope. A generated occurrence goes to the master with `scope` and `occurrence`, and uses the master's version. An override with THIS goes to its own id with its own version. This matches `resolve()` on the server.
- The THIS option is hidden when the repeat rule or calendar changes (`scopesFor`). The server rejects those changes for THIS (`updateOccurrence`) and accepts them for FOLLOWING (`splitSeries`).
- `patchRequest` sends only changed fields. All time fields are sent together when any of them changes, and `timeZone` is sent when the event switches from all-day to timed.
- Drag revert: `info.revert()` runs when the scope prompt is cancelled and when the save fails.
- Read-only calendars: their events can't be dragged (`toEventInput`, `editable`), and the dialog disables the form and hides Delete and Save. Attendees are editable only in LOCAL calendars.
- The dialog cannot be closed while a write is running.
- Strings use i18n with `@@` ids. The scope and event dialogs are labelled.
- DST conversion in `instantOf` was checked with node for Europe/Zurich, America/New_York, Australia/Lord_Howe and Asia/Kolkata. Ambiguous times (clocks set back) are right. Skipped times have one problem in zones west of UTC (CR-5).

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | The dialog's "Reload" (and its first load) shows the times from the view's snapshot, not the event's current times**
    - File: `web/src/app/features/calendar/edit/event-form.ts:75`, `web/src/app/features/calendar/edit/event-dialog.ts:176-179`
    - Category: Correctness
    - Problem: `load()` fetches the event again with `GET /events/{id}`, but `editForm` takes the start, end and all-day values from `occurrence`. That is the `OccurrenceResponse` the calendar view loaded earlier. The reload only copies the new `version` into it (`{ ...occurrence, version: event.version }`). So when the event was moved elsewhere (the usual cause of a 412/409), the form still shows the old times, even though the banner says "Reload it to see the current version". The first open has the same problem whenever the view's list is out of date.
    - Why it matters: Criterion 2 ("'changed remotely' conflicts shown with reload") is only partly met. After the reload the If-Match version is current but the times are not. `patchRequest` sends `start` and `end` together when either changes. So if the user then changes only the end time, the old start is sent with the new version, and the remote change is overwritten without a conflict. That is a lost update, which is what the conflict flow is meant to prevent.
    - Suggested fix: For a single event or an override, take the times from the fetched `EventResponse` (`start`/`end`/`allDay`/`startDate`/`endDate`). For a generated occurrence, re-read that occurrence (for example `GET /events?from=&to=&calendarIds=` around `occurrence.occurrence`) or close the dialog and refresh the view. Add a test where the reloaded event has different times.

- [ ] **CR-2 | No committed Vitest tests for the scope prompt or conflict handling**
    - File: `web/src/app/features/calendar/edit/` (no `*.spec.ts` in `git ls-files`); `web/src/app/features/calendar/views/calendar-view.spec.ts:297` (the only spec change, a selector fix)
    - Category: Tests
    - Problem: Criterion 3 requires "Vitest component/service tests cover scope prompt and conflict handling". The reviewed commits add no tests for `EventEditService` (target and version selection), `EventEditor.move` (scope cancel, conflict snackbar, revert), `EventDialog` (conflict banner, reload, read-only, attendees), `patchRequest`/`scopesFor` or `instantOf`/`wallClockOf`.
    - Why it matters: The criterion can't be verified from the committed code, and the target and version logic is easy to break later. The parallel test-writer may add these tests. This finding is resolved once they are committed and cover at least: the scope prompt (all three scopes, plus FOLLOWING/ALL only for rule or calendar changes), 409/412 to the reload banner and snackbar, and revert on cancel or failure.
    - Suggested fix: Commit spec files under `web/src/app/features/calendar/edit/` that cover the cases above, then run `cd web && npm run check`.

## P2 Medium

- [ ] **CR-3 | Dragging an override with FOLLOWING/ALL uses an If-Match version fetched just before the write**
    - File: `web/src/app/features/calendar/edit/event-edit.service.ts:115-118` (called without `masterVersion` from `event-editor.ts:68`)
    - Category: Correctness
    - Problem: When an override is dragged and the user picks FOLLOWING or ALL, `target()` sends the change to the series master. Since `move()` passes no master version, it uses `(await this.get(seriesId)).version`, fetched right before the PATCH. That version is always current, so If-Match no longer checks anything: changes to the master made after the view loaded are overwritten without a 412. The server already supports addressing an override directly with FOLLOWING/ALL. `resolve()` returns `Target(override, master, own, override, scope)` and checks the override's own version, which the client already has in `occurrence.version`.
    - Why it matters: Lost-update protection (SPEC.md §4.2 optimistic locking) is skipped on this path, and every such drag costs an extra GET.
    - Suggested fix: For an override, send every scope to `occurrence.eventId` with `occurrence.version`, and drop the extra GET. Or, if the master must be the target, use a version the user actually saw.

## P3 Low

- [ ] **CR-4 | Attendees added while a local calendar was selected are still sent after switching a new event to a synced calendar**
    - File: `web/src/app/features/calendar/edit/event-form.ts:142`, `web/src/app/features/calendar/edit/event-dialog.ts:130-132`
    - Category: Correctness
    - Problem: In create mode, `attendeesEditable()` becomes false once a non-LOCAL calendar is chosen. The attendees list is then shown read-only with "Guests are managed by the event's calendar provider", but `createRequest` still sends `form.attendees`.
    - Why it matters: The user can't remove the guests any more, and they are sent to an external calendar, which is the case the read-only rule is meant to prevent.
    - Suggested fix: Leave out `attendees` (or clear them) in `createRequest` when the target calendar is not LOCAL, the same way `patchRequest` uses `withAttendees`.

- [ ] **CR-5 | `instantOf` resolves a skipped DST time to before the change in zones west of UTC**
    - File: `web/src/app/features/calendar/edit/event-times.ts:30-35`
    - Category: Correctness
    - Problem: The doc comment says a skipped time "resolves to the same distance after the change". This holds for Europe/Zurich (`2026-03-29 02:30` gives 03:30 CEST). For America/New_York, `instantOf('2026-03-08','02:30','America/New_York')` returns `2026-03-08T06:30:00Z`, which is 01:30 EST, an hour before the change. The correction step (`if (second !== first) instant = wall - second`) takes the other offset, and for negative offsets that moves the instant back across the change.
    - Why it matters: In a western-hemisphere zone, an event typed into the skipped hour is saved one hour earlier than the user intended, and in the opposite direction from European zones.
    - Suggested fix: When the two offset passes disagree and neither gives back the typed wall-clock time, use `wall - min(first, second)`, i.e. the offset from before the change applied to the later instant. Add tests for New_York and Zurich.

- [ ] **CR-6 | "New event" rounds to the next full hour in the browser's zone, not in the settings zone**
    - File: `web/src/app/features/calendar/views/calendar-view.ts:171-173`
    - Category: Correctness
    - Problem: `start.setMinutes(60, 0, 0)` rounds in the browser's local zone. The calendar and the dialog show times in the settings zone (the e2e test also runs with the browser in another zone). If the two zones differ by a non-whole hour (for example Asia/Kolkata settings in a UTC browser), the default start is :30 instead of a full hour.
    - Why it matters: A small default-value glitch that is inconsistent with the rest of the view, which works in the settings zone.
    - Suggested fix: Work out the next full hour from `wallClockOf(now, settings.timeZone)` and convert it back with `instantOf`.

## Review conclusion

Material issues found; fix before completing the task.
