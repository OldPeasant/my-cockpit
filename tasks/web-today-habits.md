---
title: Today view: habits due
status: in_progress
owner: dell26/agent-1
deps: [web-today-events,web-habits]
touches: [web/src/app/features/gtd/today]
prio: 3
lease_until: 2026-10-22T02:26:00Z
---
## Goal
Today shows habits due.

## Acceptance criteria
- [ ] Habits due today with one-tap check
- [ ] Vitest component/service tests cover habits section; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-22 03:48+0200 by dell26/agent-1): Today habits section is hidden when the user has no active habits, shows 'No habits due today' when habits exist but none is due; registered via provideTodaySection in today.routes.ts (order 10); messages.xlf left to a dedicated extract like other web tasks
