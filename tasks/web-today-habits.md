---
title: Today view: habits due
status: in_progress
owner: dell26/agent-1
deps: [web-today-events,web-habits]
touches: [web/src/app/features/gtd/today]
prio: 3
lease_until: 2026-10-22T02:51:15Z
---
## Goal
Today shows habits due.

## Acceptance criteria
- [x] Habits due today with one-tap check
  - verified (2026-10-22 04:26+0200 by dell26/agent-1): npx ng test --include='src/app/features/gtd/today/*.spec.ts' (web, Vitest) -> 3 files, 85 passed; today-habits.spec.ts covers only dueToday habits listed, one-tap check via HabitsService.setCheck, conflict/reload paths; today-view.spec.ts renders the section in the routed Today view
- [x] Vitest component/service tests cover habits section; `cd web && npm run check` passes
  - verified (2026-10-22 04:26+0200 by dell26/agent-1): cd web && npm run check -> lint clean, 146 files / 2967 tests passed, build ok; sh scripts/check.sh -> pass (first run hit unrelated flaky android SessionCacheTest capture_queue.db.lck, rerun passed)

## Notes
- ASSUMPTION (2026-10-22 03:48+0200 by dell26/agent-1): Today habits section is hidden when the user has no active habits, shows 'No habits due today' when habits exist but none is due; registered via provideTodaySection in today.routes.ts (order 10); messages.xlf left to a dedicated extract like other web tasks
