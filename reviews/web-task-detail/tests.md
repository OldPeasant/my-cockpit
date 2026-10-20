# Test Report

Date: 2026-10-21

Task: web-task-detail - Task detail editor

Scope: third test-writer run, on the final revision after the fixes for TW-2, TW-3, TW-4 and the second review's CR-1 (R2 CR-1). The task's commits `0e302d91260eaa7c18f630493c052de155b8a23f..c0e95e04098e469ee14f29d0cd8093f3900e4cfa` on `main`, plus the two test files this run changed (uncommitted). The parent named HEAD 8fe4ba8c and the fix commit b91fef50. Those commits were rewritten when the assume commit 0834a46c was inserted, and they are now c0e95e04 and a1822b65. `git diff --stat 8fe4ba8c HEAD -- web` is empty, so the web code measured here is the same code. R2 CR-2 (following a link drops unsaved edits without asking) is out of scope by a recorded assumption and was not tested.

Requirements:
- tasks/web-task-detail.md acceptance criteria 1-4 and the recorded assumptions
- SPEC.md §5.2 (task fields), §4.2 (If-Match / 412)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md "Project notes"
- reviews/web-task-detail/review.md (R2 CR-1, R2 CR-2), reviews/web-task-detail/fixes.md (round 2)

## Test files changed

- `web/src/app/features/gtd/task-detail/task-detail.spec.ts`
- `web/src/app/features/gtd/task-detail/task-checklist.spec.ts`

## Tests added or updated

Count: 2 (both added)

Before adding tests, I checked how the earlier tests cover the fixes:
- TW-2 / R2 CR-1, a save that succeeds late: `keeps showing the new task when a save of the previous task answers late` asserts that t2 stays in the form, that the PATCH went to t1, and that the next save sends t2 with `If-Match "7"`.
- TW-3, a 412 that arrives late: `shows no conflict for the new task when a save of the previous task gets a 412 late` asserts that no conflict banner is shown and no GET is made for the conflict reload.
- Other late save errors: `shows no save error for the new task when a save of the previous task fails late` asserts that no error is shown and that t2 can be saved afterwards.
- TW-4 and the checklist side of R2 CR-1: these tests cover a late add, a late remove failure and a late toggle 412: `does not show a step added to the previous task in the new one`, `does not put the steps of the previous task back when its remove fails late`, `does not reload or warn for the new task when a toggle of the previous task gets a 412 late`.

Two gaps were left, and this run added one test for each:
- `finishes a move of the previous task without touching the new one or showing "Saved"` (task-detail.spec.ts). It covers a save of t1 that changes the list, with the `/move` answering after the switch to t2. It asserts that t1's PATCH still follows with the version the move returned (`If-Match "2"`), that t2's title and list stay in the form, that no "Saved" note appears on t2 and that the form is not dirty. Earlier tests covered only a PATCH-only save and never checked the "Saved" note.
- `leaves the new task checklist as it is when a toggle of the previous task succeeds late` (task-checklist.spec.ts). It covers a successful toggle of t1 that answers after the switch, which no earlier test did. It asserts that t2's steps and checked state do not change, that no alert appears and that the checklist is usable again.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: panel with every §5.2 field, checklist add/toggle/reorder, recurrence presets + custom RRULE | `shows every field of the task`, `adds a trimmed step with the button and clears the input`, `adds a step with Enter`, `moves a step down and saves only the positions that changed`, `moves a step up`, `applies a recurrence preset and its mode`, `starts a custom rule from the chosen preset`, `round-trips every preset with a rule`, `checks a custom rule only` and the other tests in the six task-detail spec files | `cd web && npx ng test --watch=false --include='src/app/features/gtd/task-detail/**/*.spec.ts'` | Pass |
| AC2: saves with If-Match; 412 shows "changed elsewhere" and reloads | `saves changed fields with If-Match, silently`, `moves first, then patches with the new version`, `on 412 says the task changed elsewhere and reloads it`, `on 412 of a move says the task changed elsewhere and reloads it`, checklist `on 412 reloads the checklist and says it changed elsewhere`, service `patches a task with If-Match of its version` / `moves a task with If-Match of its version` / `updates ... with If-Match of the item version` | same focused command | Pass |
| AC2 (R2 CR-1 / TW-2..TW-4): a late save or checklist answer for the previous task does not change the task now shown, and a late 412 shows no false conflict | the 3 save tests and 3 checklist tests listed above, plus the 2 new tests | same focused command | Pass |
| AC3: linked items panel included | `shows every field of the task` (asserts `app-linked-items`) | same focused command | Pass |
| AC4: Vitest tests cover form validation, 412 handling and recurrence presets; `npm run check` passes | `does not save an invalid form and shows why`, `links every field error to its field`, task-draft.spec.ts, the 412 tests above, recurrence.spec.ts; full check | `cd web && npm run check` | Pass (53 files, 981 tests, lint, api:check, e2e typecheck, build) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/gtd/task-detail/**/*.spec.ts'`

