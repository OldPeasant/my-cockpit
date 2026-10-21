---
title: Calendar task overlay and cross-links
status: in_progress
owner: dell26/agent-3
deps: [web-cal-edit,web-gtd-data,web-links-panel,notes-api]
touches: [web/src/app/features/calendar/overlay,web/src/app/features/calendar/views]
prio: 2
lease_until: 2026-10-21T18:42:40Z
releases: 1
extended: 
---
## Goal
Tasks in the calendar and linking events (SPEC.md §6.3).

## Acceptance criteria
- [x] Optional overlay of due tasks and ticklers in day/week views; event detail has linked items, 'Create task from event', 'Create note for event'
  - verified (2026-10-21 19:46+0200 by dell26/agent-1): Playwright overlay.spec.ts against the docker compose e2e stack: week view shows due task + tickler with overlay on; event dialog lists linked items, 'Create note for event' links both ways; Vitest task-overlay/event-links/calendar-view specs (toggle, day/week only, create task/note) -> 357 passed (rev 915e9caa)
- [x] Vitest component/service tests cover overlay toggle and create-linked actions; `cd web && npm run check` passes
  - verified (2026-10-21 19:46+0200 by dell26/agent-1): cd web && npx ng test --include calendar/** gtd/data/** -> 18 files, 357 passed (overlay toggle incl. no refetch while off, create task/note from event); cd web && npm run check -> exit 0, 2433 tests (test-writer, rev 915e9caa)
- [x] Playwright `e2e/calendar-overlay/overlay.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/calendar-overlay/overlay.spec.ts`, see p0-e2e-harness): create note for event → linked both ways
  - verified (2026-10-21 19:38+0200 by dell26/agent-1): cd web && npm run e2e -- e2e/calendar-overlay/overlay.spec.ts (podman compose e2e stack, chromium, rev 43826282c) -> 23 passed incl. 'shows due tasks and ticklers in the week view' and 'creates a note for an event, linked both ways'

## Notes
- ASSUMPTION (2026-10-21 18:51+0200 by dell26/agent-1): Added from/to (local dates, inclusive) to GET /api/v1/tasks (server gtd/api+domain, outside touches): with a range it returns open tasks whose due OR tickler date lies in it, including tasks still in a tickler. The list API hid ticklers and had no date range, so the overlay could not show ticklers otherwise
- ASSUMPTION (2026-10-21 19:05+0200 by dell26/agent-1): Overlay toggle is per browser (localStorage cockpit.calendar.taskOverlay), off by default; shown in day/week only (month/agenda say so). Due tasks with a time take a 30-min slot at the due time in the settings zone, without a time all day; ticklers show all day on their tickler date. Overlay entries are read-only (no drag); clicking one opens /tasks/{id}
- ASSUMPTION (2026-10-21 19:05+0200 by dell26/agent-1): Event detail = the event dialog (edit/, outside touches): it gets an app-calendar-event-links panel. Links point to occurrence.eventId (the series master for generated occurrences, the override otherwise). 'Create task from event' = Inbox task titled like the event; 'Create note for event' = note titled '<event> (<yyyy-MM-dd>)'; both linked item->event, the dialog stays open
- ASSUMPTION (2026-10-21 19:05+0200 by dell26/agent-1): Added route /calendar/events/:id (calendar.routes.ts, outside touches): the server's EVENT deep link had no web route. It redirects to /calendar?event=id, which jumps to the event's date and opens it (a series as a whole, without scope prompt). matchesTask (gtd/data) learned from/to so optimistic TaskStore updates follow the server rule
- ASSUMPTION (2026-10-21 19:34+0200 by dell26/agent-1): 'Create task from event' titles the Inbox task '<event> (<yyyy-MM-dd>)' like the note (SPEC §6.3 'pre-filled title + date'), without a due date: the event date is context, not a deadline the user chose (review CR-1)
- release by dell26/agent-1: Implementation, tests, e2e and all 3 criteria verified; quality helpers done: review round 3 (reviews/web-cal-overlay/review.md) = 0 findings, tests.md + fixes.md committed (round-1 CR-1..CR-4 fixed). LEFT: (1) write reviews/web-cal-overlay/quality.json per docs/QUALITY.md: reviewed_revision = current hash of the test-writer commit (was 915e9caa before rebase; production code unchanged since round 2), findings CR-1..CR-4 fixed, fixer ran in round 1; commit it. (2) run sh scripts/check.sh in full: server+web passed on 2026-10-21, android part not finished within my 590s shell limit (no failure seen). (3) python tasks.py done web-cal-overlay. No re-review needed unless code changes.
