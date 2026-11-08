# Test Report

Date: 2026-11-08

Task: web-projects-plus — Web projects: delete project, all tasks in project detail

Scope: second pass on the final code. `git diff 7268744e106c4478bda5fe8a63937bba2a0a7706..e0c1bddbe83320a433d1bb069f2392384fd5e802` (task commits aef67f2da, 5cdc8d7e1, e54172f83, e0c1bddbe on main; e0c1bddbe holds the review fixes CR-1, CR-2, CR-3 from `reviews/web-projects-plus/fixes.md`), plus this test-writer's uncommitted edit of `web/src/app/features/gtd/projects/project-detail-delete.spec.ts`.

Requirements:
- `tasks/web-projects-plus.md` acceptance criteria and its three recorded assumptions (delete in a "More project actions" menu on detail with an Undo snackbar that restores via POST /trash restore type PROJECT; server rule "tasks keep their project", no server change; Inbox label too)
- `reviews/web-projects-plus/review.md` / `fixes.md` (CR-1 keep the page and refocus the menu, CR-2 delete the current version, CR-3 default dialog focus)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md` project notes

## Test files changed

- `web/src/app/features/gtd/projects/project-detail-delete.spec.ts`

## Tests added or updated

Count: 2 added in this pass (the file now has 24 tests: 20 for the page, 4 for the dialog). No existing test was changed.

Added:
- `delete > sends one DELETE when two confirmations were open at the same time`: two `remove()` calls both open a confirmation before either closes (the busy guard does not apply yet), then both confirm. Only one DELETE goes out and one snackbar appears. This exercises the re-read after the dialog (`projects.find` / busy re-check) for a duplicate submission.
- `delete > drops the kept copy after a successful delete (no stale project on its route)`: once the navigation to /projects settles, the `deleting` fallback from CR-1 is cleared, so the page no longer shows the deleted project (heading and menu gone) when the route still says its id. Without the `.finally(() => deleting.set(null))` the copy would stay.

Reviewed against the final code and kept: the tests from the fixer for CR-1 (page content kept during the pending delete, menu disabled; focus back on the menu button after a refused delete), CR-2 (If-Match of the version current at dialog close; no delete when the project disappeared during the dialog) and CR-3 (no `autoFocus` override). Also kept: the earlier delete tests (confirmation data, If-Match, navigation, snackbar, cancel/dismiss, refused delete, retry after refusal, pending double delete, zero open tasks, Undo and failing Undo), the list-label tests and the dialog tests. They match the final behaviour. The earlier "Observation" (page showed "not found" while the delete ran) is resolved by CR-1 and covered by those tests.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Delete project: menu, confirmation stating the server rule (tasks stay open and keep the project), If-Match of the current version, navigation, Undo, page kept while pending, focus after refusal | `delete > asks with the number of open tasks, deletes with If-Match and goes to the projects`; `does nothing when the confirmation is cancelled or dismissed`; `stays on the project when the server refuses the delete`; `Undo restores the project from the Trash`; `does not ask or delete twice while the delete runs`; `keeps the page content while the delete runs instead of "Project not found"`; `moves the focus back to the menu button after a refused delete`; `deletes the version current when the confirmation closes (fresh If-Match)`; `does not delete a project that disappeared while the confirmation was open`; `sends one DELETE when two confirmations were open at the same time`; `drops the kept copy after a successful delete (no stale project on its route)`; `opens the confirmation with the default focus (Cancel via cdkFocusInitial)`; `enables the menu again after a refused delete so it can be retried`; `passes zero open tasks to the confirmation of a project without tasks`; `a failing Undo is swallowed (the HTTP layer reports it) and does not refresh`; `DeleteProjectDialog` (4 tests) | `cd web && npx ng test --watch=false --include src/app/features/gtd/projects` | Pass |
| AC1 server side (DELETE /projects/{id} keeps tasks' project) | No server change in this task; existing server tests not re-run here | none | Not run: server unchanged, outside the task diff |
| AC2 All open tasks shown, list label for non-Next (Waiting, Someday, Scheduled; Inbox per assumption) | `list labels > labels tasks outside Next with their list and leaves Next tasks unlabelled`; `keeps the next-step marker on a non-Next task that is the next step` | `cd web && npx ng test --watch=false --include src/app/features/gtd/projects` | Pass |
| AC3 New UI strings in `web/src/locale` (English + German) | existing `src/locale/messages-de.spec.ts` in the full web suite | `cd web && npm run coverage` | Pass (191 files, 4450 tests) |
| AC4 Unit tests cover the new behavior | this file; coverage below | `cd web && npm run coverage` | Pass (no uncovered statement or branch in changed code) |
| AC4 `npm run check` and `sh scripts/check.sh` pass | none | none | Not run: left to the parent worker (check.sh includes Gradle builds); only the Vitest suite and prettier/eslint on the changed spec ran here |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/features/gtd/projects`

Result:

Pass: 8 test files, 122 tests.

Also `cd web && npx prettier --check src/app/features/gtd/projects/project-detail-delete.spec.ts` gave "All matched files use Prettier code style!", and `cd web && npx eslint --max-warnings=0 src/app/features/gtd/projects/project-detail-delete.spec.ts` finished with no output (clean).

## Full test execution

Command:

`cd web && npm run coverage` (the whole web Vitest suite with v8 coverage)

Result:

Pass: 191 test files, 4450 tests.

## .taskcheck

Result:

