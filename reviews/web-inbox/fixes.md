# Fix Report

Date: 2026-10-20

Task: web-inbox - Inbox processing mode

Scope: current task; findings CR-1 to CR-5 of `reviews/web-inbox/review.md`

## Fixed findings

### CR-1 — Inline-attribute draft can silently overwrite a concurrent change, bypassing If-Match

Files changed:
- `web/src/app/features/gtd/inbox/inbox-page.ts`
- `web/src/app/features/gtd/inbox/inbox-page.spec.ts`

Problem:
The draft held a full snapshot of energy, creativity, estimate and contexts. When the item was refetched with other values while shown, `run()` sent every field where the old snapshot differed from the new server copy, using the new version as If-Match. The remote change was reverted without a 412. A second cause made this worse: the `linkedSignal` source `() => this.current()?.id` was tracked through `current()`, so every refetch reran the computation and reset the draft and the open prompt.

Fix:
- The draft is now `changes: linkedSignal<Partial<Clarification>>`, which holds only the fields the user set. The displayed `clarification` is a `computed` of `clarificationOf(current())` with `changes` laid over it.
- `run()` builds the PATCH with `clarificationPatch(task, { ...clarificationOf(task), ...changes })`, so it only contains touched fields that differ from the current server copy.
- Both linked signals (`changes`, `prompt`) now take `currentId = computed(() => this.current()?.id)` as their source. They reset only when the item changes, not when the same item is refetched. With the same fix, an open prompt no longer closes on a refetch.

Validation:
- New test "sends only the attributes changed inline, keeping a concurrent change": the user sets Creative, then a resync refetches the item with energy HIGH, a 30 min estimate, `@home` and version 2. Then N. The PATCH body is exactly `{ creativity: 'CREATIVE' }` with `If-Match: "2"`, and both the remote and the local values show. Pass.
- New test "keeps an open prompt when the item is refetched". Pass.
- `npx ng test --watch=false --include src/app/features/gtd/inbox/inbox-page.spec.ts` — Pass (48 tests at that point, 49 with the prompt test).

### CR-2 — The `matchesTask` tickler change has no unit test

Files changed:
- none (already covered)

Problem:
The review found no `matchesTask` tickler cases.

Fix:
`web/src/app/features/gtd/data/task-store.spec.ts` already has the test "hides a task whose tickler date is still to come, on every list (server rule)", added by the test-writer after the review. It covers:
- future dates (tomorrow, a date months ahead, with no list filter, on NEXT): no match;
- today: match;
- a past date: match;
- no tickler date: match.

All four boundaries the review asked for are there. No case was missing, so nothing was added.

Validation:
- `npm run check` (includes this spec) — Pass

### CR-3 — Enter does not confirm the To-project prompt when an existing project is selected

Files changed:
- `web/src/app/features/gtd/inbox/inbox-page.html`
- `web/src/app/features/gtd/inbox/inbox-page.spec.ts`

Problem:
A `<select>` does not trigger implicit form submission, but the Move button shows `Enter`.

Fix:
Added `(keydown.enter)="confirmPrompt($event)"` on the project `<select>`. `confirmPrompt` prevents the default action, so the document keydown handler ignores the event. It also checks `promptValid()`, so a "New project…" choice without a title still does nothing.

Validation:
- New test "Enter on the project choice moves the item": it dispatches a real `keydown` Enter on the focused select, not `requestSubmit()`. The test expects one move `{ list: 'NEXT', projectId: 'p1' }` and then Inbox zero. Pass.

### CR-4 — Inbox load error has no retry

Files changed:
- `web/src/app/features/gtd/inbox/inbox-page.html`
- `web/src/app/features/gtd/inbox/inbox-page.ts`
- `web/src/app/features/gtd/inbox/inbox-page.spec.ts`

Problem:
A failed initial load left an error state that could not be recovered without a reload.

Fix:
Following the Trash page pattern, the error now sits in a `role="alert"` block with a "Try again" button (i18n id `@@inbox.retry`). The button calls the new `retry()`, which runs `tasks.refresh()`.

Validation:
- New test "loads the inbox again with Try again after a load error": after the click, `listTasks` is called a second time with `list: INBOX`, the error text is gone and the item is listed. Pass.

### CR-5 — A failed move after "new project" leaves an orphan project, and a retry creates a duplicate

Files changed:
- `web/src/app/features/gtd/inbox/inbox-page.ts`
- `web/src/app/features/gtd/inbox/inbox-page.spec.ts`

Problem:
`projects.create` succeeded before the move. If the move then failed, retrying with "New project" created a second project.

Fix:
After a successful create, the page records the project id per item in `createdProjects: Map<itemId, projectId>`. It removes the entry once the move succeeds. When the To-project prompt opens again for that item, `choose()` preselects the recorded project (it is already in `ProjectStore` through `accept`), before falling back to the first open project or "New project". The map is keyed by item id, not by the current-item signal, so it also works when the failure arrives after the next item came up.

Validation:
- New test "offers the created project on a retry when the move after New project failed": the first move is rejected. The retry prompt shows `p2` selected with no title field. Confirming creates no second project (`createProject` called once) and moves with `projectId: 'p2'`. Pass.

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include src/app/features/gtd/inbox/inbox-page.spec.ts` — Pass

Full test suite:
- `cd web && npm run check` (api:check, `ng lint --max-warnings=0`, e2e typecheck, `ng test --watch=false`, `ng build`) — Pass: All files pass linting, 45 test files / 752 tests passed, the build succeeded. The build prints a warning that the initial bundle exceeds the 500 kB budget. This warning was there before and is not caused by these changes.

Lint/type-check/build:
- Included in `npm run check` above — Pass

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("all checks passed": server, web, android)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
