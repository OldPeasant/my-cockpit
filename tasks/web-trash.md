---
title: Trash view
status: in_progress
owner: dell26/agent-6
deps: [p0-web-shell,p0-trash-core,p0-e2e-harness]
touches: [web/src/app/features/trash]
prio: 2
lease_until: 2026-10-20T20:11:12Z
---
## Goal
List, restore, permanently delete (SPEC.md §4.2).

## Acceptance criteria
- [x] Trash lists deleted items of all types with deletion date and 'purged in N days'; restore and delete permanently (confirm)
  - verified (2026-10-20 21:55+0200 by dell26/agent-6): Vitest trash-page.spec.ts (npx ng test --watch=false --include='src/app/features/trash/**/*.spec.ts') renders all item types with deletion date + 'purged in N days/today', restore POST and confirm-dialog DELETE flows -> 85 passed; route /trash lazy-loads TrashPage (app.spec.ts)
- [x] Vitest component/service tests cover restore and delete flows; `cd web && npm run check` passes
  - verified (2026-10-20 21:56+0200 by dell26/agent-6): cd web && npm run check (api:check, lint, e2e:typecheck, test:ci, ng build) -> all stages passed (668 tests incl. restore/delete/cancel/Escape/404/failure flows); trash coverage 100% lines, 97.9% stmts

## Notes
- ASSUMPTION (2026-10-20 19:20+0200 by dell26/agent-6): Trash view: restore shows a snackbar 'Restored "title"'; restore/delete returning 404 just drops the row (handled elsewhere); 'purged in N days' rounds up, shows 'purged today' at 0 and turns red at <=3 days; paging via a 'Show more' button (50 per page).
- ASSUMPTION (2026-10-20 19:20+0200 by dell26/agent-6): Edited web/src/app/app.spec.ts (outside touches): it used /trash as its placeholder example; now /trash is in BUILT and the two checks look for app-trash-page h1.
