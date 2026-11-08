# Test Report

Date: 2026-11-08

Task: web-task-actions - Web task detail: complete, delete, undo, Markdown notes

Scope: `git diff 4102a26a80c47af215943702c497901f6ee773a7..b28baf82e736921d2cc9dd234addb6e1d0583291 -- web/` (task commits eba1ef248, b28baf82e on branch main; base and commits supplied by the parent worker). Tests measured on HEAD b28baf82e plus the uncommitted test files listed below.

Requirements:
- `tasks/web-task-actions.md` acceptance criteria and the three recorded assumptions (undo in lists covers completing only; Complete saves valid changes first and stays; Delete confirms only when dirty, then navigates back via BackNavigation; rendered notes reuse projects' renderMarkdown, Edit/click/Enter switch to the textarea, links still open; checklist rename via pencil button, Enter/blur saves, Escape cancels)
- Parent worker's summary of the change
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`

## Test files changed

- `web/src/app/features/gtd/ui/task-undo.spec.ts` (new)
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts`
- `web/src/app/features/gtd/task-detail/task-detail-page.spec.ts`
- `web/src/app/features/gtd/task-detail/task-detail.service.spec.ts`
- `web/src/app/features/gtd/task-detail/task-checklist.spec.ts`
- `web/src/app/features/gtd/lists/list-pages.spec.ts`
- `web/src/app/features/gtd/next/next-view.spec.ts`
- `web/src/app/features/gtd/today/today-view.spec.ts`
- `web/src/app/features/gtd/projects/projects-pages.spec.ts`
- `web/src/locale/messages-de.spec.ts`

## Tests added or updated

Count: 77 new tests (task-undo 8, task-detail 38 in "complete, reopen and delete" (25) and "rendered notes" (13), task-detail-page 1, task-detail.service 4, task-checklist 11, list-pages 5 incl. 3 parameterized, next-view 2, today-view 2, projects-pages 5, messages-de 1). No existing test was changed or weakened; `projects-pages.spec.ts` only gained an `uncompleteTask` answer and a `failComplete` switch in its shared API fake.

## Requirement coverage

Focused command for each row: `cd web && npx ng test --watch=false --include='{spec path}'`; all rows were also re-run in `npx ng test --watch=false --include='src/app/features/gtd/**/*.spec.ts'` and `npm run test:ci`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Task detail has Complete/Reopen | task-detail "completes the task silently with If-Match, stays and offers Undo", "reopens a done task silently, without an undo snackbar", "saves unsaved valid changes first, then completes the saved version", "does not complete when the changes are invalid", "does not complete when saving the changes first fails", "shows the running action and starts nothing else meanwhile", "shows \"Reopening…\" and \"Deleting…\" while those run", "on a 412 while completing ...", "shows the error when completing fails and lets the user retry", "shows the error when reopening fails", "does not reload on a change event while completing", "drops a background reload that started before completing", "does not show the completion of the previous task in the new one"; task-detail.service "completes a task silently ...", "reopens a task silently by default" | focused task-detail.spec.ts, task-detail.service.spec.ts | Pass |
| AC1 Delete (to Trash) and navigate back via the shared back helper | task-detail "deletes a task without unsaved changes at once, offers Undo and closes", "asks before deleting a task with unsaved changes and keeps them when cancelled", "deletes the saved version, without saving, when the user confirms dropping changes", "does not show \"moved to the Trash\" for its own deletion event", "still shows \"moved to the Trash\" when someone else deletes it during a completion", "on a 412 while deleting ...", "shows the error when deleting fails and stays", "does not close the new task when the deletion of the previous one answers late"; task-detail-page "goes back through the shared back navigation after deleting the task, offering Undo"; task-detail.service "moves a task to the Trash silently with If-Match" | focused task-detail.spec.ts, task-detail-page.spec.ts, task-detail.service.spec.ts | Pass |
| AC2 Undo snackbar (service: message, Undo action, duration, reopen/restore, failure) | task-undo.spec.ts: 7 tests with a MatSnackBar double + 1 with the real MatSnackBar clicking "Undo" | focused ui/task-undo.spec.ts | Pass |
| AC2 Undo in task detail | task-detail "Undo reopens the completed version with the HTTP layer showing errors, and shows it", "Undo reopens the newest version when the task was saved after completing", "Undo does not replace unsaved edits made after completing", "Undo after switching to another task reopens the old one without touching the new one"; task-detail.service "reopens a task with errors shown by the HTTP layer when asked to toast"; delete-undo offered in the delete tests above (restore itself: task-undo "offers to restore a deleted task from the Trash") | focused task-detail.spec.ts, task-detail.service.spec.ts | Pass |
| AC2 Undo in lists: Next | next-view "undo after completing": "offers Undo, which reopens the completed version and shows the task again", "offers no Undo when completing fails" | focused next/next-view.spec.ts | Pass |
| AC2 Undo in lists: Today | today-view "undo after completing": "offers Undo, which reopens the task into its section", "offers no Undo when completing fails" | focused today/today-view.spec.ts | Pass |
| AC2 Undo in lists: project detail (incl. closing "Choose next step") | projects-pages "undo after completing": "Undo reopens the completed task, shows it again and closes \"Choose next step\"", "keeps \"Choose next step\" open when the snackbar goes away without Undo", "does not close a prompt that belongs to another completed task", "offers Undo also for a task that was not the next step, without a prompt", "offers no Undo when completing fails" | focused projects/projects-pages.spec.ts | Pass |
| AC2 Undo in Waiting/Someday/Scheduled (ListPage) | list-pages "undo after completing": "%s offers Undo, which reopens the task" for Waiting For, Someday, Scheduled; "offers no Undo when completing fails"; "offers no Undo when reopening from the Logbook" | focused lists/list-pages.spec.ts | Pass |
| AC3 Checklist items renamed inline | task-checklist "rename": focused input with text and aria-label, Enter saves trimmed text with item version, blur saves, Escape cancels and does not bubble, empty/blank/unchanged text does nothing, Enter+blur saves once, optimistic text and rollback on error, 412 reload with conflict message, no rename while another change saves, rename after toggle uses the saved version, task switch drops an open rename | focused task-detail/task-checklist.spec.ts | Pass |
| AC4 Notes rendered as Markdown when not editing; click/Edit switch to the textarea | task-detail "rendered notes": blank notes show the textarea, rendered Markdown with Edit button, HTML is escaped (no img/script elements), Edit focuses the textarea, click and Enter on the view switch, a link click keeps the view, a text selection keeps the view, typing stays in source mode (also through empty notes), renders again after save, after discard, after switching task, a server error on notes keeps the source shown; existing "shows every field of the task" | focused task-detail.spec.ts | Pass |
| AC5 New UI strings in web/src/locale (English + German) | messages-de "has the task actions, undo and notes messages in English and German (web-task-actions)" plus the existing generic completeness/placeholder checks | `npx ng test --watch=false --include='src/locale/messages-de.spec.ts'` | Pass |
| AC6 Unit tests cover the new behavior; `npm run check` and `sh scripts/check.sh` pass | all of the above; `npm run test:ci` | `cd web && npm run test:ci` | Pass for the unit suite. `npm run check` (api:check, full lint, e2e typecheck, build) and `sh scripts/check.sh`: Not run: check.sh includes Gradle builds that this helper may not run and takes 6-10 min; the parent worker must run both. ESLint (`npx eslint --max-warnings=0` on the 10 changed spec files) and `npx prettier --check` on them pass. |

## Focused test execution

Commands (from `web/`), each Pass:

- `npx ng test --watch=false --include='src/app/features/gtd/ui/task-undo.spec.ts'` -> 8 passed
- `npx ng test --watch=false --include='src/app/features/gtd/task-detail/task-checklist.spec.ts'` -> 42 passed
- `npx ng test --watch=false --include='src/app/features/gtd/task-detail/task-detail.spec.ts'` -> 136 passed
- `npx ng test --watch=false --include='src/app/features/gtd/lists/list-pages.spec.ts'` -> 42 passed
- `npx ng test --watch=false --include='src/app/features/gtd/next/next-view.spec.ts'` -> 46 passed
- `npx ng test --watch=false --include='src/app/features/gtd/today/today-view.spec.ts'` -> 25 passed
- `npx ng test --watch=false --include='src/app/features/gtd/projects/projects-pages.spec.ts'` -> 21 passed
- `npx ng test --watch=false --include='src/locale/messages-de.spec.ts' --include='.../task-detail.service.spec.ts' --include='.../task-detail-page.spec.ts'` -> 3 files, 37 passed

Result:

Pass

## Full test execution

Commands:

`cd web && npx ng test --watch=false --include='src/app/features/gtd/**/*.spec.ts'` -> 33 files, 859 tests passed (after the Prettier formatting of the new specs).

`cd web && npm run test:ci` -> 190 files, 4414 tests passed (81 s).

Result:

Pass

## .taskcheck

Result:

Not run: `sh scripts/check.sh` runs the Gradle server and Android checks, which this helper is not permitted to run, and takes 6-10 minutes. The parent worker must run it.

## Bugs found

No production defect was found: every new test passes against b28baf82e.

Low-severity observations for the parent (no failing test written, because no requirement says otherwise):

- TW-1 (Low, consistency): in `task-detail.ts` `toggleDone`, when a Complete of task A answers after the editor switched to task B, the code returns before `undo.completed(...)`, so A is completed with no Undo snackbar. Delete in the same situation still offers Undo (`does not close the new task when the deletion of the previous one answers late` asserts this). The tests only assert that B is untouched (`does not show the completion of the previous task in the new one`). The parent may decide whether completing should also offer Undo there.
- TW-2 (Low): `project-detail-page.ts` closes "Choose next step" after an undo when `prompt().done === task.title`, so the match uses the title, not the id. With two tasks of the same title in a project, undoing one could close the prompt of the other. The covered case (different titles) works.

## Coverage

Status: verified (whole-file coverage of the task-changed production files; test selection = the gtd folder).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (the coverage tool has no changed-line mode; not reconstructed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-task-actions; base 4102a26a80c47af215943702c497901f6ee773a7; HEAD b28baf82e736921d2cc9dd234addb6e1d0583291; task commits eba1ef248 and b28baf82e from the parent worker, checked against `git diff --stat 4102a26a8..HEAD` (17 web files, no other commits in the range).
- Source/test snapshot identifier (including uncommitted inputs): production = HEAD b28baf82e (no uncommitted production changes, `git diff --stat` shows only spec files). Uncommitted test files, sha256: list-pages.spec.ts 9444b800ce62..., next-view.spec.ts caba9f925281..., projects-pages.spec.ts d14a1c98f9a3..., task-checklist.spec.ts a1c2767b6e7b..., task-detail-page.spec.ts ff6e888a353f..., task-detail.service.spec.ts 4e56b6904394..., task-detail.spec.ts f6d9d40cf96f..., today-view.spec.ts 3de49ecb50ba..., messages-de.spec.ts f7284ede8268..., ui/task-undo.spec.ts be67bb215d68....
- Exact included files: `web/src/app/features/gtd/lists/list-page.ts`, `next/next-view.ts`, `projects/project-detail-page.ts`, `task-detail/task-checklist.ts`, `task-detail/task-detail.service.ts`, `task-detail/task-detail.ts`, `today/today-view.ts`, `ui/task-undo.ts` (all under `web/src/app/features/gtd/`). Excluded: `ui/index.ts` (a re-export barrel with no executable statements of its own; the provider has no entry for it); the `.html` templates (`task-detail.html`, `task-checklist.html`), because v8 coverage measures the TypeScript sources, not the templates (the DOM tests exercise them); `.scss` files (styles); `web/src/locale/*.xlf` (data, checked by messages-de.spec.ts); spec files.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 with `@vitest/coverage-v8`; `npm run coverage -- --include='src/app/features/gtd/**/*.spec.ts'` in `web/` (the project's coverage script, with the test selection narrowed to the gtd specs).
- Run start/completion time and exit status: started 2026-11-08 15:10:10 (+01:00), about 10 s, all 33 files / 859 tests passed, the command exited without an error.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 8e51ea34bffb...), `web/coverage/web/coverage-final.json` (sha256 d3888299d691...), `lcov.info`, `lcov-report/`; all written at 15:10.
- Evidence outputs are fresh and inputs stayed unchanged: the report files are dated 15:10, after the last spec edit (Prettier at about 15:09). No spec or production file changed after the run (the hashes above were taken after it).
- Final run after latest test additions and production fixes: yes for the tests on HEAD b28baf82e. No production fix was made. If the fixer changes production code, this coverage is stale.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `gtd/lists/list-page.ts` | 33/33 (100%) | 8/9 (88.9%) | 10/10 (100%) | 28/28 (100%) |
| `gtd/next/next-view.ts` | 93/96 (96.9%) | 35/38 (92.1%) | 34/35 (97.1%) | 80/82 (97.6%) |
| `gtd/projects/project-detail-page.ts` | 150/152 (98.7%) | 67/76 (88.2%) | 41/41 (100%) | 131/131 (100%) |
| `gtd/task-detail/task-checklist.ts` | 113/114 (99.1%) | 49/50 (98.0%) | 34/35 (97.1%) | 88/89 (98.9%) |
| `gtd/task-detail/task-detail.service.ts` | 17/17 (100%) | 8/8 (100%) | 13/13 (100%) | 15/15 (100%) |
| `gtd/task-detail/task-detail.ts` | 288/292 (98.6%) | 190/203 (93.6%) | 65/65 (100%) | 237/237 (100%) |
| `gtd/today/today-view.ts` | 48/51 (94.1%) | 19/21 (90.5%) | 18/19 (94.7%) | 39/41 (95.1%) |
| `gtd/ui/task-undo.ts` | 23/23 (100%) | 7/7 (100%) | 7/7 (100%) | 18/18 (100%) |
| Aggregate (8 measured files, summed counters; `ui/index.ts`, templates excluded as above) | 765/778 (98.3%) | 383/412 (93.0%) | 222/225 (98.7%) | 636/641 (99.2%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `next/next-view.ts:224-226`, `today/today-view.ts:113-115` (the `task.completedAt` reopen branch of `toggleDone`) | Reopening a done row from Next/Today | Left uncovered: these views list open tasks only and a completed row disappears at once, so a done row cannot be clicked there. The task only split this existing branch apart. Reopen without Undo is covered through the Logbook (ListPage). |
| `task-detail/task-detail.ts:311` (`if (!task) return` after the save in `toggleDone`) | Defensive guard: the task cannot disappear between the save and the action | Not material. |
| `task-detail/task-detail.ts:154` (`?? ''` of `notesHtml` without a draft) | No draft yet (loading state); the view is not rendered then | Not material. |
| `task-detail/task-detail.ts:257, 392, 407` and branches 168, 226, 360, 362, 375, 412 | Code from before this task (setRepeat/convert guards, close label) | Outside the task's changed behavior. |
| `projects/project-detail-page.ts:168, 264` and binary-expr branches | Code from before this task (makeNext without a project, etc.) | Outside the task's changed behavior. |
| `task-detail/task-checklist.ts:134-135` (`drop`, CDK drag event) | Drag reorder, from before this task | Outside the task's changed behavior (reorder is covered through `reorder()` and the buttons). |
| `lists/list-page.ts:13` binary-expr | Generated decorator branch | Not material. |

## Unable to test

- Real browser behavior of focus/blur on removing the rename input (Chrome fires blur on removal). jsdom does not. The guard (`renaming` is cleared before saving) is covered by "saves only once when Enter is followed by a blur". No end-to-end (Playwright) test was run; the acceptance criteria name no e2e test.
- `npm run check` (full lint, api:check, e2e typecheck, build) and `sh scripts/check.sh` were not run by this helper (see AC6). Follow-up: the parent worker runs them.

## Summary

Tests added and all validation passed (focused specs, the gtd folder, and the full web unit suite `npm run test:ci`). I found no production defect; the two low-severity observations TW-1 and TW-2 are for the parent to decide. `npm run check` and `.taskcheck` still need to be run by the parent worker.
