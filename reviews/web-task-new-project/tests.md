# Test Report

Date: 2026-11-14

Task: web-task-new-project - Web task detail: create a new project from the project picker

Scope: task base d75effeac87421101b605c2e4816e3f036b50ceb, implementation commit 612cec44d5c5b8246242eba4e6264e4a401160e5 (the parent named 8abd4876a; the only code commit in d75effeac..HEAD is 612cec44d, so that hash was used). Measured HEAD aa225e34cf54f9f2f0641f2ce386d69fa900313f (adds only reviews/web-task-new-project/review.md on top of 612cec44d; no code change). Uncommitted: the new tests in web/src/app/features/gtd/task-detail/task-detail.spec.ts.

Requirements:
- tasks/web-task-new-project.md acceptance criteria and the recorded assumption (inline title field, Create/Cancel, Create saves the whole form)
- docs/ARCHITECTURE.md "Coverage"
- Inbox processing behavior referenced by the task (web/src/app/features/gtd/inbox/inbox-page.ts, remembered created project per task)

## Test files changed

- `web/src/app/features/gtd/task-detail/task-detail.spec.ts`

## Tests added or updated

Count: 11 added (the 4 tests of the implementation commit kept unchanged; a `ProjectStore` import was added), all in describe 'New project from the project picker':

