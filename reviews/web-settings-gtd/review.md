# Code Review

Date: 2026-10-21

Task: web-settings-gtd - Settings: contexts and areas

Scope: second review of the final code. The whole task diff `git diff 3aa4bbbd..75a3fa90 -- web/src/app/features/settings`, with a focus on fix commit 75a3fa90 (TW-1, CR-1..3 from the first review). Committed changes only. The other `web/` paths in that range (`features/gtd/projects`, `e2e/gtd-projects`, `app.spec.ts`) belong to task web-projects, which was merged from main, and are not part of this review.

Requirements:
- tasks/web-settings-gtd.md acceptance criteria (1) and (2), plus its three recorded assumptions
- tasks/gtd-delete-reassign.md (the follow-up task that holds CR-1's server-side fix)
- SPEC.md §5.1 (Areas of focus, Contexts)
- api/openapi.yaml: contexts, areas, their order endpoints, listTasks/updateTask, listProjects/updateProject
- server gtd domain (`ContextService.order`/`delete`, `AreaService.delete`), checked to confirm the dialog hint
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- reviews/web-settings-gtd/fixes.md, previous review.md and tests.md

Git range:
- Branch: main
- HEAD: 260a6c0f (75a3fa90 is the last commit that changes code; 260a6c0f only adds tasks/gtd-delete-reassign.md)
- Base/start: 3aa4bbbd3a9abc422a576d0cf520bbcdebc6fb81
- Commits reviewed: 3 task commits (985a2b1f implementation, df3cf0ab tests and reports, 75a3fa90 fixes)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

How the earlier findings were resolved (checked in the current code):
- CR-2 / TW-1 (failed reorder reverts a concurrent rename or color change): fixed. `gtd-settings.ts:166-167` now uses `restoreOrder` (`:298-305`) on failure. It sorts the current items by their index in the old snapshot, so content and `version` saved meanwhile stay. A successful reorder still takes the server's list. The new spec checks that the next PATCH sends `If-Match: "2"`. Items missing from the snapshot sort last, which is safe because `busy` blocks create and delete while a reorder runs.
- CR-3 (failed reassignment blames the list and reloads it): fixed. `gtd-settings.service.ts:32-37, 183-189` wraps only the move step (listing plus PATCHes) in `ReassignError`. The DELETE stays outside the wrapper, so its errors are plain `ApiError`s. `gtd-settings.ts:281` reloads only for a plain 404/412. `message()` (`:331-344`) shows the per-kind "nothing was deleted, try again" text for a stale cause, and the cause's own message otherwise. Tests cover a 412 and a 404 during the move (no reload), a 500 during the move (cause message shown), and a 412 on the DELETE itself (reload still happens).
- CR-1 (move skips tickler/Trash items): mitigated as the review suggested. The dialog hint (`delete-dialog.ts:97-105`, linked with `aria-describedby`) only says what the server confirms. `ContextService.delete` clears the context on every task, Trash included, and `listTasks` hides tickler and Trash. Projects have no tickler, so the area hint mentions only the Trash. The root cause is recorded as a task assumption, and tasks/gtd-delete-reassign.md has concrete criteria to fix it on the server and remove the hint. This is an accepted, documented limitation, not an open finding.

Acceptance criteria:
- (1) Create, rename, reorder (drag plus up/down buttons) and delete for contexts (with a clearable color) and areas are implemented. Delete counts the assigned tasks or projects and asks whether to clear them or move them to another context or area. The move option is offered only when another target exists. Requests follow the OpenAPI contract: If-Match on every PATCH/DELETE, `color: null` to clear, paging with limit 200.
- (2) Vitest specs cover reorder (success, failure, a concurrent rename or color change, bounds, busy gate) and delete (count, cancel, clear, move, failure in the move step, failure in the DELETE, conflict handling) at the component, service and dialog level. The fix commit weakened no assertions. The changed assertions follow the deliberate CR-3 contract change, as fixes.md explains.

Other checks on the fix commit: no secrets or personal data. Test data is fictitious. No unsafe HTML. Errors stay silent at the HTTP layer and show next to their list, so they are not toasted twice. The new i18n ids are unique. The changes stay inside the task's `touches` (`web/src/app/features/settings/gtd`).

I also looked at one more case and found no defect: a rename that finishes while a reorder runs, after the server has already applied the order. The server bumps the versions of the contexts it moves (`ContextService.order` saves each one). A rename sent with an older version gets a 412, and the existing stale handling reloads the list. A rename that the server applied first shows up in the order response. So both orderings end consistent with the server, apart from a theoretical response-reordering race that I could not show to cause lasting harm, because the next edit then fails with a 412 and reloads.

Verification limits: this review was read-only. My attempt to run `npx ng test` and `npx ng lint` here was denied by the sandbox, so I did not run the suite myself. The parent worker must confirm the passing `npm run check` and settings specs reported in fixes.md (162 settings tests, 1260 web tests, lint clean) with its own run before it records criterion (2).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
