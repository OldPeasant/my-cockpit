---
title: Inbox processing mode
status: in_progress
owner: dell26/agent-6
deps: [web-gtd-data]
touches: [web/src/app/features/gtd/inbox]
prio: 1
lease_until: 2026-10-20T20:26:03Z
---
## Goal
Keyboard-driven one-item-at-a-time processing (SPEC.md §5.3).

## Acceptance criteria
- [ ] Inbox list + 'Process' mode showing one item with 'x items left'; actions with shortcuts: Do it (done), Next, Waiting, Someday, Schedule, Tickler, To project (existing/new), Trash; 'To reference' and 'To calendar' buttons present but disabled until later tasks enable them
- [ ] Inline setting of energy, creativity, contexts, estimate before the action
- [ ] Vitest component/service tests cover each action calls the right API, keyboard shortcuts; `cd web && npm run check` passes
- [ ] Playwright `e2e/gtd-inbox/inbox.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/gtd-inbox/inbox.spec.ts`, see p0-e2e-harness): process 3 items to an empty inbox

## Notes
- ASSUMPTION (2026-10-20 22:10+0200 by dell26/agent-6): Inbox shortcuts: P process (list), D do it, N next, P to project, W waiting, S schedule, T tickler, M someday (S was taken), R/C reference/calendar (disabled), X or Delete trash, Esc back/cancel; Q stays the global capture key.
- ASSUMPTION (2026-10-20 22:10+0200 by dell26/agent-6): 'To project' moves the item to NEXT with the chosen (or newly created) active project; 'Tickler' moves it to INBOX with a future ticklerDate (default tomorrow); 'Waiting' asks for an optional free-text person (no contact picker yet).
- ASSUMPTION (2026-10-20 22:10+0200 by dell26/agent-6): Inline attributes are kept as a local draft and saved with one PATCH just before the action (not on every click); Trash skips the PATCH.
- ASSUMPTION (2026-10-20 22:10+0200 by dell26/agent-6): Fixed matchesTask in gtd/data/task-store.ts (outside touches, one line): tasks with a future ticklerDate no longer match a list, as on the server; otherwise a ticklered item stayed visible in the inbox.
