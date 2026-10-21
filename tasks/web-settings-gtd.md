---
title: Settings: contexts and areas
status: in_progress
owner: dell26/agent-3
deps: [p0-web-settings,gtd-projects-api]
touches: [web/src/app/features/settings/gtd]
prio: 2
lease_until: 2026-10-21T02:19:03Z
---
## Goal
Manage contexts and areas (SPEC.md §5.1).

## Acceptance criteria
- [x] Create/rename/reorder/delete contexts (with color) and areas; delete asks what to do with assigned tasks/projects
  - verified (2026-10-21 03:59+0200 by dell26/agent-3): cd web && npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts' -> 8 files, 170 passed; GtdSettings component specs drive the real /settings/gtd DOM against HttpTestingController (OpenAPI paths, If-Match): create/rename/color set+clear/reorder (buttons + drag)/delete for contexts and areas, delete dialog counts assigned tasks/projects and offers clear or move-to-another
- [x] Vitest component/service tests cover reorder and delete flows; `cd web && npm run check` passes
  - verified (2026-10-21 03:59+0200 by dell26/agent-3): cd web && npm run check -> exit 0: lint clean, Vitest 74 files / 1360 passed (settings: reorder success/failure/concurrent edit/bounds/busy, delete count/cancel/clear/move/move-failure/delete-failure/412), build OK

## Notes
- ASSUMPTION (2026-10-21 03:26+0200 by dell26/agent-3): Delete options: contexts -> 'remove from tasks' (server default) or 'replace with another context' (client patches each open+done task's contextIds, then deletes); areas -> 'leave projects without area' (server default) or 'move to another area' (client patches each project, any status). No server bulk-reassign endpoint exists, so reassignment is sequential client-side and stops before the delete on the first failure.
- ASSUMPTION (2026-10-21 03:26+0200 by dell26/agent-3): Settings section uses its own GtdSettingsService (plain API calls, local signals) instead of the gtd/data ContextStore, to stay inside the task's touches; other views catch up via SSE change events. Reorder: drag (CDK) plus up/down buttons for keyboard users. Context color: native color picker on a swatch, clearable. Route edit in settings.routes.ts replaces the gtd placeholder as the shell intends.
- ASSUMPTION (2026-10-21 03:44+0200 by dell26/agent-3): CR-1: 'move to another context/area' cannot reach tasks in the tickler/Trash or projects in the Trash (listTasks/listProjects hide them); the server delete strips the context/area from them. The dialog states this. A server-side reassign on DELETE would fix it; follow-up task created.
