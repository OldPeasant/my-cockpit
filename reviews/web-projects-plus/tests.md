# Test Report

Date: 2026-11-08

Task: web-projects-plus — Web projects: delete project, all tasks in project detail

Scope: `git diff 7268744e106c4478bda5fe8a63937bba2a0a7706..5cdc8d7e1881b9eb270cd9a2709f8560c0910aab` (task commits aef67f2da, 5cdc8d7e1, branch main), plus the uncommitted test edit in `web/src/app/features/gtd/projects/project-detail-delete.spec.ts` made by this test-writer.

Requirements:
- `tasks/web-projects-plus.md` acceptance criteria and its three recorded assumptions (delete in a "More project actions" menu on detail, Undo via POST /trash restore type PROJECT; server rule "tasks keep their project"; Inbox label too)
- `docs/ARCHITECTURE.md` "Coverage" and code conventions (i18n English + German)
- `AGENTS.md` project notes

## Test files changed

- `web/src/app/features/gtd/projects/project-detail-delete.spec.ts` (written by the parent in aef67f2da; extended here)

## Tests added or updated

Count: 5 added by this test-writer (the file now has 14 tests; the 9 written by the parent were reviewed and kept unchanged)

Added:
- `delete > does not ask or delete twice while the delete runs` — the delete request is held open with a deferred promise; a second `remove()` while it is pending opens no second dialog and sends no second DELETE; after the response exactly one snackbar appears.
- `delete > enables the menu again after a refused delete so it can be retried` — after a failed DELETE the store rolls back, the menu trigger is enabled again and a second attempt deletes and shows the snackbar.
- `delete > passes zero open tasks to the confirmation of a project without tasks` — boundary: the dialog gets `openTasks: 0`.
- `delete > a failing Undo is swallowed (the HTTP layer reports it) and does not refresh` — `restoreTrashItem` throws; no unhandled rejection, no project refresh.
- `DeleteProjectDialog > closes with false on Cancel and true on Delete project` — the dialog's result contract the page relies on (`MatDialogRef.close(false)` / `close(true)`).

