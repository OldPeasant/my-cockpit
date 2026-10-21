---
title: Settings: contexts and areas
status: in_progress
owner: dell26/agent-3
deps: [p0-web-settings,gtd-projects-api]
touches: [web/src/app/features/settings/gtd]
prio: 2
lease_until: 2026-10-21T02:08:57Z
---
## Goal
Manage contexts and areas (SPEC.md §5.1).

## Acceptance criteria
- [ ] Create/rename/reorder/delete contexts (with color) and areas; delete asks what to do with assigned tasks/projects
- [ ] Vitest component/service tests cover reorder and delete flows; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-21 03:26+0200 by dell26/agent-3): Delete options: contexts -> 'remove from tasks' (server default) or 'replace with another context' (client patches each open+done task's contextIds, then deletes); areas -> 'leave projects without area' (server default) or 'move to another area' (client patches each project, any status). No server bulk-reassign endpoint exists, so reassignment is sequential client-side and stops before the delete on the first failure.
- ASSUMPTION (2026-10-21 03:26+0200 by dell26/agent-3): Settings section uses its own GtdSettingsService (plain API calls, local signals) instead of the gtd/data ContextStore, to stay inside the task's touches; other views catch up via SSE change events. Reorder: drag (CDK) plus up/down buttons for keyboard users. Context color: native color picker on a swatch, clearable. Route edit in settings.routes.ts replaces the gtd placeholder as the shell intends.
- ASSUMPTION (2026-10-21 03:44+0200 by dell26/agent-3): CR-1: 'move to another context/area' cannot reach tasks in the tickler/Trash or projects in the Trash (listTasks/listProjects hide them); the server delete strips the context/area from them. The dialog states this. A server-side reassign on DELETE would fix it; follow-up task created.
