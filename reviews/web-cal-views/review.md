# Code Review

Date: 2026-10-21

Task: web-cal-views - Calendar views (SPEC.md §6.3)

Scope: final review of the whole task, `git diff 793b2ae3..a71c49ec` (commits 20d90f7b..a71c49ec). Only committed changes were reviewed; the working tree was clean. This review replaces the first review (CR-1, CR-2 at HEAD db91b54c) and confirms the fixes for CR-1, CR-2 and TW-1 described in `reviews/web-cal-views/fixes.md`.

Requirements:
- tasks/web-cal-views.md: acceptance criteria 1-4 and the three recorded assumptions
- SPEC.md §4.2 (ETag/If-Match, change events), §6.3
- docs/ARCHITECTURE.md (web stack, conventions, testing)
- AGENTS.md project notes (feature folders, i18n, lint, logging, fictitious test data)
- Server contract: `server/.../calendar/api/CalendarService.kt` (`CALENDAR` change events on create and update; `visible` can be changed on read-only calendars too)

Git range:
- Branch: main
- HEAD: a71c49ec19025d9d792b688ac7b6339e9ead6c43
- Base/start: 793b2ae33e4f74e6bf9d60e659b9a1e677fb0409
- Commits reviewed: 8

Summary: 0 P0, 0 P1, 0 P2, 0 P3

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

Fixes from earlier rounds, checked in the current code:
- CR-1: `CalendarStore` now reloads on `merge(resync$, events$('CALENDAR'))` (`calendar-store.ts:57`). The view's effect fetches occurrences again when the set of calendar ids changes (`calendar-view.ts:68-123`). Tests cover the reload, the next toggle's new `If-Match`, and the occurrence query of a calendar created elsewhere.
- CR-2: a save no longer drops list answers that are still in flight. List answers are merged by version with `newest()` (`calendar-store.ts:117-126`), and out-of-order list answers are still dropped by `requestSeq`. Tests cover both a stale answer and a newer one.
- TW-1: a save answer only replaces the known calendar when the known version is not higher (`calendar-store.ts:94-96`). The test-writer's test for this case passes.

Acceptance criteria, as reviewed in the code:
1. FullCalendar 7 shows day, week (the default), month and agenda (`listWeek`). `timeZone` and `firstDay` come from the settings. Calendar toggles show each calendar's color, are optimistic, and are saved one after another with `PATCH` + `If-Match`. A failed save rolls the toggle back and shows a toast.
2. `OccurrenceStore` loads `from`/`to` of the visible range for all calendar ids and shows only the newest answer, also when it fails. It refetches on `EVENT` changes and on a resync. Loading state is cleared on every path.
3. Unit tests cover range loading, out-of-order answers, toggles, rollback, the change and resync paths, and the URL/time-zone helpers. I ran `npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts'`: 4 files, 68 tests passed.
4. `e2e/calendar-views/views.spec.ts` runs the browser in America/New_York with the settings in Europe/Zurich. It checks the weekly event at 09:00 on both sides of the DST change in the week, day, month and agenda views, plus a toggle, a reload and an SSE live update. I did not run it here.

Evidence status: `python tasks.py verify web-cal-views` shows no criterion recorded yet. Before `done`, the parent worker must record evidence, including an actual run of the e2e command against the e2e stack for criterion 4.
