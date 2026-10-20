---
title: Command palette search and commands
status: todo
owner:
deps: [p0-web-shell,p0-search-core,p0-e2e-harness]
touches: [web/src/app/features/search]
prio: 1
---
## Goal
Ctrl+K palette with global search and commands (SPEC.md §4.4).

## Acceptance criteria
- [ ] Palette (via `PALETTE_PROVIDERS`) shows grouped search results as you type (debounced, out-of-order safe) and commands (new task, go to …)
- [ ] Enter opens the selected result's deep link
- [ ] Vitest component/service tests cover result grouping, stale response ignored, keyboard navigation; `cd web && npm run check` passes
- [ ] Playwright `e2e/search/search.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/search/search.spec.ts`, see p0-e2e-harness): search 'passport' finds the task

## Notes
