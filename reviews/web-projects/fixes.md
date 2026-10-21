# Fix Report

Date: 2026-10-21

Task: web-projects - Projects views

Scope: current task; findings CR-1 to CR-5 of `reviews/web-projects/review.md` and three failing test-writer tests (`markdown.spec.ts`, `projects-states.spec.ts`), all in `web/src/app/features/gtd/projects/`. Fix commit: 07b67a4f.

## Fixed findings

### CR-1 — Retrying a failed "add as next step" or "add task" creates a duplicate task

Files changed:
- `web/src/app/features/gtd/projects/project-detail-page.ts`
- `web/src/app/features/gtd/projects/projects-states.spec.ts`

Problem:
`addNext()` and `addTask()` cleared their input only when both `createTask` and `setProjectNextStep` succeeded. If the second call failed, the text stayed, and submitting again created a second task with the same title.

Fix:
Both methods clear their input as soon as the task is created. If `setNextStep` then fails, the prompt stays open and offers the new task as a choice (it is in the project's open tasks), so the user picks it instead of creating it again. Added two unit tests where `setProjectNextStep` rejects after `createTask` succeeded: one for the prompt (input cleared, submit disabled, no second create, choosing the created task sets it as next step), one for "Add task" on a stalled project.

Validation:
- `npx ng test --watch=false --include='src/app/features/gtd/projects/**/*.spec.ts'` — Pass (86 tests)

### CR-2 — No tests for the failure and rollback paths

Files changed:
- `web/src/app/features/gtd/projects/projects-states.spec.ts`

Problem:
The happy-path spec never made the API fail.

Fix:
The test-writer's `projects-states.spec.ts` (commit 81f3e70c) already covers the three named paths: "a refused reorder restores the previous order without an announcement", "the prompt stays open when making a task the next step fails" (button enabled again), and "shows an error when the tasks cannot be loaded, and retries". `project-task-store.spec.ts` covers the store rollback. I added the missing CR-1 partial-failure tests (see CR-1).

Validation:
- `npx ng test --watch=false --include='src/app/features/gtd/projects/**/*.spec.ts'` — Pass

### CR-3 — Focus is lost when the "Choose next step" prompt opens

Files changed:
- `web/src/app/features/gtd/projects/project-detail-page.ts`
- `web/src/app/features/gtd/projects/project-detail-page.html`
- `web/src/app/features/gtd/projects/projects-states.spec.ts`

Problem:
Completing the next step removes the focused checkbox's row. The prompt then appears but does not get focus.

Fix:
New `openPrompt()` sets the prompt and, in `afterNextRender`, focuses the first enabled choice, or the "new next step" input when there are no open tasks. Both entry points use it: completing the next step and the "Choose next step" button. Added two tests: focus goes to the first choice, or to `#prompt-new` when there are no tasks.

Validation:
- `npx ng test --watch=false --include='src/app/features/gtd/projects/**/*.spec.ts'` — Pass

### CR-4 — Unused `TaskStore` alias provider

Files changed:
- `web/src/app/features/gtd/projects/project-detail-page.ts`

Problem:
`{ provide: TaskStore, useExisting: ProjectTaskStore }` had no consumer. `TaskRow` and `ProjectForm` do not inject `TaskStore`. I confirmed this with grep in `features/gtd/ui` and `projects`.

Fix:
Removed the alias and the unused import.

Validation:
- `npm run check` (lint, typecheck, unit tests, build) — Pass

### CR-5 — "No projects yet" appears when every project is completed or dropped

Files changed:
- `web/src/app/features/gtd/projects/projects-page.html`
- `web/src/app/features/gtd/projects/projects-states.spec.ts`

Problem:
The `@empty` onboarding text showed even when finished projects existed behind the toggle.

Fix:
When `hiddenCount()` is greater than 0, the empty block shows "No open projects." (new i18n id `gtd.projects.noneOpen`). Otherwise it shows the onboarding text as before. Added a test.

Validation:
- `npx ng test --watch=false --include='src/app/features/gtd/projects/**/*.spec.ts'` — Pass

### TW-1 — markdown.spec.ts "keeps emphasis markers inside a link target literal"

Files changed:
- `web/src/app/features/gtd/projects/markdown.ts`
- `web/src/app/features/gtd/projects/markdown.spec.ts`

Problem:
The emphasis regexes ran over the generated `<a href="…">` tag, so `_draft_` in a URL became `<em>`. The same flaw could also break the `target="_blank"` attribute when an `_emphasis_` followed the link on the same line.

Fix:
While emphasis runs, a link's opening tag is replaced by a private-use placeholder (`n`; that character is stripped from the input first), and the tag is put back at the end. Hrefs and attributes stay literal, and link labels still get emphasis. The test was correct and is unchanged. I added a regression test: emphasis after a link, `~~` in a target, emphasis in a label.

Validation:
- `npx ng test --watch=false --include='src/app/features/gtd/projects/**/*.spec.ts'` — Pass

### TW-2 — projects-states.spec.ts: focus after keyboard reorder ("ArrowDown moves the task one place and keeps the focus on its handle", "pressing ArrowDown twice … moves the same task to the end")

Files changed:
- `web/src/app/features/gtd/projects/project-detail-page.ts`

Problem:
`key()` captured the handle list before re-rendering. It then focused `handles[to]` in a `setTimeout`, which can run before change detection. That index still pointed to another task's handle, so focus moved to the wrong task.

Fix:
The rows are tracked by task id, so the moved task's handle is the same element. `key()` refocuses `event.currentTarget` in `afterNextRender`, which runs after the new order is rendered. The tests were correct and are unchanged.

Validation:
- `npx ng test --watch=false --include='src/app/features/gtd/projects/**/*.spec.ts'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/gtd/projects/**/*.spec.ts'` — Pass, 7 files, 86 tests

Full test suite:
- `cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, ng build) — Pass: "All files pass linting.", 68 test files / 1226 tests passed, build OK. The build prints the existing initial-bundle budget warning, which is unrelated: projects is a lazy chunk.

Lint/type-check/build:
- Included in `npm run check` — Pass

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("all checks passed": server, web, android)

E2E (`npm run e2e -- e2e/gtd-projects/projects.spec.ts`): Not run by the fixer. The parent worker should re-run it on the final code.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
