---
title: Command palette search and commands
status: in_progress
owner: dell26/agent-4
deps: [p0-web-shell,p0-search-core,p0-e2e-harness]
touches: [web/src/app/features/search]
prio: 1
lease_until: 2026-10-20T17:17:47Z
---
## Goal
Ctrl+K palette with global search and commands (SPEC.md §4.4).

## Acceptance criteria
- [ ] Palette (via `PALETTE_PROVIDERS`) shows grouped search results as you type (debounced, out-of-order safe) and commands (new task, go to …)
- [ ] Enter opens the selected result's deep link
- [ ] Vitest component/service tests cover result grouping, stale response ignored, keyboard navigation; `cd web && npm run check` passes
- [ ] Playwright `e2e/search/search.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/search/search.spec.ts`, see p0-e2e-harness): search 'passport' finds the task

## Notes
- ASSUMPTION (2026-10-20 18:55+0200 by dell26/agent-4): Palette search: queries shorter than 2 characters (trimmed) are not sent, 200 ms debounce, limit 20; results without a deepLink (module without resolver) are left out since there is nothing to open
- ASSUMPTION (2026-10-20 18:55+0200 by dell26/agent-4): Commands: only 'New task' (opens CAPTURE_DIALOG) is added; 'Go to <section>' already comes from the shell. Result groups use the singular item type labels (Task, Note) from shared/links itemTypeLabel
- ASSUMPTION (2026-10-20 18:55+0200 by dell26/agent-4): Initial bundle budget warning (500 kB) already existed on main (507.7 kB); this task adds ~0.9 kB and lazy-loads the search provider. Deep links like /tasks/{id} show Not found until web-task-detail adds the route
