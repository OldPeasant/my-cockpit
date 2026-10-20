---
title: Trash view
status: todo
owner:
deps: [p0-web-shell,p0-trash-core,p0-e2e-harness]
touches: [web/src/app/features/trash]
prio: 2
---
## Goal
List, restore, permanently delete (SPEC.md §4.2).

## Acceptance criteria
- [ ] Trash lists deleted items of all types with deletion date and 'purged in N days'; restore and delete permanently (confirm)
- [ ] Vitest component/service tests cover restore and delete flows; `cd web && npm run check` passes

## Notes
