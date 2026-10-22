---
title: Habits UI
status: in_progress
owner: dell26/agent-2
deps: [p0-web-shell,habits-api,p0-e2e-harness]
touches: [web/src/app/features/habits]
prio: 3
lease_until: 2026-10-22T00:30:55Z
---
## Goal
Track habits (SPEC.md §8.5).

## Acceptance criteria
- [ ] Habit list with one-tap check, streaks, stats, create/edit
- [ ] Vitest component/service tests cover check toggle and streak display; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-22 02:15+0200 by dell26/agent-2): One-tap check on a habit with targetCount > 1 counts up by one per tap and wraps to 0 after the target; a once-a-day habit toggles 0/1. The check always targets the server's stats.today.