- after a failed save, Save retries with the created project without creating another
- choosing another project after a failed save forgets the created one
- Escape in the title field cancels without closing anything else
- Enter in the title field creates the project
- choosing "New project…" alone changes nothing to save
- the new project is offered in the picker and kept in the project list
- saves other unsaved edits together with the new project
- locks the picker and buttons while the project is being created
- switching to another task while creating does not assign the project to it
- switching to another task while creating fails shows no error there
- a project remembered for one task is not reused for another

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: "New project…" asks only for a title, creates the project (Active, no area: body is title only) and assigns the task in one step | asks for a title, creates the project and assigns the task in one step; Enter in the title field creates the project; saves other unsaved edits together with the new project; locks the picker and buttons while the project is being created | `cd web && npx ng test --watch=false --include src/app/features/gtd/task-detail/task-detail.spec.ts` | Pass |
| AC2a: Cancel or an empty title leave the project unchanged | Cancel or an empty title leave the project unchanged; Escape in the title field cancels without closing anything else; choosing "New project…" alone changes nothing to save | same | Pass |
| AC2b: create or save failure shows an error; a retry does not create a second project | shows an error when creating fails; Create again retries; a failed save keeps the new project chosen; a retry does not create another; after a failed save, Save retries with the created project without creating another; choosing another project after a failed save forgets the created one; a project remembered for one task is not reused for another; switching to another task while creating does not assign the project to it; switching to another task while creating fails shows no error there | same | Pass |
| AC3: the new project appears in the picker and the project list; the task appears in the project detail | the new project is offered in the picker and kept in the project list (option present, shared ProjectStore holds it, the saved task has projectId p9) | same | Pass (unit level: the project detail page itself was not rendered; it lists tasks by projectId from the server) |
| AC4: new UI strings in web/src/locale (English + German) | inspected: taskDetail.newProject, newProjectTitle, createProject, cancelNewProject present with German targets in messages.de.xlf | grep of web/src/locale/messages.de.xlf | Pass (inspection, no test) |
| AC5: unit tests cover the behavior; `npm run check` and `sh scripts/check.sh` pass | full web suite via `npm run coverage` | `cd web && npm run coverage` | Pass for the web unit suite; `npm run check` and `sh scripts/check.sh` Not run: parent asked not to run Gradle (check.sh runs it) and owns the final check |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/features/gtd/task-detail/task-detail.spec.ts`

Result:

Pass (163 tests passed). Also `npx prettier --check` and `npx eslint` on the spec: clean.

## Full test execution

Command:

`cd web && npm run coverage` (the whole web Vitest suite with coverage)

Result:

Pass (196 files, 4899 tests passed)

## .taskcheck

Result:

Not run: `sh scripts/check.sh` runs Gradle for server and Android, which the parent asked this helper not to run (shared build directories). The parent runs it.

## Bugs found

None. No test failed against the production code.

Observation (no finding, design note for the parent): after a failed save, picking "New project…" again and typing a different title reuses the project created first (its original title); the typed title is ignored. The picker does show the created project selected after the failure, as Inbox processing does, so this matches the "no second project" rule; no test asserts the ignored title.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. Added lines from `git diff -U0 d75effeac..612cec44d` intersected with provider DA records in `web/coverage/web/lcov.info`: task-detail.ts 38/38 executable changed lines covered; task-detail.html 32/32 executable changed lines covered. Changed-branch coverage not derived from this.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-task-new-project; base d75effeac87421101b605c2e4816e3f036b50ceb; HEAD aa225e34cf54f9f2f0641f2ce386d69fa900313f; task code commit 612cec44d (from the parent prompt and `git log d75effeac..HEAD`).
- Source/test snapshot identifier (including uncommitted inputs): sha256 task-detail.ts ff590b664f94ff53ecadd39a8f979f48165f0d13b18f58b7e9ea8c3714f31805, task-detail.html a89acfdcb3c83379220afd3952aae2ec267cc466b01ff178619173096bb83e56, task-detail.spec.ts (uncommitted, with new tests) 7a9be586a7f1fd037dd41f9d71a18b12ebccbed3943fa3d355ec0395fcbd4039; identical before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: included web/src/app/features/gtd/task-detail/task-detail.ts and task-detail.html. Excluded: task-detail.spec.ts (test), web/src/locale/messages.xlf and messages.de.xlf (translation data, not executable). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 with @vitest/coverage-v8 (^5.0.5); `npm run coverage` in web/; whole web suite.
- Run start/completion time and exit status: started 2026-11-14 11:47:51 +0100, report written 11:48:39 +0100; exit status 0.
- Generated report paths and report hashes/run identifier: web/coverage/web/coverage-summary.json sha256 2d81c1757ffebeb2ee7519c684e6f2a2a20102568d5556c2cb148bc1bcb58036; web/coverage/web/coverage-final.json sha256 f268abb851a94582857d42a7020f676ca28a628169a35718f015396c6bfc00ae; web/coverage/web/lcov.info.
- Evidence outputs are fresh and inputs stayed unchanged: yes (Vitest rewrote coverage/ in this run; hashes above checked before and after).
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/task-detail/task-detail.ts` | 345/349 (98.85%) | 224/238 (94.11%) | 68/68 (100%) | 285/285 (100%) |
| `web/src/app/features/gtd/task-detail/task-detail.html` | 439/444 (98.87%) | 73/75 (97.33%) | 34/37 (91.89%) | 257/259 (99.22%) |
| Aggregate (both files, summed counters) | 784/793 (98.87%) | 297/313 (94.89%) | 102/105 (97.14%) | 542/544 (99.63%) |

No scoped file is missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `task-detail.ts:289` (right arm of `?? ''`) | createProject called while not asking for a title | Defensive: createProject is reachable only from the title field and its Create button, which exist only while a title is asked for; left uncovered |
| `task-detail.ts:94, 175, 189, 219, 251, 340, 344, 395, 469, 486, 501, 506` | pre-existing editor code (decorator metadata, conversion, notes, actions) | Outside this task's changed lines; no test added |
| `task-detail.html:30, 112, 221, 299` and branch at line 1 | pre-existing template handlers (notes Enter, waiting-since input, context chips) | Outside this task's changed lines; no test added |

## Unable to test

- AC3 "the task appears in the project detail": not rendered in a unit test; covered indirectly (the task is saved with the new projectId and the project is in the shared ProjectStore). A browser end-to-end check was not run (out of the requested scope).
- AC5 `npm run check` and `sh scripts/check.sh`: left to the parent (Gradle must not be run by this helper).

## Summary

Tests added and all validation passed. No production defects found; task-changed lines are fully covered.
