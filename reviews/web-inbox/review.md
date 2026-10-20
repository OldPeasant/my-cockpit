# Code Review

Date: 2026-10-20

Task: web-inbox - Inbox processing mode

Scope: re-review after fixes. Task commits from base 0792ca45 to HEAD b3317b11 (`git diff 0792ca45..b3317b11`), committed code only. Uncommitted test changes from a parallel test-writer are ignored.

Requirements:
- tasks/web-inbox.md acceptance criteria and recorded assumptions
- SPEC.md §4.2 (ETag/If-Match) and §5.3 (inbox processing mode)
- docs/ARCHITECTURE.md (web conventions, i18n, lint)
- AGENTS.md project notes
- Previous review (reviews/web-inbox/review.md, findings CR-1 to CR-5) and fix report (reviews/web-inbox/fixes.md)

Git range:
- Branch: main
- HEAD: b3317b1144ffffefff7d2490aa99d24b58c364c0
- Base/start: 0792ca45574b8ac3252236886e108c9c959a8165
- Commits reviewed: 5 (e0d1233, a1e8c8f, 24d2864, 063283b, b3317b1)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Notes on verification: this review's sandbox did not allow shell commands, so no `git`, lint, Vitest, Playwright or `tasks.py verify` was run. The range and commit list come from the parent's scope and the recent log. This review read the current committed state of these files in full: `inbox-page.ts`, `inbox-page.html`, `inbox-actions.ts`, `inbox-page.spec.ts`, `e2e/gtd-inbox/inbox.spec.ts`, `gtd/data/task-store.ts`, `gtd/data/list-store.ts` and `gtd/data/task-store.spec.ts`. The criteria are not yet ticked in tasks/web-inbox.md. The parent must record evidence for every criterion with `tasks.py verify`, including the real Playwright run for criterion #4 against the e2e stack. That is expected at this stage and is not reported as a finding.

## Status of the earlier findings

- CR-1 (inline draft overwrote concurrent changes, bypassing If-Match): **resolved**. The draft is now `changes: linkedSignal<Partial<Clarification>>`, which holds only the fields the user touched (`inbox-page.ts:80-88`). `run()` builds the PATCH from `{ ...clarificationOf(task), ...changes }` against the current server copy (`inbox-page.ts:231`), so fields the user did not touch are never sent. Both linked signals now key on `currentId` (`inbox-page.ts:68`), so a refetch of the same item no longer resets the draft or the open prompt. If the user clears a field, the key stays in `changes` as `undefined` and is correctly sent as `null`. Tests: "sends only the attributes changed inline, keeping a concurrent change" and "keeps an open prompt when the item is refetched" (`inbox-page.spec.ts:306-335`).
- CR-2 (no unit test for the `matchesTask` tickler rule): **resolved**. `task-store.spec.ts:514-529` covers a future date (several lists and no list), today, a past date and no tickler date.
- CR-3 (Enter did not confirm on the project `<select>`): **resolved**. `(keydown.enter)="confirmPrompt($event)"` is on the select (`inbox-page.html:142`). `confirmPrompt` calls `preventDefault`, so the document handler ignores the key, and it checks `promptValid()`. The test sends a real keydown to the focused select (`inbox-page.spec.ts:242-254`).
- CR-4 (load error had no retry): **resolved**. The error is now in a `role="alert"` block with a "Try again" button (`@@inbox.retry`) that calls `tasks.refresh()` (`inbox-page.html:27-33`, `inbox-page.ts:145-147`). `ListStore.refresh` reruns the stored query and clears the error on success. Test: `inbox-page.spec.ts:512-525`.
- CR-5 (orphan or duplicate project after a failed move): **resolved**. The created project id is remembered per item, preselected when the prompt opens again, and forgotten once the move succeeds (`inbox-page.ts:93`, `:168`, `:274-279`). Test: `inbox-page.spec.ts:473-501`.

Also re-checked on the final code: every action calls the intended endpoint, and the move or complete uses the version the PATCH returned. To reference and To calendar are disabled and their keys do nothing. "x items left" uses an ICU plural. All strings are localized with ids. The keyboard handler ignores events from overlays, typing targets, modifiers, `defaultPrevented` and key repeats. `busy` is always cleared in `finally`. The e2e spec processes 3 items (inline attributes, then N, M, X) to Inbox zero and checks the server state through the API.

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
    - Problem: `choose()` preselects `createdProjects.get(task.id)` without checking that the id is still in `openProjects()`. Suppose the project created during a failed attempt is deleted or completed before the retry (e.g. from another device). Then `promptProject` holds an id that matches no `<option>`, so the browser shows the first option. `promptValid()` is still true, because the value is not `NEW_PROJECT`. Confirming sends a move with a project id that is gone, which fails on the server, and the UI gives no hint why.
    - Why it matters: this is a rare error-path-after-error-path case, but what the select shows and what gets sent differ.
    - Suggested fix: use the remembered id only if `openProjects()` contains it. Otherwise drop it from the map and fall back to the first open project or `NEW_PROJECT`.

## Review conclusion

No material issues found. All earlier findings (CR-1 to CR-5) are resolved in the code. The only new finding, CR-6, is an optional low-priority fix. The parent still has to record evidence for every criterion, including criterion #3 (`cd web && npm run check`) and criterion #4 (the Playwright run against the e2e stack). This review did not run either one.
