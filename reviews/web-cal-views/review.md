# Code Review

Date: 2026-10-21

Task: web-cal-views - Calendar views (SPEC.md §6.3)

Scope: committed changes of the task, `git diff 63d16ee2..db91b54c -- web` (commits c9628772, e203dea5, db91b54c). Uncommitted spec-file edits in the working tree (calendar-store/-view/-views/occurrence-store `*.spec.ts`, presumably from the concurrent test-writer) were not reviewed.

Requirements:
- tasks/web-cal-views.md acceptance criteria 1-4 and recorded assumptions
- SPEC.md §4.2, §4.7, §6.3, §6.4
- docs/ARCHITECTURE.md (web stack, conventions, testing)
- AGENTS.md project notes (signals/zoneless, theme tokens, i18n, lint, no secrets in logs)

Git range:
- Branch: main
- HEAD: db91b54c72b6df630f1a055b743d4bf27b9d6864
- Base/start: 63d16ee20ba13bf7317a41ae736998279a5d4177
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Calendar list ignores `CALENDAR` change events; a refused toggle leaves a stale version**
    - File: `web/src/app/features/calendar/views/calendar-store.ts:50`
    - Category: Correctness
    - Problem: `CalendarStore` reloads only on `resync$`. It does not subscribe to `events$('CALENDAR')`, but the server announces every calendar create and update, including visibility, color, rename and sync, as `CALENDAR` change events (`server/.../calendar/api/CalendarService.kt:70,89,122`, `CALENDAR_CHANGE_TYPE = "CALENDAR"`). While the page is open, a calendar created or changed on another device or tab, or by a source sync, never reaches the list. A new calendar's id is also missing from `calendarIds`, so its occurrences are never fetched. Because the store is root-scoped and nothing refreshes it, `save()` (line 80) keeps sending the old `version`. After another client changes a calendar, each toggle of that calendar gets a 412 and an error toast. This lasts until the user leaves the page and comes back, or until an SSE reconnect.
    - Why it matters: AC 2 asks for live updates via SSE. The occurrence store follows `EVENT` changes, but the calendar data the view filters and colors by does not. That leads to missing events and repeated toggle failures.
    - Suggested fix: Merge `events$('CALENDAR')` into the reload trigger next to `resync$`, like `OccurrenceStore` does with `EVENT`. The existing `requestSeq` and pending-visibility logic already protects optimistic toggles. Add a unit test showing that a `CALENDAR` change reloads the list, and that a newly added calendar's id reaches the occurrence query.

## P3 Low

- [ ] **CR-2 | A successful toggle throws away a list reload that is still in flight**
    - File: `web/src/app/features/calendar/views/calendar-store.ts:88`
    - Category: Correctness
    - Problem: After a successful PATCH, `this.requestSeq++` drops the whole answer of any list request still in flight, for example one started by a resync. That stops the PATCHed calendar from going stale. But the answer's changes to all other calendars are lost too, and no new load is started. The list stays outdated until the next resync.
    - Why it matters: This is a small consistency gap. It is rare (a toggle and a resync at the same time), and fixing CR-1 makes it less visible because the next change event reloads anyway.
    - Suggested fix: When a newer list answer is dropped after a save, start a follow-up `load()`. Or, when that answer arrives, merge it but keep the saved calendar's newer `version`.

## Review conclusion

Material issues found; fix before completing the task.

The rest of the change matches the criteria:
- FullCalendar shows day, week (default), month and agenda (`listWeek`) views, with `timeZone` and `firstDay` from the settings. FC 7.1.3 supports named zones natively, so the `datesSet` instants are real instants.
- Calendar toggles show each calendar's color, are optimistic, are saved with If-Match one after another, and roll back on failure.
- Only the newest occurrence request is shown, also when it fails. `EVENT` and resync notifications trigger a refetch.
- All strings use i18n ids. Styles use theme tokens, and no logging was added.
- The unit tests cover range loading, out-of-order answers and toggles.
- The e2e spec runs against the real stack: browser in America/New_York, settings in Europe/Zurich, the DST week in all four views, toggles, a reload and an SSE live update.

No evidence has been recorded with `tasks.py verify` yet; the parent worker must record it.
