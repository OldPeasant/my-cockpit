# Fix Report

Date: 2026-10-21

Task: web-cal-views - Calendar views (SPEC.md §6.3)

Scope: current task; findings CR-1 and CR-2 from `reviews/web-cal-views/review.md` (code at HEAD 49aa93ee, base 63d16ee2).

## Fixed findings

### CR-1 — Calendar list ignores `CALENDAR` change events; a refused toggle leaves a stale version

Files changed:
- `web/src/app/features/calendar/views/calendar-store.ts`
- `web/src/app/features/calendar/views/calendar-store.spec.ts`
- `web/src/app/features/calendar/views/calendar-view.spec.ts`

Problem:
`CalendarStore` reloaded only on `resync$`. The server publishes `CALENDAR` change events (`CalendarService.kt`, `CALENDAR_CHANGE_TYPE = "CALENDAR"`) for calendar create/update. Because the store ignored them, calendars created or changed elsewhere never showed up, and new calendars' occurrences were not fetched. Toggles also kept sending a stale `version`, so they failed with 412.

Fix:
The store now reloads on `merge(resync$, events$('CALENDAR'))`, the same pattern `OccurrenceStore` uses for `EVENT`. The view's existing effect refetches occurrences when the set of calendar ids changes. In `calendar-store.spec.ts`, the `ChangeEventsService` fake now also provides `events$`.

New tests:
- `calendar-store.spec.ts`: "reloads after a calendar change notification, not after other changes". It checks that an `EVENT` change triggers no reload, that a `CALENDAR` change does, that the new calendar appears, and that the next toggle sends the new version in `If-Match`.
- `calendar-view.spec.ts`: "fetches the events of a calendar created elsewhere after its change notification". It checks that the new calendar's id reaches the `listEventOccurrences` query.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts'` — Pass (4 files, 62 tests)

### CR-2 — A successful toggle throws away a list reload that is still in flight

Files changed:
- `web/src/app/features/calendar/views/calendar-store.ts`
- `web/src/app/features/calendar/views/calendar-store.spec.ts`

Problem:
After a successful PATCH, `this.requestSeq++` threw away the whole answer of any list request still in flight. Other calendars' updates and new calendars in that answer were lost until the next reload.

Fix:
I removed the `requestSeq++` after a save. A list answer is now merged with the known state by `newest(known, answer)`: for each calendar in the answer, the known entry is kept when its `version` is higher. An answer requested before a save therefore cannot bring back the saved calendar's old visibility, while all other changes in the answer are applied. Out-of-order list answers are still dropped by `requestSeq`. Pending optimistic toggles are unaffected, because they are still applied on top of the server state.

Test changes:
- The existing test "ignores a list answer requested before a saved toggle" is renamed to "keeps a saved toggle over a list answer requested before it". Its intent is kept: the stale answer for `family` does not undo the saved `visible: false` / version 2. It now also asserts that the same answer's new calendar (`school`) and a newer `work` (version 4) are shown.
- Added "takes a calendar of a list answer when its version is newer than the saved one". A genuinely newer server state wins.
- The test-writer's related tests ("keeps an unsaved toggle shown over a list reloaded by a resync", "saves a queued toggle with its own version when a resync dropped the calendar") pass unchanged.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts'` — Pass (4 files, 62 tests)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts'` — Pass (4 files, 62 tests)

Full test suite:
- `cd web && npm run check` (api:check, lint with `--max-warnings=0`, e2e:typecheck, test:ci, ng build) — Pass: "All files pass linting.", 55 test files / 962 tests passed, build succeeded. The build prints a warning that the initial bundle exceeds its budget (634.21 kB vs 500 kB). The check still passes, and the warning is unrelated to these changes: they only touch the lazy `calendar-view` chunk.

Lint/type-check/build:
- Included in `npm run check` above — Pass

`.taskcheck`:
- Not run by the fixer (`sh scripts/check.sh` covers all parts; only web code changed and `npm run check` passed). The parent worker runs it before `done`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
