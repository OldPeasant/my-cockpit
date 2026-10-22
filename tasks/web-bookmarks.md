---
title: Bookmarks UI
status: in_progress
owner: dell26/agent-3
deps: [p0-web-shell,bookmarks-api,p0-e2e-harness]
touches: [web/src/app/features/bookmarks]
prio: 3
lease_until: 2026-10-22T02:00:42Z
---
## Goal
Read-later queue and bookmarks (SPEC.md §8.4).

## Acceptance criteria
- [ ] Read-later queue, all by tag, add by URL, mark read, snapshot view
- [ ] Vitest component/service tests cover queue actions; `cd web && npm run check` passes

## Notes
