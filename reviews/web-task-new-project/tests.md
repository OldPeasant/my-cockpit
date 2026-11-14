# Test Report

Date: 2026-11-14

Task: web-task-new-project - Web task detail: create a new project from the project picker

Scope: task base d75effeac87421101b605c2e4816e3f036b50ceb, final code at HEAD 06aa16a644eb34f17cbfd04eae1115b0706c287b. Task code commits: e31ee4903 (implementation), 433f5a45c (round-1 tests and report), 06aa16a64 (fix for CR-1: the created project is reused only for the same task and the same trimmed title, and Discard forgets it). Other commits in the range (2f7d95725 assume, f090d3a62 heartbeat, cee8fad72 review report) change no code. Uncommitted at measurement: one round-2 test added to web/src/app/features/gtd/task-detail/task-detail.spec.ts.

Requirements:
- tasks/web-task-new-project.md acceptance criteria and the recorded assumption (inline title field, Create/Cancel, Create saves the whole form)
- reviews/web-task-new-project/review.md finding CR-1 and reviews/web-task-new-project/fixes.md
- docs/ARCHITECTURE.md "Coverage"

## Test files changed

- `web/src/app/features/gtd/task-detail/task-detail.spec.ts`

## Tests added or updated

Count: round 2 adds 1 test; nothing existing was changed or removed.

All the tests in describe 'New project from the project picker' (task tests, final revision: 19):

- implementation commit (4): asks for a title, creates the project and assigns the task in one step; Cancel or an empty title leave the project unchanged; shows an error when creating fails; Create again retries; a failed save keeps the new project chosen; a retry does not create another
- round 1 (11): after a failed save, Save retries with the created project without creating another; choosing another project after a failed save forgets the created one; Escape in the title field cancels without closing anything else; Enter in the title field creates the project; choosing "New project…" alone changes nothing to save; the new project is offered in the picker and kept in the project list; saves other unsaved edits together with the new project; locks the picker and buttons while the project is being created; switching to another task while creating does not assign the project to it; switching to another task while creating fails shows no error there; a project remembered for one task is not reused for another
- fixer, CR-1 (3): after a failed save, a retry with a different title creates that project; after a conflict, a retry with the same title reuses the created project; Discard after a failed save forgets the created project
- round 2 (1, new): a project created by a retry with another title is the one a later retry reuses

