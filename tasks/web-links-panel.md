---
title: Linked items panel (shared)
status: in_progress
owner: dell26/agent-1
deps: [p0-web-shell,p0-items-links,p0-search-core]
touches: [web/src/app/shared/links]
prio: 1
lease_until: 2026-10-20T16:21:34Z
---
## Goal
Reusable 'Linked items' panel for every detail view (SPEC.md §4.3).

## Acceptance criteria
- [ ] Panel lists links of an item (icon, title, subtitle, deep link; unavailable items greyed out), remove link
- [ ] 'Add link' searches all types as you type (search API) and creates the link; 'create linked…' slot lets features add actions
- [ ] Vitest component/service tests cover listing, adding via search, removal; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-20 17:30+0200 by dell26/agent-1): Item icons are inline SVG glyphs chosen by ItemType (the app ships no Material Symbols font); the server's icon name is kept as data-icon on the tile
- ASSUMPTION (2026-10-20 17:30+0200 by dell26/agent-1): 'create linked…' slot = content projection of elements marked appLinkedCreate (shown in a 'Create linked' row); features call the panel's public reload() after creating the linked item. No live refresh: the server emits no LINK change events
- ASSUMPTION (2026-10-20 17:30+0200 by dell26/agent-1): SYNC links (inline note links) show 'In text' instead of a remove button, since DELETE rejects them with 409; unavailable items stay removable. messages.xlf not re-extracted: the panel is not used by any route yet, and extract only collects reachable components
