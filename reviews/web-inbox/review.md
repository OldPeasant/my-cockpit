# Code Review

Date: 2026-10-20

Task: web-inbox - Inbox processing mode

Scope: final re-review of the whole task. `git diff 5541034e..HEAD -- web`, committed code only. Focus of this pass: commit b3265e1e (5 new tests in `inbox-page.spec.ts` by the test-writer), plus a check that the production code has not changed since the previous review at 6651db8f.

Requirements:
- tasks/web-inbox.md acceptance criteria and recorded assumptions
- SPEC.md §4.2 (ETag/If-Match) and §5.3 (inbox processing mode)
- docs/ARCHITECTURE.md (web conventions, i18n, lint)
- AGENTS.md project notes
- Previous review (findings CR-1 to CR-6), reviews/web-inbox/fixes.md and reviews/web-inbox/tests.md

Git range:
- Branch: main
- HEAD: 2597cf93289b3fba95adeb2df42e673307833e70
- Base/start: 5541034e10cf00ce81cef8de654b0decfb56b47f
- Commits reviewed: 7 (06033d25, c9d47bd2, 3cbe3a82, 220e2929, 6651db8f, b3265e1e, 2597cf93). The last one changes only `reviews/`.
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Verification done in this pass

- `git diff --stat 6651db8f..HEAD -- web ':!*.spec.ts'` is empty. No production file has changed since the previous review. The only `web/` change is `web/src/app/features/gtd/inbox/inbox-page.spec.ts` (+100 lines, tests only).
- `cd web && npx ng test --watch=false --include src/app/features/gtd/inbox/inbox-page.spec.ts` -> 1 file, 54 tests passed.
- `npx eslint` and `npx prettier --check` on the changed spec -> clean.
- This review did not run the full `npm run check` or the Playwright e2e suite. The parent records that evidence with `tasks.py verify`.

## New tests (b3265e1e)

I checked each new test against the production code. All five test real behavior through the DOM and the fake API, and their assertions match the code:

- "does not move the item when creating its new project fails" (`inbox-page.spec.ts:503`): `createProject` rejects, so `run()` stops before `move` and never puts an id into `createdProjects` (`inbox-page.ts:274-279`). The test checks that no move was sent, the item stays current and is not counted, and a retry preselects the existing project `p1`, not a phantom id. This is a useful complement to the CR-5 test at `:473`.
- "saves the inline attributes before Do it" (`:828`): checks the PATCH with If-Match `"1"`, then the complete with the returned version `"2"`, and the call order. It covers the complete path, which the existing test at `:286` (move path) did not.
- "saves the inline attributes before a prompted action, with the prompt value" (`:847`): checks that the draft survives opening the prompt, that only the touched fields are sent, and that the move uses the new version and the prompt date.
- "shows inbox zero when another device empties the inbox while processing" (`:871`) and "closes an open prompt when its item leaves the inbox elsewhere" (`:885`): drive a resync through the `ChangeEventsService` fake. They check the empty state and progress, and that a stale prompt closes and the next key acts on the new current item (`t2`), not the removed one.

No weakened, skipped or deleted tests. A small overlap remains between `:286` and `:828`, but they cover different actions (move vs. complete), so I report no finding for it.

## Status of the earlier findings

Production code is unchanged, so the earlier results still hold:

- CR-1 (inline draft overwrote concurrent changes, bypassing If-Match): **resolved**. The draft holds only the touched fields (`inbox-page.ts:80-88`) and is merged over the current server copy at run time. Tests: `inbox-page.spec.ts:306`, `:326`.
- CR-2 (no unit test for the `matchesTask` tickler rule): **resolved**. `task-store.spec.ts:514`.
- CR-3 (Enter did not confirm on the project `<select>`): **resolved**. `(keydown.enter)="confirmPrompt($event)"` on the select. Test: `inbox-page.spec.ts:242`.
- CR-4 (load error had no retry): **resolved**. `role="alert"` block with "Try again", which calls `tasks.refresh()`. Test: `inbox-page.spec.ts:538`.
- CR-5 (orphan or duplicate project after a failed move): **resolved**. The created project id is remembered per item and forgotten after a successful move. Tests: `inbox-page.spec.ts:473`, and the new `:503`.
- CR-6: **open**, P3, below. The parent plans to accept it as a known limitation.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-6 | A remembered "created project" that is no longer open is preselected anyway**
    - File: `web/src/app/features/gtd/inbox/inbox-page.ts:167-169`
    - Category: Correctness
    - Problem: `choose()` preselects `createdProjects.get(task.id)` without checking that the id is still in `openProjects()`. Suppose the project created during a failed attempt is deleted or completed before the retry (e.g. from another device). Then `promptProject` holds an id that matches no `<option>`, so the browser shows the first option. `promptValid()` stays true, and confirming sends a move with a project id that is gone. The server rejects it, and the UI gives no hint why.
    - Why it matters: this is a rare error path that follows another error path, but what the select shows and what gets sent differ.
    - Suggested fix: use the remembered id only if `openProjects()` contains it. Otherwise drop it from the map and fall back to the first open project or `NEW_PROJECT`.

## Review conclusion

No material issues found. The production code is unchanged since the previous review. CR-1 to CR-5 remain resolved. The five new tests are correct and pass, and they add real value. CR-6 (P3) remains open as an optional low-priority fix. The parent still has to record evidence for every criterion, including `cd web && npm run check` and the Playwright run against the e2e stack. This review ran neither.
