---
title: Command palette search and commands
status: in_progress
owner: dell26/agent-4
deps: [p0-web-shell,p0-search-core,p0-e2e-harness]
touches: [web/src/app/features/search]
prio: 1
lease_until: 2026-10-20T17:27:54Z
---
## Goal
Ctrl+K palette with global search and commands (SPEC.md §4.4).

## Acceptance criteria
- [x] Palette (via `PALETTE_PROVIDERS`) shows grouped search results as you type (debounced, out-of-order safe) and commands (new task, go to …)
  - verified (2026-10-20 19:10+0200 by dell26/agent-4): npx ng test --include src/app/features/search (palette with provideSearch shows Task/Note groups as you type, 200ms debounce, older query request cancelled; New task opens CAPTURE_DIALOG; Go to from shell) -> 35 passed; npm run e2e -- e2e/search/search.spec.ts (docker compose e2e stack) -> Task group result and Commands/Go to rows shown, passed
- [x] Enter opens the selected result's deep link
  - verified (2026-10-20 19:10+0200 by dell26/agent-4): npm run e2e -- e2e/search/search.spec.ts against the docker compose e2e stack: ArrowDown to the result, Enter -> URL /tasks/<created id>, palette closed, passed; unit: Enter navigates to deepLink (navigateByUrl spy) passed
- [x] Vitest component/service tests cover result grouping, stale response ignored, keyboard navigation; `cd web && npm run check` passes
  - verified (2026-10-20 19:10+0200 by dell26/agent-4): cd web && npm run check at 9868beb0 -> api:check, lint, e2e:typecheck ok, 30 files / 471 Vitest tests passed (grouping, stale response cancelled, arrow-key navigation), ng build ok (pre-existing initial budget warning)
- [x] Playwright `e2e/search/search.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/search/search.spec.ts`, see p0-e2e-harness): search 'passport' finds the task
  - verified (2026-10-20 19:10+0200 by dell26/agent-4): cd web && npm run e2e -- e2e/search/search.spec.ts (run-e2e.mjs, deploy/docker-compose.e2e.yml stack, chromium) at 9868beb0 -> 7 passed (5 phase-demos + 2 search specs incl. search passport finds the task)

## Notes
- ASSUMPTION (2026-10-20 18:55+0200 by dell26/agent-4): Palette search: queries shorter than 2 characters (trimmed) are not sent, 200 ms debounce, limit 20; results without a deepLink (module without resolver) are left out since there is nothing to open
- ASSUMPTION (2026-10-20 18:55+0200 by dell26/agent-4): Commands: only 'New task' (opens CAPTURE_DIALOG) is added; 'Go to <section>' already comes from the shell. Result groups use the singular item type labels (Task, Note) from shared/links itemTypeLabel
- ASSUMPTION (2026-10-20 18:55+0200 by dell26/agent-4): Initial bundle budget warning (500 kB) already existed on main (507.7 kB); this task adds ~0.9 kB and lazy-loads the search provider. Deep links like /tasks/{id} show Not found until web-task-detail adds the route
- ASSUMPTION (2026-10-20 18:57+0200 by dell26/agent-4): Review CR-1: no loading/failed state in the palette while the search runs or after it fails (shows the empty text); the PaletteProvider contract in shell/ has no such state, extending it is a shell change outside this task. Errors are silent so the commands stay usable
