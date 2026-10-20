---
title: Web GTD data layer and shared task components
status: in_progress
owner: R1963MACOS/agent-1
deps: [p0-web-shell,p0-web-sse,gtd-projects-api,p0-e2e-harness]
touches: [web/src/app/features/gtd/data,web/src/app/features/gtd/ui]
prio: 1
lease_until: 2026-10-20T19:14:50Z
---
## Goal
Signal-based stores for tasks/projects/contexts and reusable task UI pieces.

## Acceptance criteria
- [ ] Stores load via the generated client, update optimistically with rollback on error, refetch on SSE `resync` and apply ChangeEvents
- [ ] Out-of-order safety: a slower older list request never overwrites a newer one (test reverses response order)
- [ ] Shared components: task row (checkbox, title, chips for context/energy/due/project), energy/creativity/estimate pickers, context chips
- [ ] Vitest component/service tests cover store ordering, optimistic update rollback, task row rendering; `cd web && npm run check` passes

## Notes
