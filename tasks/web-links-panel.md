---
title: Linked items panel (shared)
status: todo
owner:
deps: [p0-web-shell,p0-items-links,p0-search-core]
touches: [web/src/app/shared/links]
prio: 1
---
## Goal
Reusable 'Linked items' panel for every detail view (SPEC.md §4.3).

## Acceptance criteria
- [ ] Panel lists links of an item (icon, title, subtitle, deep link; unavailable items greyed out), remove link
- [ ] 'Add link' searches all types as you type (search API) and creates the link; 'create linked…' slot lets features add actions
- [ ] Vitest component/service tests cover listing, adding via search, removal; `cd web && npm run check` passes

## Notes
