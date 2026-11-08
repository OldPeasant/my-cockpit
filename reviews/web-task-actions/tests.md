# Test Report

Date: 2026-11-08

Task: web-task-actions - Web task detail: complete, delete, undo, Markdown notes

Scope: `git diff 6871a30a616a9df59b921d877b9ed23119f78133..84f65350795165c8d1f44aec8dc630455cc67d4c -- web/` (final round on the final code). Task commits cf34e97a8, 77769e63b, 67fb1f56e, 6e9442aa8, 9f4905fcd, 84f653507, supplied by the parent worker and checked against `git log --oneline 6871a30a6..HEAD` (exactly these six commits). Measured on HEAD 84f653507 plus one uncommitted test addition in `task-detail.spec.ts` (below). Latest production change under test: `save()` returns a boolean, and `toggleDone` stops after a failed save (incl. 412) or a task switch during the save-first (commit 84f653507).

Requirements:
- `tasks/web-task-actions.md` acceptance criteria and its three recorded assumptions (list undo covers completing only; Complete saves valid changes first and stays; Delete confirms only when dirty, then navigates back via BackNavigation; rendered notes reuse projects' renderMarkdown, Edit/click/Enter switch to the textarea, links still open; checklist rename via pencil button, Enter/blur saves, Escape cancels)
- Earlier rounds: `reviews/web-task-actions/tests.md` (previous version), `review.md`, `fixes.md`
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`

## Test files changed

- `web/src/app/features/gtd/task-detail/task-detail.spec.ts` (this round, uncommitted)

The task's committed test files (from earlier rounds, all in the measured suite): `gtd/ui/task-undo.spec.ts` (new), `gtd/task-detail/task-detail.spec.ts`, `task-detail-page.spec.ts`, `task-detail.service.spec.ts`, `task-checklist.spec.ts`, `gtd/lists/list-pages.spec.ts`, `gtd/next/next-view.spec.ts`, `gtd/today/today-view.spec.ts`, `gtd/projects/projects-pages.spec.ts`, `web/src/locale/messages-de.spec.ts`.

## Tests added or updated

Count: 1 added this round. No existing test was changed or weakened.

- task-detail "complete, reopen and delete": "does not complete when edits are typed while saving the changes first". The save-first resolves after the user typed more; the toggle must stop (the `this.dirty()` part of the new guard in `toggleDone`): one PATCH, no complete call, the later text stays, "Unsaved changes" is shown, the button reads Complete, no Undo is offered. The other two exits of the new guard (failed save with 412, task switch during the save) were already covered by the tests added in 84f653507 ("does not complete when saving the changes first meets a 412", "does not complete the new task when the editor switches during the save first"); the 500 case by "does not complete when saving the changes first fails".

## Requirement coverage

Focused command: `cd web && npx ng test --watch=false --include='{spec path}'`. All rows also ran in `cd web && npm run test:ci` (190 files, 4429 tests passed) and in the coverage run below.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Task detail has Complete/Reopen (save-first, errors, 412, switching, busy states) | task-detail "completes the task silently with If-Match, stays and offers Undo", "reopens a done task silently ...", "saves unsaved valid changes first, then completes the saved version", "does not complete when the changes are invalid", "does not complete when saving the changes first fails", "does not complete when saving the changes first meets a 412", "does not complete the new task when the editor switches during the save first", "does not complete when edits are typed while saving the changes first" (new), "keeps edits typed while completing runs ...", "keeps edits typed while reopening runs", "on a 412 while completing ...", "shows the error when completing fails and lets the user retry", "shows the running action and starts nothing else meanwhile", "offers Undo for a completion that answers after switching to another task"; task-detail.service complete/uncomplete tests | `npx ng test --watch=false --include='src/app/features/gtd/task-detail/task-detail.spec.ts'` | Pass (148 passed) |
| AC1 Delete (to Trash), navigate back via the shared back helper | task-detail delete tests (immediate delete with Undo and close, dirty confirm/cancel, own DELETED event before and after the answer, someone else's deletion, 412, failure, late answer after switch); task-detail-page "goes back through the shared back navigation after deleting the task, offering Undo"; task-detail.service "moves a task to the Trash silently with If-Match" | full suite `npm run test:ci` | Pass |
| AC2 Undo snackbar service | ui/task-undo.spec.ts (8 tests, one with the real MatSnackBar) | `npm run test:ci` | Pass |
| AC2 Undo in task detail | task-detail Undo tests (reopens the completed/newest version, keeps unsaved edits, after a switch) | focused task-detail.spec.ts | Pass |
| AC2 Undo in lists: Next, Today, project detail, Waiting/Someday/Scheduled | next-view, today-view, projects-pages (incl. prompt closing by id), list-pages "undo after completing" | `npm run test:ci` | Pass |
| AC3 Checklist items renamed inline | task-checklist "rename" group (Enter/blur/Escape, trimming, no-ops, optimistic + rollback, 412, busy, task switch), "strikes through only the done steps", "styles the rename input of an open step too" | `npm run test:ci` | Pass |
| AC4 Notes rendered as Markdown when not editing; click/Edit switch | task-detail "rendered notes" group | focused task-detail.spec.ts | Pass |
| AC5 New UI strings in English + German | messages-de "has the task actions, undo and notes messages in English and German (web-task-actions)" plus generic completeness checks | `npm run test:ci` | Pass |
| AC6 Unit tests cover the new behavior; `npm run check` and `sh scripts/check.sh` pass | all of the above | `cd web && npm run test:ci` | Unit suite Pass. `npm run check` and `sh scripts/check.sh`: Not run: the parent worker runs them (check.sh runs Gradle, which this helper does not run). ESLint `--max-warnings=0` and `prettier --check` on the changed spec pass. |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/gtd/task-detail/task-detail.spec.ts'`

Result:

Pass (1 file, 148 tests). `npx prettier --check` and `npx eslint --max-warnings=0` on the spec: Pass.

## Full test execution

Command:

`cd web && npm run test:ci`

Result:

Pass: 190 files, 4429 tests passed (started 15:30:14, 47 s).

## .taskcheck

Result:

Not run: by instruction of the parent worker this helper runs neither Gradle nor `sh scripts/check.sh`; the parent runs `.taskcheck` and `npm run check`.

## Bugs found

None. Every test passes against HEAD 84f653507. The earlier observations TW-1 and TW-2 were fixed in 6e9442aa8 (see fixes.md) and their tests pass.

## Coverage

Status: verified (whole-file coverage of the task-changed production files; test selection = all gtd specs plus messages-de.spec.ts).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately (method below): statements 185/194 (95.4%), branches starting on a changed line 128/135 (94.8%). Changed-branch coverage is approximated by branches whose start line is an added/modified line; it is not exact.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-task-actions; base 6871a30a616a9df59b921d877b9ed23119f78133; HEAD 84f65350795165c8d1f44aec8dc630455cc67d4c; the six task commits from the parent, equal to `git log 6871a30a6..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): production = HEAD 84f653507, no uncommitted production change. Uncommitted test file: `task-detail.spec.ts` sha256 dcd47e9391f18effead791abca08f1d7d5d815995a5c32f02a2f77039ca3fb65 (taken after the run; no edit after the run). `reviews/web-task-actions/review.md` was also modified in the working tree, not by this helper (not a test input).
- Exact included files (under `web/src/app/features/gtd/`): `lists/list-page.ts`, `next/next-view.ts`, `projects/project-detail-page.ts`, `task-detail/task-checklist.ts`, `task-detail/task-detail.service.ts`, `task-detail/task-detail.ts`, `today/today-view.ts`, `ui/task-undo.ts`. Excluded: `ui/index.ts` (re-export barrel, no coverage entry from the provider); `.html` templates (v8 measures TypeScript; the DOM tests exercise them); `.scss`; `web/src/locale/*.xlf` (data, checked by messages-de.spec.ts); spec files. No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8` (node v24.21.0); `npm run coverage -- --include='src/app/features/gtd/**/*.spec.ts' --include='src/locale/messages-de.spec.ts'` in `web/`.
- Run start/completion time and exit status: started 2026-11-08 15:31:09 +01:00, reports written 15:31:25; 34 files, 882 tests passed, no error.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256 e629645f665ecd93c9d9c08ea4484dafd198978eb27714b4d05eec0649e31720), `web/coverage/web/coverage-summary.json` (sha256 817cec391a2b4a1c473441857e1264d6111a23070590d9cb4ec95ea64a2a832a).
- Evidence outputs are fresh and inputs stayed unchanged: reports dated 15:31:25, after the last spec edit (about 15:29); Vitest rewrites `coverage/` per run.
- Final run after latest test additions and production fixes: yes (HEAD 84f653507 contains the last production fix; the new test was added before the run).
- Changed-line method: added lines from `git diff 6871a30a6..HEAD -U0 -- src/app/features/gtd/`, intersected with `statementMap` start lines and `branchMap` start lines of `coverage-final.json`.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `gtd/lists/list-page.ts` | 33/33 (100%) | 8/9 (88.9%) | 10/10 (100%) | 28/28 (100%) |
| `gtd/next/next-view.ts` | 93/96 (96.9%) | 35/38 (92.1%) | 34/35 (97.1%) | 80/82 (97.6%) |
| `gtd/projects/project-detail-page.ts` | 150/152 (98.7%) | 67/76 (88.2%) | 41/41 (100%) | 131/131 (100%) |
| `gtd/task-detail/task-checklist.ts` | 113/114 (99.1%) | 49/50 (98.0%) | 34/35 (97.1%) | 88/89 (98.9%) |
| `gtd/task-detail/task-detail.service.ts` | 17/17 (100%) | 8/8 (100%) | 13/13 (100%) | 15/15 (100%) |
| `gtd/task-detail/task-detail.ts` | 303/307 (98.7%) | 202/215 (94.0%) | 64/64 (100%) | 248/248 (100%) |
| `gtd/today/today-view.ts` | 48/51 (94.1%) | 19/21 (90.5%) | 18/19 (94.7%) | 39/41 (95.1%) |
| `gtd/ui/task-undo.ts` | 23/23 (100%) | 7/7 (100%) | 7/7 (100%) | 18/18 (100%) |
| Aggregate (8 measured files, summed counters; `ui/index.ts` and templates excluded as above) | 780/793 (98.4%) | 395/424 (93.2%) | 221/224 (98.7%) | 647/652 (99.2%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `next/next-view.ts:224-226`, `today/today-view.ts:113-115` (reopen branch of `toggleDone`) | Reopening a done row from Next/Today | Justified gap: these views list open tasks only and a completed row disappears at once. Reopen without Undo is covered through the Logbook (list-pages). |
| `task-detail/task-detail.ts:315` (`if (!task) return` after the save-first) | Defensive guard: the shown task cannot vanish after a successful save of the same id | Not material. |
| `task-detail/task-detail.ts:406, 421` (`this.busy()` guards of `toReference`/`toCalendar`) | Conversions while Complete/Delete runs | Not material: the buttons are disabled while busy ("shows the running action and starts nothing else meanwhile"); the guard is not reachable through the UI. |
| `task-detail/task-detail.ts:197` (switch back to the id just deleted) | `deletedId` kept when the editor returns to the deleted task | Not material: after its own delete the page navigates away; the other branch is covered by "shows \"moved to the Trash\" when someone else deletes it after its own delete failed". |
| `task-detail/task-detail.ts:154` (`?? ''` without a draft) | Loading state, notes view not rendered | Not material. |
| `task-detail/task-detail.ts:168, 229, 260, 264, 389, 426`, branch 92 | Code from before this task | Outside the task's changed behavior. |
| `projects/project-detail-page.ts:168, 264` and binary-expr branches; `task-checklist.ts:134-135` (`drop`) and branch 36; `list-page.ts:13` | Code from before this task, generated decorator branches | Outside the task's changed behavior. |

## Unable to test

- Real browser focus/blur when the rename input is removed (jsdom does not fire blur on removal); the guard is covered by "saves only once when Enter is followed by a blur". No Playwright test was run; the criteria name none.
- `npm run check` and `sh scripts/check.sh` were not run by this helper (Gradle not allowed here); follow-up: the parent worker runs them.

## Summary

Tests added and all validation passed: one test added for the remaining exit of the new `toggleDone` guard (edits typed during the save-first); focused spec (148) and the full web unit suite (190 files, 4429 tests) pass; task-scoped coverage is fresh for HEAD 84f653507. No production defects found. `npm run check` and `.taskcheck` remain for the parent worker.
