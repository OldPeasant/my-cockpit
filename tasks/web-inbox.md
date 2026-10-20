---
title: Inbox processing mode
status: in_progress
owner: dell26/agent-6
deps: [web-gtd-data]
touches: [web/src/app/features/gtd/inbox]
prio: 1
lease_until: 2026-10-20T21:01:08Z
---
## Goal
Keyboard-driven one-item-at-a-time processing (SPEC.md §5.3).

## Acceptance criteria
- [x] Inbox list + 'Process' mode showing one item with 'x items left'; actions with shortcuts: Do it (done), Next, Waiting, Someday, Schedule, Tickler, To project (existing/new), Trash; 'To reference' and 'To calendar' buttons present but disabled until later tasks enable them
  - verified (2026-10-20 22:41+0200 by dell26/agent-6): npx ng test --watch=false --include src/app/features/gtd/inbox/inbox-page.spec.ts -> 54 passed (list, Process mode 'x items left', each action incl. To project existing/new, Trash; To reference/To calendar rendered disabled and their keys ignored); plus e2e/gtd-inbox/inbox.spec.ts drives Process mode
- [x] Inline setting of energy, creativity, contexts, estimate before the action
  - verified (2026-10-20 22:41+0200 by dell26/agent-6): inbox-page.spec.ts inline-attribute tests (energy, creativity, contexts, estimate PATCHed before the action, If-Match chain uses returned version; only user-changed fields sent) -> pass in npm --prefix web run check (757 tests)
- [x] Vitest component/service tests cover each action calls the right API, keyboard shortcuts; `cd web && npm run check` passes
  - verified (2026-10-20 22:42+0200 by dell26/agent-6): npm --prefix web run check (api:check, lint --max-warnings=0, e2e:typecheck, ng test 45 files/757 tests, ng build) -> exit 0; inbox-page/inbox-actions/task-store specs cover per-action API calls and keyboard shortcuts
- [x] Playwright `e2e/gtd-inbox/inbox.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/gtd-inbox/inbox.spec.ts`, see p0-e2e-harness): process 3 items to an empty inbox
  - verified (2026-10-20 22:43+0200 by dell26/agent-6): npm --prefix web run e2e -- e2e/gtd-inbox/inbox.spec.ts (podman-compose e2e stack: real server+postgres image, chromium) on final code 2597cf93 -> 6 passed incl. 'inbox processing > process 3 items to an empty inbox'

## Notes
- ASSUMPTION (2026-10-20 22:10+0200 by dell26/agent-6): Inbox shortcuts: P process (list), D do it, N next, P to project, W waiting, S schedule, T tickler, M someday (S was taken), R/C reference/calendar (disabled), X or Delete trash, Esc back/cancel; Q stays the global capture key.
- ASSUMPTION (2026-10-20 22:10+0200 by dell26/agent-6): 'To project' moves the item to NEXT with the chosen (or newly created) active project; 'Tickler' moves it to INBOX with a future ticklerDate (default tomorrow); 'Waiting' asks for an optional free-text person (no contact picker yet).
- ASSUMPTION (2026-10-20 22:10+0200 by dell26/agent-6): Inline attributes are kept as a local draft and saved with one PATCH just before the action (not on every click); Trash skips the PATCH.
- ASSUMPTION (2026-10-20 22:10+0200 by dell26/agent-6): Fixed matchesTask in gtd/data/task-store.ts (outside touches, one line): tasks with a future ticklerDate no longer match a list, as on the server; otherwise a ticklered item stayed visible in the inbox.
- ASSUMPTION (2026-10-20 22:10+0200 by dell26/agent-6): Clicking an item title in the inbox list navigates to /tasks/<id> (the deep link the search palette uses); the task detail route comes with a later task.
- ASSUMPTION (2026-10-20 22:42+0200 by dell26/agent-6): CR-6 (P3) accepted: a project remembered after a failed 'new project' move is preselected on retry without checking it is still open; if it was deleted/completed meanwhile the move fails server-side and the item stays in the inbox, user picks another project. Rare error-path-on-error-path, not worth another fix cycle.
