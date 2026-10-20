---
title: Calendar views
status: todo
owner:
deps: [p0-web-shell,cal-local-api,p0-web-sse,p0-e2e-harness]
touches: [web/src/app/features/calendar/views]
prio: 1
---
## Goal
Day/week/month/agenda (SPEC.md §6.3).

## Acceptance criteria
- [ ] FullCalendar with week (default), day, month, list views, week start + time zone from settings, calendar visibility toggles with colors
- [ ] Loads occurrences for the visible range (out-of-order safe), live updates via SSE
- [ ] Vitest component/service tests cover range loading and toggles; `cd web && npm run check` passes
- [ ] Playwright `e2e/calendar-views/views.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/calendar-views/views.spec.ts`, see p0-e2e-harness): weekly event shows correctly across the DST week

## Notes