Result:

Pass: 6 files, 169 tests.

## Full test execution

Command:

`cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, ng build)

Result:

Pass, exit 0: 53 test files, 981 tests, "All files pass linting", build complete. The build prints a warning that it already printed before this task: the initial bundle is 605.42 kB, over the 500 kB budget. It does not fail the check.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` printed "== all checks passed" (2026-10-21 00:03:17 to 00:09:41 +02:00). It took about 6.5 min for server, web and Android together on this machine. That is above the 2 min warning in AGENTS.md and within the 15 min timeout.

## Bugs found

None. Both new tests passed on the first run against the current production code.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: every in-scope `.ts` file under `task-detail/` was added by this task, so its changed lines are all its lines, and the whole-file numbers below equal changed-line coverage for these files. `gtd.routes.ts` has one added line (line 15). The lcov report shows `DA:15,1`, so 1/1 changed lines are covered. Changed-branch coverage was not measured separately.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-task-detail`. The base is `0e302d91260eaa7c18f630493c052de155b8a23f`, as the parent supplied it. Measured HEAD is `c0e95e04098e469ee14f29d0cd8093f3900e4cfa`. Task-owned commits: every commit in `0e302d91..HEAD`, all named `web-task-detail:` or `assume: web-task-detail`. The production file scope comes from `git diff --name-only 0e302d91..HEAD -- web/src` without `*.spec.ts`, and it matches the task's `touches` plus the routes line recorded in the assumption.
- Source/test snapshot identifier (including uncommitted inputs): the snapshot is HEAD c0e95e04 plus uncommitted `task-detail.spec.ts` (sha256 `acd740a4c81626f9910218a58ca560f067aa297656c97f2e7706b56573c40a3a`) and `task-checklist.spec.ts` (sha256 `7c39e1bfe517b1246a8cd6dbbc1796564ba463cfc831ee5ebbf9ba5643664410`). The combined sha256 of all `task-detail/*.ts`, `*.html` and `gtd.routes.ts` is `fc310a3d495fb16922f053b531d208f33d6043fc6fd6f8c0d67521a49d649509`. It was the same before and after the coverage run.
- Exact included files; renamed/deleted/excluded files and reasons: included are `gtd.routes.ts`, `recurrence.ts`, `task-checklist.ts`, `task-detail-page.ts`, `task-detail.routes.ts`, `task-detail.service.ts`, `task-detail.ts` and `task-draft.ts`. Excluded are the `*.spec.ts` files (tests) and the `*.scss` files (styles, not executable). `task-checklist.html` and `task-detail.html` are compiled into their components, so their bindings are counted with the component `.ts` files, not as separate entries. No file was renamed or deleted, and no scoped file is missing from the report.
- Provider/version, command, working directory, and test selection: `@vitest/coverage-v8` 5.0.5 (Vitest 5.0.5), run as `npm --prefix web run coverage`, which is `ng test --watch=false --coverage` with the json-summary, json, lcov and text-summary reporters. Working directory `web`. All tests ran: 53 files, 981 tests.
- Run start/completion time and exit status: the command ran twice back to back between 2026-10-21T00:01:16+02:00 and 00:02:48+02:00, and both runs exited 0. The report comes from the second run, whose files were written at 00:02:48.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 `af5ec133142cae203344628da81ce60eb0ce64450cfb6642307716a7e31a57bd`), `web/coverage/web/coverage-final.json` (sha256 `d973e57c289ab6739b46c76a73f5589a791d580164fa93fe83ac3371971109dc`), `web/coverage/web/lcov.info`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Vitest rewrote `coverage/` during this run (timestamps 00:02:48), and the source snapshot hash was the same before and after.
- Final run after latest test additions and production fixes: yes. It includes the two tests this run added and the fix commit a1822b65. Production code did not change after the run.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/gtd.routes.ts` | 9/9 (100.00%) | N/A | 8/8 (100.00%) | 9/9 (100.00%) |
| `web/src/app/features/gtd/task-detail/recurrence.ts` | 62/62 (100.00%) | 51/52 (98.08%) | 17/17 (100.00%) | 51/51 (100.00%) |
| `web/src/app/features/gtd/task-detail/task-checklist.ts` | 88/89 (98.88%) | 40/41 (97.56%) | 27/28 (96.43%) | 67/68 (98.53%) |
| `web/src/app/features/gtd/task-detail/task-detail-page.ts` | 18/18 (100.00%) | 13/16 (81.25%) | 4/4 (100.00%) | 10/10 (100.00%) |
| `web/src/app/features/gtd/task-detail/task-detail.routes.ts` | 1/1 (100.00%) | N/A | N/A | 1/1 (100.00%) |
| `web/src/app/features/gtd/task-detail/task-detail.service.ts` | 12/12 (100.00%) | 5/5 (100.00%) | 8/8 (100.00%) | 10/10 (100.00%) |
| `web/src/app/features/gtd/task-detail/task-detail.ts` | 169/170 (99.41%) | 106/112 (94.64%) | 41/41 (100.00%) | 136/136 (100.00%) |
| `web/src/app/features/gtd/task-detail/task-draft.ts` | 74/74 (100.00%) | 108/108 (100.00%) | 10/10 (100.00%) | 60/60 (100.00%) |
| **Aggregate (summed counters, 8 files, none missing or excluded beyond the specs/styles above)** | 433/435 (99.54%) | 323/334 (96.71%) | 115/116 (99.14%) | 344/345 (99.71%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `task-checklist.ts:93-94` (`drop`, 1 function / 1 statement) | Drag-and-drop calls `reorder()` | Justified gap: a CDK drag gesture cannot be simulated reliably in jsdom. `drop` only forwards to `reorder()`, which is tested directly (moves, ignored moves, busy state, 412, late answers). |
| `task-checklist.ts:33` (if branch in the compiled component decorator) | None, the compiler adds this branch | Not material |
| `task-detail.ts:84` (if branch in the compiled component decorator) | None, the compiler adds this branch | Not material |
| `task-detail.ts:122`, `:171`, `:202`, `:206`, `:227` (the `draft`/`task` absent sides of guards in `errors`, `edit`, `setRepeat`, `setRepeatMode`, `discard`) | Defensive guards. The template shows these controls only after a task has loaded. | Justified gap: they cannot be reached through the UI. |
| `task-detail-page.ts:14`, `:19` (template `@if` falsy side, `?? ''` fallbacks of the route param) | The route is always `:id`, so the param is always set | Justified gap: the router never activates the page without an id |
| `recurrence.ts:59` (`?? ''` in `presetRule` for a preset without a rule) | `none`/`custom` return an empty rule | Justified gap: callers handle `none` and `custom` before this point. `is null for none, the preset rule, or the normalized custom rule` covers what the user sees. |

No uncovered code is part of the R2 CR-1 / TW-2..TW-4 fixes. All new guard lines in `save()`, `TaskChecklist.addItem` and `TaskChecklist.change` are covered on both sides.

## Unable to test

- R2 CR-2 (following a link drops unsaved edits without asking): out of scope by the parent's recorded assumption, so it was not tested. A test would only describe a behavior that has been decided against.
- Real browser drag and drop of checklist steps, and the routed page in a real browser: no criterion of this task names E2E tests, so neither was run. The keyboard and button reorder paths and `reorder()` are covered by component tests.

## Summary

Tests added and all validation passed. The fixes for TW-2, TW-3, TW-4 and R2 CR-1 are covered by tests that assert the required behavior. This run added two tests for gaps that remained: a late `/move` of the previous task, including the "Saved" note, and a late successful checklist toggle. The focused suite (169), `npm run check` (981) and `.taskcheck` pass. Task-scoped coverage: 99.54% statements, 96.71% branches, 99.14% functions and 99.71% lines. No production defects were found.
