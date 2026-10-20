---
title: Trash view
status: in_progress
owner: dell26/agent-6
deps: [p0-web-shell,p0-trash-core,p0-e2e-harness]
touches: [web/src/app/features/trash]
prio: 2
lease_until: 2026-10-20T17:37:10Z
---
## Goal
List, restore, permanently delete (SPEC.md §4.2).

## Acceptance criteria
- [ ] Trash lists deleted items of all types with deletion date and 'purged in N days'; restore and delete permanently (confirm)
- [ ] Vitest component/service tests cover restore and delete flows; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-20 19:20+0200 by dell26/agent-6): Trash view: restore shows a snackbar 'Restored "title"'; restore/delete returning 404 just drops the row (handled elsewhere); 'purged in N days' rounds up, shows 'purged today' at 0 and turns red at <=3 days; paging via a 'Show more' button (50 per page).