Why the round-2 test: the CR-1 tests check both outcomes of the title comparison and the Discard reset, but nothing checked that a project created by a different-title retry replaces the remembered one. If only the first created project were kept, the sequence "create Garage, save fails, retry with Garden, save fails, retry with Garden" would create a third project, and no test would fail. The new test runs that sequence (two plain 500 save failures) and checks that only Garage and Garden are created and that the last save assigns Garden (p11).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: "New project…" asks only for a title, creates the project (Active, no area: body is title only) and assigns the task in one step | asks for a title, creates the project and assigns the task in one step; Enter in the title field creates the project; saves other unsaved edits together with the new project; locks the picker and buttons while the project is being created | `cd web && npx ng test --watch=false --include src/app/features/gtd/task-detail/task-detail.spec.ts` | Pass |
| AC2a: Cancel or an empty title leave the project unchanged | Cancel or an empty title leave the project unchanged; Escape in the title field cancels without closing anything else; choosing "New project…" alone changes nothing to save | same | Pass |
| AC2b: create or save failure shows an error; a retry does not create a second project | shows an error when creating fails; Create again retries; a failed save keeps the new project chosen; a retry does not create another; after a failed save, Save retries with the created project without creating another; after a conflict, a retry with the same title reuses the created project; a project created by a retry with another title is the one a later retry reuses; choosing another project after a failed save forgets the created one; Discard after a failed save forgets the created project; a project remembered for one task is not reused for another; switching to another task while creating does not assign the project to it; switching to another task while creating fails shows no error there | same | Pass |
| CR-1: a retry with a different title creates the project asked for | after a failed save, a retry with a different title creates that project; a project created by a retry with another title is the one a later retry reuses | same | Pass |
| AC3: the new project appears in the picker and the project list; the task appears in the project detail | the new project is offered in the picker and kept in the project list (option present, the shared ProjectStore holds it, the saved task has projectId p9) | same | Pass (unit level: the project detail page itself was not rendered; it lists tasks by projectId from the server) |
| AC4: new UI strings in web/src/locale (English + German) | inspected in round 1: taskDetail.newProject, newProjectTitle, createProject and cancelNewProject are present with German targets in messages.de.xlf; the CR-1 fix added no strings | inspection of web/src/locale/messages.de.xlf | Pass (inspection, no test) |
| AC5: unit tests cover the behavior; `npm run check` and `sh scripts/check.sh` pass | the whole web unit suite via `npm run coverage` | `cd web && npm run coverage` | Pass for the web unit suite; `npm run check` and `sh scripts/check.sh` Not run: the parent asked not to run Gradle (check.sh runs it) and owns the final check |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/features/gtd/task-detail/task-detail.spec.ts`

Result:

Pass (1 file, 167 tests passed). `npx prettier --check` and `npx eslint` on the spec: clean.

## Full test execution

Command:

`cd web && npm run coverage` (the whole web Vitest suite with coverage)

Result:

Pass (196 files, 4903 tests passed, exit status 0)

## .taskcheck

Result:

Not run: `sh scripts/check.sh` runs Gradle for server and Android, which the parent asked this helper not to run (shared build directories). The parent runs it.

## Bugs found

None. No test failed against the final production code (06aa16a64).

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. Added lines from `git diff -U0 d75effeac..06aa16a64` for the two scoped files intersected with the provider's DA records in `web/coverage/web/lcov.info`: task-detail.ts 40/40 executable changed lines covered; task-detail.html 32/32 executable changed lines covered. Changed-branch coverage was not derived from this; the one uncovered branch on a changed line is listed below.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-task-new-project; base d75effeac87421101b605c2e4816e3f036b50ceb; HEAD 06aa16a644eb34f17cbfd04eae1115b0706c287b; task code commits e31ee4903, 433f5a45c, 06aa16a64 (from the parent prompt and `git log d75effeac..HEAD`).
- Source/test snapshot identifier (including uncommitted inputs): sha256 task-detail.ts 938de4547d250fe991d96ef92c2ae7a55c3662701c87e07171e58afade2c7ee8 (committed at HEAD), task-detail.html a89acfdcb3c83379220afd3952aae2ec267cc466b01ff178619173096bb83e56 (committed at HEAD), task-detail.spec.ts with the uncommitted round-2 test 93596e64f4b998b57f8d73b73f8eb4dcbc830b45030205ae95f6a6552e81b921; identical before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: included web/src/app/features/gtd/task-detail/task-detail.ts and task-detail.html. Excluded: task-detail.spec.ts (test), web/src/locale/messages.xlf and messages.de.xlf (translation data, not executable), reviews/ and tasks/ files (not code). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 with @vitest/coverage-v8; `npm run coverage` in web/; the whole web suite.
- Run start/completion time and exit status: started 2026-11-14 11:53:03 +0100, reports written 11:54:01 +0100; exit status 0.
- Generated report paths and report hashes/run identifier: web/coverage/web/coverage-summary.json sha256 39f2b37654017c599025833cf4bbca74d479e0c0483451cfc442d489d2fab8d6; web/coverage/web/coverage-final.json sha256 588bcd4abaabbab36308896286ebc673998ccc725f17d06fe29d8186404ff250; web/coverage/web/lcov.info sha256 98c77d1f7727e91751b51b4456c61538ce57bf63d27195cc5745ce5abe64c35a.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report files were rewritten at 11:54:01, during this run, and their hashes differ from the round-1 report. The input hashes above were the same before and after.
- Final run after latest test additions and production fixes: yes (after the CR-1 fix 06aa16a64 and the round-2 test).

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/task-detail/task-detail.ts` | 347/351 (98.86%) | 226/240 (94.16%) | 68/68 (100%) | 287/287 (100%) |
| `web/src/app/features/gtd/task-detail/task-detail.html` | 439/444 (98.87%) | 73/75 (97.33%) | 34/37 (91.89%) | 257/259 (99.22%) |
| Aggregate (both files, summed counters) | 786/795 (98.87%) | 299/315 (94.92%) | 102/105 (97.14%) | 544/546 (99.63%) |

No scoped file is missing from the report; nothing is unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `task-detail.ts:290` (right arm of `?? ''`) | createProject called while no title is being asked for | Defensive: createProject can only be reached from the title field and its Create button, which exist only while a title is being asked for; left uncovered |
| `task-detail.ts` branches at 94, 175, 189, 219, 251, 344, 348, 399, 473, 492, 507, 512 | pre-existing editor code (decorator metadata, conversion, notes, actions, the null-task arm of discard) | Not on this task's changed lines (473 is the existing `if (task)` in discard, unchanged); no test added |
| `task-detail.html:221, 299`, branches at lines 1 and 30 | pre-existing template handlers (notes Enter, waiting-since input, context chips) | Not on this task's changed lines; no test added |

## Unable to test

- AC3 "the task appears in the project detail": not rendered in a unit test. It is covered indirectly: the task is saved with the new projectId and the project is in the shared ProjectStore. A browser end-to-end check was not run because it was not part of the requested scope.
- AC5 `npm run check` and `sh scripts/check.sh`: left to the parent, since this helper must not run Gradle.

## Summary

Tests added and all validation passed. Round 2 added one test, for the remembered project being replaced after a retry with a different title. No production defects were found, and every changed executable line in the final revision is covered.
