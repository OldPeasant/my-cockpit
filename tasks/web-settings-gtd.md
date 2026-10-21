---
title: Settings: contexts and areas
status: in_progress
owner: dell26/agent-3
deps: [p0-web-settings,gtd-projects-api]
touches: [web/src/app/features/settings/gtd]
prio: 2
lease_until: 2026-10-21T01:38:10Z
---
## Goal
Manage contexts and areas (SPEC.md §5.1).

## Acceptance criteria
- [ ] Create/rename/reorder/delete contexts (with color) and areas; delete asks what to do with assigned tasks/projects
- [ ] Vitest component/service tests cover reorder and delete flows; `cd web && npm run check` passes

## Notes
