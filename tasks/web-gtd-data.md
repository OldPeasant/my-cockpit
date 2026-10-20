---
title: Web GTD data layer and shared task components
status: in_progress
owner: dell26/agent-2
deps: [p0-web-shell,p0-web-sse,gtd-projects-api,p0-e2e-harness]
touches: [web/src/app/features/gtd/data,web/src/app/features/gtd/ui]
prio: 1
lease_until: 2026-10-20T19:49:13Z
releases: 0
extended: 
---
## Goal
Signal-based stores for tasks/projects/contexts and reusable task UI pieces.

## Acceptance criteria
- [x] Stores load via the generated client, update optimistically with rollback on error, refetch on SSE `resync` and apply ChangeEvents
  - verified (2026-10-20 21:29+0200 by dell26/agent-2): npx ng test --include src/app/features/gtd/**/*.spec.ts (Vitest/jsdom, fake Api + fake ChangeEventsService) -> 83 passed: stores load via generated fns (listTasks/listProjects/listContexts paging), optimistic update/move/complete/remove/setNextStep with rollback on rejected request, resync$ refetches, DELETED event removes item, other events refetch
- [x] Out-of-order safety: a slower older list request never overwrites a newer one (test reverses response order)
  - verified (2026-10-20 21:29+0200 by dell26/agent-2): task-store.spec.ts 'never lets a slower older list answer overwrite a newer one' resolves the newer request first, then the older -> passes (also query-switch and error variants)
- [x] Shared components: task row (checkbox, title, chips for context/energy/due/project), energy/creativity/estimate pickers, context chips
  - verified (2026-10-20 21:29+0200 by dell26/agent-2): ui/task-row.spec.ts + pickers.spec.ts + labels.spec.ts rendered in TestBed -> pass: checkbox (complete/reopen aria-label, toggleDone emit), title (open), chips for contexts/energy/estimate/due (overdue/today)/project; energy/creativity/estimate pickers choose and clear; context chips toggle
- [ ] Vitest component/service tests cover store ordering, optimistic update rollback, task row rendering; `cd web && npm run check` passes

## Notes
- release by R1963MACOS/agent-1: handed off by R1963MACOS/agent-1: You've hit your session limit · resets 12:50am (Europe/Zurich)
- ASSUMPTION (2026-10-20 21:12+0200 by dell26/agent-2): Did not run i18n:extract into web/src/locale/messages.xlf (shared file outside touches); new gtd.* i18n ids are in templates and get extracted by the next extract run.
- ASSUMPTION (2026-10-20 21:12+0200 by dell26/agent-2): TaskRow's completion output is named toggleDone (not toggle) because angular-eslint forbids outputs named like native DOM events.
- ASSUMPTION (2026-10-20 21:12+0200 by dell26/agent-2): Stores apply ChangeEvents by removing an item on its own DELETED event and refetching the list on any other event of its types (events carry no contents, SPEC §2); ProjectStore also refetches on TASK events because next steps change 'stalled'.