Reviewed existing tests (kept): list labels for WAITING/SOMEDAY/SCHEDULED/INBOX and none for NEXT; next-step marker together with a list label; delete confirmation data, DELETE with If-Match of the current version, navigation to /projects, snackbar text; cancel and dismiss (false/undefined) do nothing; refused delete stays on the page; Undo calls `restoreTrashItem {type: PROJECT, id}` and refreshes; dialog texts for 0, 1 and several open tasks.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Delete project: menu, confirmation, server delete rule (tasks keep the project, stated in the dialog), If-Match, navigation, Undo | `delete > asks with the number of open tasks, deletes with If-Match and goes to the projects`; `does nothing when the confirmation is cancelled or dismissed`; `stays on the project when the server refuses the delete`; `Undo restores the project from the Trash`; `does not ask or delete twice while the delete runs`; `enables the menu again after a refused delete so it can be retried`; `passes zero open tasks to the confirmation of a project without tasks`; `a failing Undo is swallowed ...`; `DeleteProjectDialog` (4 tests) | `cd web && npx ng test --watch=false --include src/app/features/gtd/projects` | Pass |
| AC1 server side (DELETE /projects/{id} keeps tasks' project) | No server change in this task; covered by existing server tests, not re-run here | — | Not run: server unchanged, out of the task diff |
| AC2 All open tasks shown, list label for non-Next (Waiting, Someday, Scheduled; Inbox per assumption) | `list labels > labels tasks outside Next with their list and leaves Next tasks unlabelled`; `keeps the next-step marker on a non-Next task that is the next step` | same as above | Pass |
| AC3 New UI strings in `web/src/locale` (English + German) | existing `src/locale/messages-de.spec.ts` (German file consistency) ran in the full web suite | `cd web && npm run coverage` (full Vitest suite) | Pass (191 files, 4443 tests) |
| AC4 Unit tests cover the new behavior | this file; coverage below | `cd web && npm run coverage` | Pass (all changed executable lines covered) |
| AC4 `npm run check` and `sh scripts/check.sh` pass | — | — | Not run: lint/build/check.sh (includes Gradle) left to the parent worker; only the Vitest suite plus prettier/eslint on the changed spec were run here |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/features/gtd/projects`

Result:

Pass — 8 test files, 115 tests (after the additions; one first draft of the double-delete test failed because it assumed the menu stays visible while the delete runs, see "Observations"; the test was corrected, not production code).

Also: `npx prettier --check` and `npx eslint --max-warnings=0` on the changed spec -> clean.

## Full test execution

Command:

`cd web && npm run coverage` (the whole web Vitest suite with coverage)

Result:

Pass — 191 test files, 4443 tests, exit 0.

## .taskcheck

Result:

Not run — `sh scripts/check.sh` also runs the server and Android Gradle builds; left to the parent worker before `tasks.py done`.

## Bugs found

None.

Observations (not defects, no fix required):
- `ProjectStore.remove` removes the project optimistically, so while the DELETE is pending the detail page shows "This project does not exist or is in the Trash." and the menu trigger is not rendered. The `[disabled]="busy().has('delete')"` binding on the trigger is therefore never visible in practice; the `busy` guard in `remove()` still prevents a second delete (tested). On failure the store rolls back and the page returns (tested).

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by intersecting the `git diff -U0 7268744e1..HEAD` added lines of `project-detail-page.ts`, `delete-project-dialog.ts` and `markdown.ts` with the uncovered statement locations in `coverage-final.json`: no uncovered statement lies on a changed line (the only uncovered statements in these files, `project-detail-page.ts` lines 178 and 327, are pre-existing code). Changed-line coverage: all executable changed lines covered. Changed-branch coverage not separately computed beyond the listing below.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-projects-plus; base 7268744e106c4478bda5fe8a63937bba2a0a7706; HEAD 5cdc8d7e1881b9eb270cd9a2709f8560c0910aab; task commits aef67f2da, 5cdc8d7e1 as given by the parent and confirmed by `git diff --stat` (only `web/src/app/features/gtd/projects` and `web/src/locale` changed, matching the task's touches).
- Source/test snapshot identifier (including uncommitted inputs): HEAD 5cdc8d7e1 plus the modified spec, sha256 `5b2a16e33ece34f0dc8d5abb5a38fc9b777b0dcedefc15802c6a1759ce26a021` (`project-detail-delete.spec.ts`); production sha256: `project-detail-page.ts` 8c2ea6712288396028f9e4383b2d5ce05c42149eec7f785144da60c1b7325e6c, `delete-project-dialog.ts` 60efe2b5b9d75d2446471324cdc4981565ebe4bb6024c942ee9f12d1f5c374cd, `markdown.ts` 957bfcd044ede5a66c3ca010813588b80ba4658b3ccc3ac8b1f522cac11e24ec, `project-detail-page.html` 720a42fb9dd6baefa92b2f42f0ada62d9dd6a2da655b196b68272b57e0f0dc58.
- Exact included files: `web/src/app/features/gtd/projects/delete-project-dialog.ts`, `web/src/app/features/gtd/projects/project-detail-page.ts`, `web/src/app/features/gtd/projects/markdown.ts` (formatting-only change). Excluded: `project-detail-page.html` (template; exercised through the component tests, v8 reports no separate counters for it), `project-detail-page.scss`, `project-list-label.scss` (styles), `messages.xlf`, `messages.de.xlf` (locale data, validated by `messages-de.spec.ts`), `markdown.spec.ts`, `project-detail-delete.spec.ts` (tests). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 with `@vitest/coverage-v8`; `npm run coverage` in `web/`; full suite (all spec files).
- Run start/completion time and exit status: 2026-11-08T16:52:12+01:00 to 2026-11-08T16:53:13+01:00, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 a58c66ee523850de50e29acab12943812dbe81b9720b8217ae07fe3e47581467; `web/coverage/web/coverage-summary.json` sha256 1ba5cf5d62b62f774497e5208a29b0ef996c634eb1142c4e55ec3bb8d92ea98e.
- Evidence outputs are fresh and inputs stayed unchanged: Vitest rewrote `coverage/` in this run; the spec and production hashes above were taken before and re-checked after the run and are identical.
- Final run after latest test additions and production fixes: yes (no production fixes were made; the run includes all 5 added tests).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/projects/delete-project-dialog.ts` | 34/34 (100%) | 14/15 (93.33%) | 2/2 (100%) | 23/23 (100%) |
| `web/src/app/features/gtd/projects/project-detail-page.ts` | 177/179 (98.88%) | 80/89 (89.89%) | 46/46 (100%) | 153/153 (100%) |
| `web/src/app/features/gtd/projects/markdown.ts` | 54/54 (100%) | 25/25 (100%) | 8/8 (100%) | 52/52 (100%) |
| Aggregate (summed counters, 3 files above; template, styles and locale files excluded as listed) | 265/267 (99.25%) | 119/129 (92.25%) | 56/56 (100%) | 228/228 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `delete-project-dialog.ts`:61 (if, branch 0) | Compiler-generated branch at the decorated class declaration, not task logic | Justified gap: not reachable behavior |
| `project-detail-page.ts`:58 (if, branch 0) | Same generated branch at the `@Component` class | Justified gap |
| `project-detail-page.ts`:178, 327 (early-return ifs in `makeNext`, `saveDescription`); binary-expr at 87, 103, 105, 136, 210, 234 | Pre-existing code, not changed by this task | Out of task scope |
| `project-detail-page.ts`:271-318 (`listLabel`, `remove`, `restore`) | AC1, AC2 | Fully covered (all statements and branches, incl. cancel, refused delete, pending double delete, failing Undo) |

## Unable to test

- The visual appearance of the list label and of the danger button (styles) — not unit-testable; no e2e test names it in the criteria.
- German rendering of the new strings in the running app (`npm run build:de`) — not run here; the German file consistency spec passed. The parent's `npm run check` builds the app.
- `npm run check` and `sh scripts/check.sh` — not run by this helper (see above); the parent worker must run them for AC4.

## Summary

Tests added and all validation passed (focused suite and the full web Vitest suite); no production defects found. `npm run check` / `.taskcheck` remain for the parent worker.