Not run. `sh scripts/check.sh` also runs the server and Android Gradle builds; the parent worker runs it before `tasks.py done`.

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by comparing the added lines from `git diff -U0 7268744e1..HEAD` (`project-detail-page.ts` lines 21-99 changed region and 276-339 new methods, all of `delete-project-dialog.ts`, the reformatted lines of `markdown.ts`) with the uncovered statements and branches in `coverage-final.json`. None of the uncovered locations is on a changed line, so every executable changed line and branch is covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-projects-plus; base 7268744e106c4478bda5fe8a63937bba2a0a7706 (given by the parent); HEAD e0c1bddbe83320a433d1bb069f2392384fd5e802. Task commits aef67f2da, 5cdc8d7e1, e54172f83, e0c1bddbe come from `git log 7268744e1..HEAD`; `git diff --stat` shows only `web/src/app/features/gtd/projects`, `web/src/locale` and `reviews/web-projects-plus`, which matches the task's touches.
- Source/test snapshot identifier (including uncommitted inputs): HEAD e0c1bddbe plus the modified spec. sha256 values: `project-detail-delete.spec.ts` e5086f69f90dc5afe7f7702c03009ee2385643ff3e7f7b43da3c4384ab7c2006, `project-detail-page.ts` 8b18ba6234e2e47d52e76c88e9fbf385a432780933a03e2b87d511ad74ad3a12, `project-detail-page.html` 720a42fb9dd6baefa92b2f42f0ada62d9dd6a2da655b196b68272b57e0f0dc58, `delete-project-dialog.ts` 60efe2b5b9d75d2446471324cdc4981565ebe4bb6024c942ee9f12d1f5c374cd, `markdown.ts` 957bfcd044ede5a66c3ca010813588b80ba4658b3ccc3ac8b1f522cac11e24ec. (`reviews/web-projects-plus/review.md` was also modified in the working tree during this run, not by this test-writer; it is not a test or production input.)
- Exact included files: `web/src/app/features/gtd/projects/delete-project-dialog.ts`, `web/src/app/features/gtd/projects/project-detail-page.ts`, `web/src/app/features/gtd/projects/markdown.ts` (formatting-only change). Excluded: `project-detail-page.html` (template, exercised through the component tests; v8 has no separate counters for it), `project-detail-page.scss` and `project-list-label.scss` (styles), `messages.xlf` and `messages.de.xlf` (locale data, checked by `messages-de.spec.ts`), and the spec files. No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 with `@vitest/coverage-v8`; `npm run coverage` in `web/`; full suite (all spec files).
- Run start/completion time and exit status: 2026-11-08T16:58:44+01:00 to 2026-11-08T16:59:41+01:00. All 191 files and 4450 tests passed and the reports were written. The output went through `tail`, so the shell exit code was not captured separately.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 83129d5a5f715a34cba2564c456cef26978497ab9ea1f2acc45f8b682288bade; `web/coverage/web/coverage-summary.json` sha256 713154546447a92990dfb207b561143e5afd58e9ee3035810b480843199210dd. Both differ from the previous pass's report hashes.
- Evidence outputs are fresh and inputs stayed unchanged: Vitest rewrote `coverage/` in this run, and the counters include the new tests (`project-detail-page.ts` now has 190 statements, against 179 in the pre-fix pass). The input hashes above were taken before the run and re-checked afterwards; they are identical.
- Final run after latest test additions and production fixes: yes. The run includes the fixes in e0c1bddbe and both tests added in this pass.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/projects/delete-project-dialog.ts` | 34/34 (100%) | 14/15 (93.33%) | 2/2 (100%) | 23/23 (100%) |
| `web/src/app/features/gtd/projects/project-detail-page.ts` | 188/190 (98.94%) | 88/97 (90.72%) | 48/48 (100%) | 164/164 (100%) |
| `web/src/app/features/gtd/projects/markdown.ts` | 54/54 (100%) | 25/25 (100%) | 8/8 (100%) | 52/52 (100%) |
| Aggregate (summed counters of the 3 files above; template, styles and locale files excluded as listed) | 276/278 (99.28%) | 127/137 (92.70%) | 58/58 (100%) | 239/239 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `delete-project-dialog.ts`:61 (if, branch 0) | Compiler-generated branch at the decorated class declaration, not task logic | Justified gap: not reachable behaviour |
| `project-detail-page.ts`:58 (if, branch 0) | Same generated branch at the `@Component` class | Justified gap |
| `project-detail-page.ts`:187 and 347 (early-return ifs in `makeNext` and `saveDescription`); binary-expr fallbacks at 87, 112, 114, 145, 219, 243 | Code from before this task (route id fallback, description fallbacks, `find(...) ?? project`) | Outside the task scope |
| `project-detail-page.ts`:90-99 (`deleting` fallback in `project`), 280-339 (`listLabel`, `remove`, `restore`) | AC1, AC2, CR-1, CR-2 | Fully covered: all statements and branches, including the fallback both kept and cleared, refused delete with refocus, re-read after the dialog (changed, gone, concurrent confirmations) and failing Undo |

## Unable to test

- How the list label and the danger button look (styles): not unit-testable, and no criterion names an e2e test.
- Real Material focus handling (Cancel focused through `cdkFocusInitial`, the browser moving focus off a disabled button): the unit test only checks that no `autoFocus` override is passed and simulates the focus move. Confirming it needs a browser; follow-up is optional.
- German rendering of the new strings in the running app (`npm run build:de`): not run here; the German file consistency spec passed.
- `npm run check` and `sh scripts/check.sh`: not run by this helper. The parent worker must run them for AC4.

## Summary

Tests added and all validation passed (focused suite and the full web Vitest suite); no production defects found. `npm run check` and `.taskcheck` remain for the parent worker.
