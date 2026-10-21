# Fix Report

Date: 2026-10-21

Task: web-settings-gtd - Settings: contexts and areas

Scope: current task. Findings TW-1 (reviews/web-settings-gtd/tests.md), CR-1, CR-2 and CR-3 (reviews/web-settings-gtd/review.md). Base 3aa4bbbd3a9abc422a576d0cf520bbcdebc6fb81. Changes stay inside `web/src/app/features/settings/gtd/`. They are uncommitted for the parent worker.

## Fixed findings

### TW-1 / CR-2 - A failed reorder overwrites a rename or color change saved meanwhile

Files changed:
- `web/src/app/features/settings/gtd/gtd-settings.ts`
- `web/src/app/features/settings/gtd/gtd-settings.spec.ts` (new test only)

Problem:
`reorder` put back the whole snapshot from before the move when saving failed. A rename or color change that was saved while the reorder ran was reverted in the UI, together with its new version, so the next edit got a 412.

Fix:
When the save fails, the new `restoreOrder` sorts the current items by their index in the old snapshot. Items keep their current content and version. A successful reorder still takes the server's list. The TW-1 test `reorder edge cases > keeps a rename saved while a failing reorder was pending` was left unchanged and now passes. Added `keeps a color change and its new version when a reorder fails meanwhile`, which also checks that the next PATCH sends `If-Match: "2"`.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts'` - Pass (8 files, 162 tests)

### CR-3 - A failed reassignment blames the list and reloads the wrong data

Files changed:
- `web/src/app/features/settings/gtd/gtd-settings.service.ts`
- `web/src/app/features/settings/gtd/gtd-settings.ts`
- `web/src/app/features/settings/gtd/gtd-settings.service.spec.ts`
- `web/src/app/features/settings/gtd/gtd-settings.spec.ts`

Problem:
A 404 or 412 from a task or project PATCH during delete-with-move showed "It was changed elsewhere in the meantime; the list now shows the current state." It also reloaded the context or area list, which had not changed.

Fix:
The service now exports `ReassignError` (with `cause`). `deleteContext` and `deleteArea` wrap the move step (listing plus PATCHes) in it. Errors from the DELETE itself stay plain `ApiError`s. The component reloads the list only for a plain 404 or 412 (failed DELETE, rename, color change or reorder). For a `ReassignError` whose cause is a 404 or 412, it shows "Some tasks were changed elsewhere in the meantime, so nothing was deleted. Please try again." (areas: "Some projects ..."). It uses the new i18n ids `gtdSettings.reassignStale.context` and `gtdSettings.reassignStale.area`, and does not reload. Any other cause shows that cause's own message.

Changed tests: two service tests expected the raw `ApiError` from a failed move. They now expect a `ReassignError` with that `ApiError` as `cause`, because the contract changed on purpose. The component test `keeps the context, does not delete it and reloads when reassigning a task fails` expected the old message and a contexts reload, which is the behavior CR-3 removes. It is now `... and blames the tasks when reassigning one fails`: no reload, the new message, and the stale-list text does not appear. No other assertion was weakened.

Added tests: service `rejects with the plain API error when the delete itself fails after the move`. Component: `blames the projects when moving one fails because it was deleted elsewhere` (404, no areas reload), `shows the error of the move step itself when it is not a conflict` (500 shows "Server down", no reload), and `reloads the list when the delete itself finds the context changed elsewhere` (DELETE 412 still reloads).

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts'` - Pass

### CR-1 (mitigation only) - "Move" skips tasks in a tickler/Trash and trashed projects

Files changed:
- `web/src/app/features/settings/gtd/delete-dialog.ts`
- `web/src/app/features/settings/gtd/delete-dialog.spec.ts`

Problem:
The move step only sees what `GET /tasks` and `GET /projects` return. The server delete then clears the context or area on every row, including the hidden ones.

Server code check (`server/src/main/kotlin/ch/sonensei/cockpit/gtd`):
- `domain/TaskService.kt` `specification`: always `deletedAt is null` and `ticklerDate is null or <= today`, for open and completed tasks alike. Tasks in the Trash or in a future tickler are excluded.
- `domain/ProjectService.kt` `specification`: always `deletedAt is null`. Trashed projects are excluded. There is no tickler filter for projects.
- `domain/ContextService.delete` uses `tasks.findAllWithContext(id)` and `domain/AreaService.delete` uses `projects.findByAreaId(id)`. Both clear the reference on all rows, trashed ones included.

Fix:
When "move" is chosen, the dialog shows a hint under the target select, linked with `aria-describedby`. For contexts it reads "Tasks in the tickler or the Trash just lose it." (`gtdSettings.delete.contextMoveHint`). For areas it reads "Projects in the Trash are left without an area." (`gtdSettings.delete.areaMoveHint`). The hint states only what the server code confirms. Two dialog tests check that the hint is shown only for "move", with the right text for each kind.

The root cause (server-side reassignment in one transaction) is not fixed here: it is a server/API change outside this task's `touches`. The parent worker should record it with `tasks.py assume` and/or add a follow-up task, as the review suggests.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts'` - Pass

## Not fixed

None. CR-1 got only the requested UI mitigation. The server-side fix is out of scope (see above).

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts'` - Pass, 8 files, 162 tests (was 154 passed + 1 failed)

Full test suite:
- `cd web && npm run check` - Pass (lint clean, 67 files / 1260 tests passed, build and the following step completed)

Lint/type-check/build:
- `cd web && npm run lint` - Pass ("All files pass linting.")
- Type check and build are part of `npm run check` - Pass

`.taskcheck`:
- `sh scripts/check.sh` - Pass ("== all checks passed", including server and android)

Remaining unresolved findings:
0 in scope (CR-1's server-side root cause needs a follow-up task)

## Final status

All validated findings fixed and validation passed.
