# Fix Report

Date: 2026-10-21

Task: web-cal-overlay - Calendar task overlay and cross-links

Scope: current task; findings CR-1 to CR-4 of `reviews/web-cal-overlay/review.md` (round 1), with the parent worker's decisions. Git scope: working tree on top of HEAD 6f8a2e95 (task base f683bbfb; task commits 52982ff2, 4649447c, 8b72a9c6, 37523ba0, 6f8a2e95). The review cites commit hashes from before a rebase; the code it reviewed is the same.

## Fixed findings

### CR-1 — "Create task from event" does not pre-fill the event's date

Files changed:
- `web/src/app/features/calendar/overlay/event-links.service.ts`
- `web/src/app/features/calendar/overlay/event-links.ts`
- `web/src/app/features/calendar/overlay/event-links.spec.ts`

Problem:
SPEC.md §6.3 asks for a pre-filled title and date. The note got `<title> (<yyyy-MM-dd>)` but the Inbox task only got the plain event title.

Fix:
As the parent decided: `EventLinksService.createTask(occurrence, timeZone)` now titles the Inbox task like the note, `<event title> (<yyyy-MM-dd>)` from `occurrenceDate`, with the plain title when the occurrence has no date. It sets no `dueDate`. A shared `datedTitle` helper builds both titles. The panel passes its `timeZone` input. The success notification uses the created task's title, so it now reads `Added to Inbox: Planning (2026-03-23)`. The spec test asserts the dated title and the notification text. A new test covers an occurrence with no date (plain title). The e2e spec `web/e2e/calendar-overlay/overlay.spec.ts` does not create a task from an event, so it did not need a change.

Validation:
- `npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts' --include='src/app/features/gtd/data/**/*.spec.ts'` — Pass (18 files, 347 tests)

### CR-3 — Range error message is off by one

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/TaskController.kt`

Problem:
The check rejects `DAYS.between(from, to) >= 366`, but the message said `to` "must be at most 366 days after from". The real limit is 365 days after `from`, which makes a 366-day inclusive range.

Fix:
Only the message changed. It now says "the range must span at most 366 days" (field `to`), and the rule is the same. No server test asserted the old text (grep for `after from` and `366 days` in `server/src/test`). `api/openapi.yaml` already says "at most 366 days" for the range, so it did not need regenerating.

Validation:
- `./gradlew --offline ktlintCheck test --tests 'ch.sonensei.cockpit.gtd.TaskDateRangeWebTests'` (in `server/`) — Pass

### CR-4 — Turning the overlay off keeps refetching tasks on every task change

Files changed:
- `web/src/app/features/calendar/overlay/task-overlay.ts`
- `web/src/app/features/calendar/overlay/task-overlay.spec.ts`
- `web/src/app/features/calendar/views/calendar-view.ts`

Problem:
`setEnabled(false)` only hid the entries. The `TaskStore` kept its query, and `ListStore` refetched it on every TASK change event and SSE resync, so the page kept sending `GET /api/v1/tasks?from=..&to=..` for data it did not show.

Fix:
A new `OverlayTaskStore extends TaskStore` in `overlay/task-overlay.ts`. Its `refresh()` override fetches only while `active` is true. That one method covers load, refresh, change events and resync, because `ListStore` routes all of them through `refresh()`. `TaskOverlay` injects it, sets `active` from the remembered toggle in its constructor and updates it in `setEnabled`. Turning the overlay on again calls `load` for the shown range, so it fetches fresh data. The calendar view now provides `OverlayTaskStore` instead of `TaskStore`. `gtd/data` was not changed. The existing mock-based suite now provides the mock as `OverlayTaskStore`. Prettier also rewrapped two existing lines of that spec file that were too long. A new suite, "TaskOverlay with its OverlayTaskStore", uses the real store with a fake `Api` and `ChangeEventsService`. It checks that change events and resyncs refetch while the overlay is on. It also checks that after turning the overlay off, CREATED/UPDATED TASK events, a resync and `refresh()` send no `listTasks` request and show no entries, and that turning it back on loads the range again. Last, it checks that a remembered "on" follows change events. With the gate removed, the "turned off" test fails, so it catches the bug.

Validation:
- `npx ng test --watch=false --include='src/app/features/calendar/overlay/task-overlay.spec.ts'` with the gate temporarily removed — 1 failed (expected), gate restored
- `npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts' --include='src/app/features/gtd/data/**/*.spec.ts'` — Pass (18 files, 347 tests)

## Not fixed

### CR-2 — New `from`/`to` branch of `matchesTask` has no unit test

Status:
Invalid (already resolved)

Reason:
Already covered: `web/src/app/features/gtd/data/task-store.spec.ts`, describe "with a from/to range (calendar overlay, server rule)", committed in 6f8a2e95 by the test-writer. It covers due and tickler dates at both inclusive ends, no dates, dates just outside the range, a future tickler inside the range, completed tasks, other filters and a half range. Nothing was redone. Those tests pass in the run above.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts' --include='src/app/features/gtd/data/**/*.spec.ts'` — Pass, 347 tests
- `cd web && npx eslint src/app/features/calendar/overlay/ src/app/features/calendar/views/calendar-view.ts` — Pass
- `cd web && npx prettier --check` on the changed web files — Pass after `--write` on `task-overlay.spec.ts`
- `cd server && ./gradlew --offline ktlintCheck test --tests 'ch.sonensei.cockpit.gtd.TaskDateRangeWebTests'` — Pass

Full test suite:
- `cd web && npm run check` (api:check, lint, e2e typecheck, test:ci, build) — Pass, 122 files, 2423 tests

Lint/type-check/build:
- Included in `npm run check` and the server `ktlintCheck` above — Pass

`.taskcheck`:
- Fail, unrelated flake: `sh scripts/check.sh` took about 6 min and failed 1 of 3570 server tests. The failing test is `IcsFeedClientTests` "a feed of exactly the maximum size is read, a larger Content-Length is refused before reading": it got an IOException from the local feed server. This calendar ICS code is not touched by the task. Rerunning `./gradlew --offline test --tests 'ch.sonensei.cockpit.calendar.infra.ics.IcsFeedClientTests'` passed. The parent should run `.taskcheck` again.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed. `.taskcheck` failed once on an unrelated flaky server test that passes when run alone.
