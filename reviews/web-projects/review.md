# Code Review

Date: 2026-10-21

Task: web-projects - Projects views

Scope: re-review of the final code after fixes. All task commits `c2621889..942dc560` (8 commits). Production code reviewed in full at HEAD 942dc560: `web/src/app/features/gtd/projects/*` (`project-detail-page.ts/.html`, `projects-page.ts/.html`, `project-task-store.ts`, `markdown.ts`, `area-store.ts`, `project-form.*`, `project-groups.ts`, `projects.routes.ts`) and `web/e2e/gtd-projects/projects.spec.ts`. The fix commit d99e65f2 was checked line by line against the earlier findings CR-1 to CR-5 in the previous review and `reviews/web-projects/fixes.md`. Test files were read as far as needed to check how the fixes are covered. Test files committed after 942dc560 by a parallel test-writer are not part of this review.

Requirements:
- tasks/web-projects.md acceptance criteria and recorded assumptions
- SPEC.md §5.2 (stalled rule, "Choose next step"), §5.3 (project list and detail), §9.1 (keyboard-first)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- Previous review (CR-1 to CR-5) and reviews/web-projects/fixes.md

Git range:
- Branch: main
- HEAD: 942dc560634d5e380ad3d276ca818c98e067df3f
- Base/start: c262188986aed7f56420943936d30d47069c8524
- Commits reviewed: 8

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Earlier findings, checked again

- CR-1 (duplicate task on retry): fixed. `addNext()` (`project-detail-page.ts:161`) and `addTask()` (`:185`) clear their input right after `tasks.create` resolves. `TaskStore.create` calls `accept(task)`, so the new task shows in `openTasks()` and in the prompt's choices. A failing `setNextStep` leaves the prompt open, and the empty input disables the submit button. `projects-states.spec.ts` covers both paths with a rejecting `setProjectNextStep`: `createTask` runs once, and choosing the created task afterwards sets it as the next step.
- CR-2 (failure-path tests): fixed. `projects-states.spec.ts` covers a refused reorder that restores the order, a refused `makeNext` that keeps the prompt usable, and a failed task load with retry. `project-task-store.spec.ts` covers the store rollback.
- CR-3 (prompt focus): fixed. `openPrompt()` (`:145`) focuses the first enabled `.choice`, or `#prompt-new`, in `afterNextRender`. Both entry points use it: completing the next step and the "Choose next step" button. The button sits in the `@else` branch and goes away when the prompt opens, so moving the focus is needed there as well. Both focus targets are tested.
- CR-4 (unused `TaskStore` alias): removed. `providers` now holds only `ProjectTaskStore`.
- CR-5 (empty text when everything is finished): fixed. `projects-page.html:87-96` shows "No open projects." when `hiddenCount() > 0`, and this is tested.
- Markdown link fix (`markdown.ts:93-108`): the opening `<a>` tags are swapped for private-use placeholders while emphasis runs. Any placeholder characters already in the input are stripped first. Emphasis therefore cannot change an href or `target="_blank"`. Escaping still happens before link parsing, and only `http(s):` and `mailto:` targets are allowed, so the XSS safety from the first review is unchanged. The `ANCHOR\d+ANCHOR` placeholder ends with a delimiter, so labels that start with digits still resolve correctly.
- Keyboard reorder focus (`key()`, `:202`): the code now refocuses `event.currentTarget` in `afterNextRender`. Rows are tracked by `task.id`, so the handle element is the same one after re-rendering. This is correct.

## Notes (not findings)

- `python tasks.py verify web-projects` still shows all 4 criteria as unrecorded at the time of this review. The parent worker must record evidence before `done`.
- `fixes.md` states that the fixer did not re-run the e2e spec. The fix commit changed `project-detail-page.ts/.html`, which criterion 4 exercises. Criterion 4 needs a fresh `npm run e2e -- e2e/gtd-projects/projects.spec.ts` run against the e2e stack at the final revision before it can be marked verified. I did not run the unit, e2e or `npm run check` suites for this review: I had only read-only access.
- Mouse drag (`drop()`) still runs in no test. It forwards to the same `move()` as the tested arrow-key path, so the risk is low.

## Review conclusion

No material issues found.
