# Test Report

Date: 2026-10-21

Task: web-task-detail - Task detail editor

Scope: fourth test-writer run, on revision `65e739b9f61c9124bec47ae2bb4b55b381528ebe` (HEAD of `main`, after a rebase). The task's commits are `415aa28575661a4b53998995ea79a34e93abc478..65e739b9` (`d048b273` up to `65e739b9`, 12 commits, all named `web-task-detail:`), plus one test file this run changed (uncommitted). Since the third report, the only production change is the R3 CR-1 fix in commit `65e739b9`: `save()` keeps a draft that was edited while the save ran (`task-detail.ts:265`). The commit also added 2 tests. This run checks that fix and adds tests only where a real gap remained. R2 CR-2 (following a link drops unsaved edits without asking) is out of scope by a recorded assumption and was not tested.

Requirements:
- tasks/web-task-detail.md acceptance criteria 1-4 and the recorded assumptions
- SPEC.md §5.2 (task fields), §4.2 (If-Match / 412)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md "Project notes"
- reviews/web-task-detail/review.md (R3 CR-1), reviews/web-task-detail/fixes.md (round 3)

## Test files changed

- `web/src/app/features/gtd/task-detail/task-detail.spec.ts`

## Tests added or updated

Count: 2 (both added)

How the tests in `65e739b9` cover the fix:
- `keeps what the user typed while a save was running`: a PATCH-only save is held open while the user types in the notes. The test checks that the notes typed during the save and the saved title both stay in the form, that the form shows "Unsaved changes", and that the next save sends only `{ notesMd }` with `If-Match "2"`. Without the fix this test fails, because the draft is reset to the saved task.
- `is not dirty when the text typed during a save matches the saved version`: covers the other side of the new guard. The draft object changed, but its content equals the saved version, so the form is not dirty.
- In lcov, both sides of the new branch at `task-detail.ts:265` are hit (23 and 3 times).

Two gaps remained, and this run added one test for each:
- `keeps text typed during a move and saves only that text next, without moving again`. The save changes the list, so it runs `/move` and then PATCH. The `/move` is held open while the user types in the notes. The fix compares the kept draft with the moved and patched version, so the test checks three things: the list and waiting-for values stay in the form, the text typed during the save is kept and shown as unsaved, and the next save sends no second `/move` and only PATCHes `{ notesMd }` with `If-Match "3"`. Without the fix, the typed notes would be lost.
- `keeps text typed during a save that fails and sends it with the retry`. The PATCH is held open while the user types, then it fails with 503. The test checks that the error is shown, that both the title from before the save and the notes typed during it stay, and that the retry sends both with `If-Match "1"`. This guards the error path next to the fix against regressions. It does not depend on the fix itself.

A 412 during a save with edits typed meanwhile was left untested on purpose. AC2 requires a reload after a 412, and that replaces the draft. Testing that the typed text is lost would only describe existing behavior, and no requirement sets it.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: panel with every §5.2 field, checklist add/toggle/reorder, recurrence presets + custom RRULE | `shows every field of the task`, `adds a trimmed step with the button and clears the input`, `adds a step with Enter`, `moves a step down and saves only the positions that changed`, `moves a step up`, `applies a recurrence preset and its mode`, `starts a custom rule from the chosen preset`, `round-trips every preset with a rule`, `checks a custom rule only` and the other tests in the six task-detail spec files | `cd web && npx ng test --watch=false --include='src/app/features/gtd/task-detail/**/*.spec.ts'` | Pass |
| AC2: saves with If-Match; 412 shows "changed elsewhere" and reloads | `saves changed fields with If-Match, silently`, `moves first, then patches with the new version`, `on 412 says the task changed elsewhere and reloads it`, `on 412 of a move says the task changed elsewhere and reloads it`, checklist `on 412 reloads the checklist and says it changed elsewhere`, service If-Match tests | same focused command | Pass |
| AC2 (R2 CR-1 / TW-2..TW-4): late answers for the previous task do not change the task shown now | the late-answer save and checklist tests listed in the previous report (unchanged) | same focused command | Pass |
| AC2 (R3 CR-1): edits typed while a save runs are kept and saved next with the new version | `keeps what the user typed while a save was running`, `is not dirty when the text typed during a save matches the saved version`, `keeps text typed during a move and saves only that text next, without moving again`, `keeps text typed during a save that fails and sends it with the retry` | same focused command | Pass |
| AC3: linked items panel included | `shows every field of the task` (asserts `app-linked-items`) | same focused command | Pass |
| AC4: Vitest tests cover form validation, 412 handling and recurrence presets; `npm run check` passes | `does not save an invalid form and shows why`, `links every field error to its field`, task-draft.spec.ts, the 412 tests above, recurrence.spec.ts; full check | `npm --prefix web run check` | Pass (57 files, 1074 tests, lint, api:check, e2e typecheck, build) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/gtd/task-detail/**/*.spec.ts'`

