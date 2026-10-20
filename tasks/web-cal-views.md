---
title: Calendar views
status: in_progress
owner: dell26/agent-4
deps: [p0-web-shell,cal-local-api,p0-web-sse,p0-e2e-harness]
touches: [web/src/app/features/calendar/views]
prio: 1
lease_until: 2026-10-20T23:25:46Z
---
## Goal
Day/week/month/agenda (SPEC.md §6.3).

## Acceptance criteria
- [x] FullCalendar with week (default), day, month, list views, week start + time zone from settings, calendar visibility toggles with colors
  - verified (2026-10-21 01:09+0200 by dell26/agent-4): calendar-view.ts/calendar-store.ts reviewed (review.md final, no findings); calendar-view.spec.ts covers default week, day/month/list views, settings week start + time zone, colored visibility toggles; e2e views.spec.ts checks all 4 views -> pass
- [x] Loads occurrences for the visible range (out-of-order safe), live updates via SSE
  - verified (2026-10-21 01:09+0200 by dell26/agent-4): cd web && npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts' -> 68 passed (incl. out-of-order range answers dropped, SSE EVENT/CALENDAR change reloads)
- [x] Vitest component/service tests cover range loading and toggles; `cd web && npm run check` passes
  - verified (2026-10-21 01:09+0200 by dell26/agent-4): cd web && npm run check -> lint clean, 55 files / 968 tests passed, build ok; calendar coverage 249/249 stmts, 92/93 branches
- [ ] Playwright `e2e/calendar-views/views.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/calendar-views/views.spec.ts`, see p0-e2e-harness): weekly event shows correctly across the DST week

## Notes
- ASSUMPTION (2026-10-21 00:32+0200 by dell26/agent-4): Calendar visibility toggles persist the calendar's 'visible' flag on the server (PATCH /calendar/calendars/{id}); the view fetches occurrences of all calendars and filters by visibility client-side, so a toggle needs no refetch.
- ASSUMPTION (2026-10-21 00:32+0200 by dell26/agent-4): FullCalendar skeleton + classic theme CSS are added as global styles in web/angular.json (FullCalendar's documented setup): they exceed the 8 kB per-component style budget; cost ~21 kB raw / ~4.5 kB gzip on the initial bundle. Classic theme variables are mapped to --mat-sys-* tokens.
- ASSUMPTION (2026-10-21 00:32+0200 by dell26/agent-4): View and date live in the URL (/calendar?view=day|week|month|agenda&date=yyyy-MM-dd, replaceUrl), agenda = FullCalendar listWeek; edited calendar.routes.ts and app.spec.ts BUILT list outside 'touches' because the route must load the view.
