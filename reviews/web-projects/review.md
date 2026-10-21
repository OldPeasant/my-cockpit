# Code Review

Date: 2026-10-21

Task: web-projects - Projects views

Scope: production code of the task commits e842bd2b, b959a8d2, a8663841, 9442b022 (`git diff d38346e1..9442b022`): `web/src/app/features/gtd/projects/*`, `web/e2e/gtd-projects/projects.spec.ts`, one line in `web/src/app/app.spec.ts`. Uncommitted changes and later test-writer commits are not part of this review.

Requirements:
- tasks/web-projects.md acceptance criteria and recorded assumptions
- SPEC.md §5.2 (stalled rule, "Choose next step" prompt), §5.3 (project list and detail)
- docs/ARCHITECTURE.md (web conventions, coverage), AGENTS.md project notes
- Server contract read for integration: `ProjectService.setNextStep` / `orderTasks`, `TaskService.complete` / `create`, `ProjectController` `/next-step` (optional If-Match)

Git range:
- Branch: main
- HEAD: 9442b022b12291d97d833b7bfe07bf917cb8fcc5
- Base/start: d38346e130716e1f25ef76e0b936238d4fadc475
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 2 P2, 3 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Retrying a failed "add as next step" or "add task" creates a duplicate task**
    - File: `web/src/app/features/gtd/projects/project-detail-page.ts:138` (also `:161`)
    - Category: Correctness
    - Problem: `addNext()` and `addTask()` each make two requests inside one `run()`: `tasks.create(...)` and then `projects.setNextStep(...)`. If the create succeeds and `setNextStep` fails (412 because the project changed in another tab or device, 400, or a network error), `run` returns `false`. The input keeps its text (`nextTask`/`newTask` are cleared only when `ok`), and the prompt stays open. The new task is already in the project and appears in the list. When the user does the obvious thing and clicks "Add as next step" again, a second task with the same title is created.
    - Why it matters: A transient error during the prompt (the main next-step flow of SPEC.md §5.2) leaves duplicate tasks in the project, and the user has to clean them up by hand.
    - Suggested fix: Remember the task once it is created. On failure, either clear the input and leave the created task as an option in the prompt (choosing it calls `makeNext`), or retry only `setNextStep` for the remembered task on the next submit. Add a unit test where `setProjectNextStep` rejects after `createTask` succeeded.

- [ ] **CR-2 | No tests for the failure and rollback paths of the new views**
    - File: `web/src/app/features/gtd/projects/projects-pages.spec.ts:126` (mock API never fails); `web/src/app/features/gtd/projects/project-task-store.ts:36`
    - Category: Tests
    - Problem: The spec's mock `Api` always succeeds. Nothing tests that a refused `orderProjectTasks` brings back the previous order (`ProjectTaskStore.reorder` clears the pending order in `finally`), that a failed `setNextStep` or `complete` leaves the prompt and rows usable, or that a failed task list load shows "The tasks could not be loaded." with a working "Try again" (`project-detail-page.html:176`). The pending-order overlay and its rollback are the only new store logic in this task.
    - Why it matters: These are exactly the paths a happy-path suite misses (see the review checklist on loading and error state). A regression in the rollback would leave a reordered list on screen that the server never saved.
    - Suggested fix: Let the mock API reject for selected calls. Add tests for: reorder refused -> original order shown; prompt `makeNext` refused -> prompt stays open, button enabled again; `listTasks` fails -> error and retry. This does not block the acceptance criteria. The test-writer working in parallel may already cover it.

## P3 Low

- [ ] **CR-3 | Focus is lost when the "Choose next step" prompt opens**
    - File: `web/src/app/features/gtd/projects/project-detail-page.ts:111` (template `project-detail-page.html:44`)
    - Category: Quality
    - Problem: Completing the next step removes its row right away (optimistic update), including the checkbox that has focus. The prompt then renders as `role="dialog"`, but focus is not moved into it and nothing is announced (`announcement` is not set). Keyboard and screen-reader users end up on `<body>` and get no notice that a dialog appeared.
    - Why it matters: SPEC.md §9.1 asks for a keyboard-first UI, and this is the central next-step interaction.
    - Suggested fix: After the prompt is set, focus its first choice or the "new next step" input (for example with `afterNextRender` or a `viewChild`), or set `announcement`.

- [ ] **CR-4 | Unused `TaskStore` alias provider**
    - File: `web/src/app/features/gtd/projects/project-detail-page.ts:32`
    - Category: Quality
    - Problem: `{ provide: TaskStore, useExisting: ProjectTaskStore }` is registered, but nothing in the component's subtree injects `TaskStore`. `TaskRow` uses only inputs and outputs, and `ProjectForm` injects `ProjectStore`.
    - Why it matters: It suggests some child relies on the alias. Someone reading or changing the page later may wrongly assume a hidden dependency exists.
    - Suggested fix: Remove the alias, or add a comment naming the consumer if one is planned.

- [ ] **CR-5 | "No projects yet" appears when every project is completed or dropped**
    - File: `web/src/app/features/gtd/projects/projects-page.html:87`
    - Category: Quality
    - Problem: The `@empty` block runs on `groups()`, which is built from the visible (unfinished) projects only. A user whose projects are all COMPLETED or DROPPED sees "No projects yet. Anything that takes more than one step is a project…" right above the "Show completed and dropped (n)" toggle.
    - Why it matters: The message contradicts the toggle and the user's data.
    - Suggested fix: When `hiddenCount() > 0`, show a different empty text (for example "No open projects."), or show the onboarding text only when `projects.items()` is empty.

## Notes (not findings)

- I checked these and found them correct: next-step marker and stalled rendering against the server's `stalled` rule; `needsNextStep` handling (it opens the prompt only for the next step of an ACTIVE project); `/next-step` without If-Match in the e2e setup, which the controller documents as optional; `orderTasks` accepting a subset of ids, so tickler-hidden tasks are not a problem; task creation does not bump the project version, so `setNextStep` after `create` does not get a 412 for that reason; HTML escaping and the http(s)/mailto link whitelist in `markdown.ts`, with Angular's sanitizer on top; route reuse resetting state per project id.
- `python tasks.py verify web-projects` still showed all 4 criteria as unrecorded when this review was written. The parent worker has to record evidence before `done`. The unit-test, e2e and build results quoted in the request are the parent's claims. I did not re-run them for this review.
- Mouse drag (`drop()` / `cdkDropListDropped`) runs in no test. Only the arrow-key path is covered. `drop()` just forwards to the same `move()`, so the risk is low.

## Review conclusion

Material issues found; fix before completing the task. No acceptance criterion is unmet. CR-1 (duplicate tasks after a retried partial failure) should be fixed. CR-2 should be covered by tests, or deferred with a stated reason.
