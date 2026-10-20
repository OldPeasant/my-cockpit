---
title: Inbox processing mode
status: todo
owner:
deps: [web-gtd-data]
touches: [web/src/app/features/gtd/inbox]
prio: 1
---
## Goal
Keyboard-driven one-item-at-a-time processing (SPEC.md §5.3).

## Acceptance criteria
- [ ] Inbox list + 'Process' mode showing one item with 'x items left'; actions with shortcuts: Do it (done), Next, Waiting, Someday, Schedule, Tickler, To project (existing/new), Trash; 'To reference' and 'To calendar' buttons present but disabled until later tasks enable them
- [ ] Inline setting of energy, creativity, contexts, estimate before the action
- [ ] Vitest component/service tests cover each action calls the right API, keyboard shortcuts; `cd web && npm run check` passes
- [ ] Playwright `e2e/gtd-inbox/inbox.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/gtd-inbox/inbox.spec.ts`, see p0-e2e-harness): process 3 items to an empty inbox

## Notes
