# Code Review

Date: 2026-10-20

Task: web-gtd-lists - Waiting For, Someday, Scheduled, Logbook views

Scope: full task diff at the final revision, `git diff a09438f1..772e60c9` (task commits 23dde64f, ce2eebce, 1613d825, 772e60c9). This re-review focuses on commit 772e60c9, which changes only tests (`web/src/app/app.i18n.spec.ts`, `web/src/app/features/gtd/lists/list-pages.spec.ts`, `web/src/app/features/gtd/lists/list-views.spec.ts`) and adds `reviews/web-gtd-lists/tests.md`. The production code is the same as in the earlier review of 216b7319/1613d825: after the rebase, `git diff 216b7319 1613d825` touches only `tasks/*.md`.

Requirements:
- tasks/web-gtd-lists.md: acceptance criteria and the recorded assumptions (Scheduled route and nav entry, follow-up task shape, waiting age and Logbook scope)
- SPEC.md §5.1 to §5.3 and §9.1
- docs/ARCHITECTURE.md and AGENTS.md project notes (feature folders, i18n, lint, e2e conventions)
- web/src/app/features/README.md and web/e2e/README.md

Git range:
- Branch: main
- HEAD: 772e60c96273a51d69194006e61962c852030b63 (reviewed revision 772e60c9)
- Base/start: a09438f181a85aca64d27f3e410217e9c2ca0c25 (`git merge-base HEAD origin/main`)
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## What I checked

- Production code (unchanged since the earlier review): Waiting For sorts by `waitingSince`, oldest first, and "Follow up" creates a NEXT task in the same project. Someday "Activate" moves the task to NEXT with a rollback on failure. Scheduled groups by `scheduledDate`. Logbook uses `{completed: true}`, groups by the local day of `completedAt`, newest first, and the checkbox uncompletes. Every page has loading, error with retry, and empty states. The shared `ListStore` request sequence handles out-of-order list answers.
- `app.i18n.spec.ts`: moving the placeholder check from `/someday` to `/bookmarks` is correct. `/someday` now renders `SomedayPage`, not `ComingSoon`. `/bookmarks` still loads `ComingSoon` (`features/bookmarks/bookmarks.routes.ts`), and `@@nav.bookmarks` is the message id used by both the nav entry and the route title, so the test still exercises shell, nav and placeholder translation. `web-bookmarks` is not started and has no owner, so no running task will replace that placeholder right now. The updated comment says why the section was chosen.
- `list-pages.spec.ts` new tests, compared with the templates and `ListPage`:
  - The age accent tests the boundary `> LONG_WAIT_DAYS` (8 days accented, 7 not). It also checks that a future `waitingSince` shows "Today" (the `@else if` branch).
  - The double-submit tests hold the API promise. In the Waiting test, the second click is forced onto a re-enabled button, so the `run` busy guard is what is exercised, not just the `disabled` attribute. The Someday test clicks twice before change detection runs. Both check exactly one API call and the final state. The Waiting test also checks that another row's button stays enabled.
  - The loading tests hold `listTasks` and check "Loading…" before the list arrives and the rows after. The shared `describe.each` covers loading, opening a task and error-then-retry for Someday, Scheduled and Logbook, and checks two `listTasks` calls after the retry.
  - The tests also cover completing from the Someday and Scheduled checkboxes, the "Yesterday" and "No date" Scheduled headings, the full-date heading for older Logbook days, and checked boxes in the Logbook.
  - The tests assert visible behavior (text, ARIA labels, API calls through the fake `invoke`) rather than component internals. The `held` hook is reset in `beforeEach`. The test data is fictitious.
- `list-views.spec.ts`: the blank-person case matches `followUpTask`, which trims `waitingForText`.
- No test was deleted, skipped or weakened. The only change to an existing assertion is the i18n section move above.
- Ran `cd web && npx ng test --watch=false --include='src/app/features/gtd/lists/**/*.spec.ts' --include='src/app/app.i18n.spec.ts'` -> 3 files, 58 tests passed.
- Acceptance evidence: `tasks.py verify web-gtd-lists` still shows all three criteria open, so no criterion is claimed without evidence. The parent worker must still run criterion 3 (the Playwright spec against the e2e stack) and the full `npm run check` for criterion 2 before ticking them.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Logbook fetches the whole completed history on every visit and every task change event**
    - File: `web/src/app/features/gtd/lists/logbook-page.ts:25` (query `{ completed: true }`), with `web/src/app/features/gtd/data/task-store.ts:126-134` (fetches pages until the cursor runs out) and `web/src/app/features/gtd/data/list-store.ts:85-92` (any TASK event triggers a refetch)
    - Category: Quality
    - Problem: The Logbook loads every completed task the user has, 200 per sequential request. It loads the whole set again whenever a TASK change event arrives (completing, editing or creating any task) and on every SSE resync.
    - Why it matters: Completed tasks keep piling up and are never paged out. After a few years of use, each visit and each task change elsewhere costs many sequential requests and a full re-render. This is acceptable for v1 and the scope is recorded as an assumption, but the cost will keep growing.
    - Suggested fix: No change is needed for this task. If it becomes slow, load the most recent page (or a date window) first and add "load older" paging. That needs cursor support in `TaskStore`, which is outside this task's `touches`.

## Review conclusion

No material issues found.
