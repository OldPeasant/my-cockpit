---
title: Calendar views
status: in_progress
owner: dell26/agent-4
deps: [p0-web-shell,cal-local-api,p0-web-sse,p0-e2e-harness]
touches: [web/src/app/features/calendar/views]
prio: 1
lease_until: 2026-10-20T23:15:42Z
---
## Goal
Day/week/month/agenda (SPEC.md §6.3).

## Acceptance criteria
- [ ] FullCalendar with week (default), day, month, list views, week start + time zone from settings, calendar visibility toggles with colors
- [ ] Loads occurrences for the visible range (out-of-order safe), live updates via SSE
- [ ] Vitest component/service tests cover range loading and toggles; `cd web && npm run check` passes
- [ ] Playwright `e2e/calendar-views/views.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/calendar-views/views.spec.ts`, see p0-e2e-harness): weekly event shows correctly across the DST week

## Notes
- ASSUMPTION (2026-10-21 00:32+0200 by dell26/agent-4): Calendar visibility toggles persist the calendar's 'visible' flag on the server (PATCH /calendar/calendars/{id}); the view fetches occurrences of all calendars and filters by visibility client-side, so a toggle needs no refetch.
- ASSUMPTION (2026-10-21 00:32+0200 by dell26/agent-4): FullCalendar skeleton + classic theme CSS are added as global styles in web/angular.json (FullCalendar's documented setup): they exceed the 8 kB per-component style budget; cost ~21 kB raw / ~4.5 kB gzip on the initial bundle. Classic theme variables are mapped to --mat-sys-* tokens.
- ASSUMPTION (2026-10-21 00:32+0200 by dell26/agent-4): View and date live in the URL (/calendar?view=day|week|month|agenda&date=yyyy-MM-dd, replaceUrl), agenda = FullCalendar listWeek; edited calendar.routes.ts and app.spec.ts BUILT list outside 'touches' because the route must load the view.
