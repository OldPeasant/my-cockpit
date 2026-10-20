---
title: Web GTD data layer and shared task components
status: in_progress
owner: dell26/agent-2
deps: [p0-web-shell,p0-web-sse,gtd-projects-api,p0-e2e-harness]
touches: [web/src/app/features/gtd/data,web/src/app/features/gtd/ui]
prio: 1
lease_until: 2026-10-20T19:29:10Z
releases: 0
extended: 
---
## Goal
Signal-based stores for tasks/projects/contexts and reusable task UI pieces.

## Acceptance criteria
- [ ] Stores load via the generated client, update optimistically with rollback on error, refetch on SSE `resync` and apply ChangeEvents
- [ ] Out-of-order safety: a slower older list request never overwrites a newer one (test reverses response order)
- [ ] Shared components: task row (checkbox, title, chips for context/energy/due/project), energy/creativity/estimate pickers, context chips
- [ ] Vitest component/service tests cover store ordering, optimistic update rollback, task row rendering; `cd web && npm run check` passes

## Notes
- release by R1963MACOS/agent-1: handed off by R1963MACOS/agent-1: You've hit your session limit · resets 12:50am (Europe/Zurich)
- ASSUMPTION (2026-10-20 21:12+0200 by dell26/agent-2): Did not run i18n:extract into web/src/locale/messages.xlf (shared file outside touches); new gtd.* i18n ids are in templates and get extracted by the next extract run.