Result:

Pass: 6 files, 173 tests (169 from the previous run, 2 from `65e739b9`, 2 added now).

## Full test execution

Command:

`npm --prefix web run check` (api:check, lint, e2e:typecheck, test:ci, ng build)

Result:

Pass: 57 test files, 1074 tests, "All files pass linting", generated API up to date, build complete. The build warns that the initial bundle is 610.96 kB, over the 500 kB budget. That warning is older than this task (the task's code is in the lazy `task-detail-routes` chunk), and it does not fail the check.

## .taskcheck

Result:

Not run in this pass. The parent asked for the focused suite, `npm run check` and coverage. `sh scripts/check.sh` takes about 6.5 min (server, web and Android), and the parent runs it before `done`. The web part of it (`npm run check`) passed above.

## Bugs found

None. Both new tests passed on the first run against `65e739b9`.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: this task added every in-scope `.ts` file under `task-detail/`, so the whole-file numbers equal changed-line coverage for those files. `gtd.routes.ts` has one added line, now line 16 after the rebase. lcov shows `DA:16,1`, so 1/1 changed lines are covered. The R3 CR-1 change (`task-detail.ts:265`) is covered as `DA:265,26`, and both branch sides are hit (`BRDA:265,28,0,23`, `BRDA:265,28,1,3`). Changed-branch coverage was not measured separately for the other lines.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-task-detail`. The base is `415aa28575661a4b53998995ea79a34e93abc478`, which is both `git merge-base HEAD origin/main` and the parent of the first task commit `d048b273`. Measured HEAD is `65e739b9f61c9124bec47ae2bb4b55b381528ebe`. All 12 commits in `415aa285..HEAD` are named `web-task-detail:`. The file scope is `git diff --name-status 415aa285..HEAD -- web/src` without `*.spec.ts`. It matches the task's `touches` plus the routes line recorded in the assumption.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `65e739b9` plus the uncommitted `task-detail.spec.ts` (sha256 `e313451fb74e64366398412509d157976dc6dce87accfb1aea4c9e3a508c3405`). The combined sha256 of all `task-detail/*.ts`, `task-detail/*.html` and `gtd.routes.ts` is `b18e2da4bceeb7f3570a1fbd377dae9235bd9eb8bcd8e9da043a20231a33ae29`, before and after the coverage run.
- Exact included files; renamed/deleted/excluded files and reasons: included are `gtd.routes.ts`, `recurrence.ts`, `task-checklist.ts`, `task-detail-page.ts`, `task-detail.routes.ts`, `task-detail.service.ts`, `task-detail.ts` and `task-draft.ts`. Excluded are the `*.spec.ts` files (tests) and `controls.scss`, `task-checklist.scss` and `task-detail.scss` (styles, not executable). `task-checklist.html` and `task-detail.html` are compiled into their components and counted with them. No file was renamed or deleted, and every scoped file is in the report.
- Provider/version, command, working directory, and test selection: `@vitest/coverage-v8` 5.0.5 (Vitest 5.0.5), run as `npm --prefix web run coverage` from the repository root. That is `ng test --watch=false --coverage` with the text-summary, json-summary, json and lcov reporters, running in `web/`. All tests ran: 57 files, 1074 tests.
- Run start/completion time and exit status: started 2026-10-21 00:18:21 +02:00, report written 00:18:38 +02:00, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 `d24d4db48e14cb890db6179d02104621e4011b66ebf8f237af0b6d0538bd1be4`), `web/coverage/web/coverage-final.json` (sha256 `7595cd93fdfbd87f0af72aa2b008b9818ba6621e9dec4450ea067079d99d47b7`), `web/coverage/web/lcov.info` (sha256 `24ba74cf53121cfbaebdeaf1aa2a01f9b583d54961482f9633b5a9cc89695dc1`). Per-file counters come from the provider's json-summary, and uncovered locations from coverage-final.json.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Vitest rewrote `coverage/` during this run (mtime 00:18:38), and the source and test hashes were the same before and after.
- Final run after latest test additions and production fixes: yes. It includes `65e739b9` and the two tests added in this run.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/gtd.routes.ts` | 10/10 (100.00%) | N/A | 9/9 (100.00%) | 10/10 (100.00%) |
| `web/src/app/features/gtd/task-detail/recurrence.ts` | 62/62 (100.00%) | 51/52 (98.08%) | 17/17 (100.00%) | 51/51 (100.00%) |
| `web/src/app/features/gtd/task-detail/task-checklist.ts` | 88/89 (98.88%) | 40/41 (97.56%) | 27/28 (96.43%) | 67/68 (98.53%) |
| `web/src/app/features/gtd/task-detail/task-detail-page.ts` | 18/18 (100.00%) | 13/16 (81.25%) | 4/4 (100.00%) | 10/10 (100.00%) |
| `web/src/app/features/gtd/task-detail/task-detail.routes.ts` | 1/1 (100.00%) | N/A | N/A | 1/1 (100.00%) |
| `web/src/app/features/gtd/task-detail/task-detail.service.ts` | 12/12 (100.00%) | 5/5 (100.00%) | 8/8 (100.00%) | 10/10 (100.00%) |
| `web/src/app/features/gtd/task-detail/task-detail.ts` | 170/171 (99.42%) | 108/114 (94.74%) | 41/41 (100.00%) | 136/136 (100.00%) |
| `web/src/app/features/gtd/task-detail/task-draft.ts` | 74/74 (100.00%) | 108/108 (100.00%) | 10/10 (100.00%) | 60/60 (100.00%) |
| **Aggregate (summed counters, 8 files, none missing; only specs and styles excluded)** | 435/437 (99.54%) | 325/336 (96.73%) | 116/117 (99.15%) | 345/346 (99.71%) |

`gtd.routes.ts` has one more route than in the previous report (10 statements instead of 9) because another task added a route on `main`. These are whole-file numbers. The task's own line is covered.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `task-checklist.ts:93-94` (`drop`, 1 function / 1 statement) | Drag and drop calls `reorder()` | Justified gap: a CDK drag gesture cannot be simulated reliably in jsdom. `drop` only forwards to `reorder()`, which is tested directly. |
| `task-checklist.ts:33`, `task-detail.ts:84` (if branch in the compiled component decorator) | None, the compiler adds this branch | Not material |
| `task-detail.ts:122`, `:171`, `:202` (1 statement), `:206`, `:227` (the `draft`/`task` absent sides of guards in `errors`, `edit`, `setRepeat`, `setRepeatMode`, `discard`) | Defensive guards. The template shows these controls only after a task has loaded. | Justified gap: they cannot be reached through the UI. |
| `task-detail-page.ts:14`, `:19` (template `@if` falsy side, `?? ''` fallbacks of the route param) | The route is always `:id` | Justified gap: the router never activates the page without an id. |
| `recurrence.ts:59` (`?? ''` in `presetRule`) | `none`/`custom` have no preset rule | Justified gap: callers handle `none` and `custom` before this point. |

No uncovered code belongs to the R3 CR-1 fix or to the earlier fixes.

## Unable to test

- R2 CR-2 (following a link drops unsaved edits without asking): out of scope by the parent's recorded assumption.
- Real browser drag and drop and the routed page in a real browser: no criterion of this task names E2E tests, so neither was run. The keyboard and button reorder paths and `reorder()` have component tests.

## Summary

Tests added and all validation passed. The R3 CR-1 fix is covered on both sides of its new guard. This run added two tests for gaps that remained: edits typed during a `/move` save, and edits typed during a save that fails. The focused suite (173 tests) and `npm --prefix web run check` (1074 tests) pass. `.taskcheck` was left to the parent. Task-scoped coverage: 99.54% statements, 96.73% branches, 99.15% functions, 99.71% lines. No production defects were found.
