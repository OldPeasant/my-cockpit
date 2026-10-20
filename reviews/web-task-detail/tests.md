# Test Report

Date: 2026-10-20

Task: web-task-detail - Task detail editor

Scope: task commits 813f5a7d and da9707fe on top of base faf560c2cb8d28dd5218795b7ba326cf7eecb103
(`git diff faf560c2..da9707fe -- web/`), measured at HEAD da9707fe268e0f66e9536d8cd7ad2986b118f258
on branch main, plus the uncommitted test files listed below. No uncommitted production changes.

Requirements:
- `tasks/web-task-detail.md` acceptance criteria and recorded assumptions (move then PATCH with the
  new ETag; per-item checklist saves with If-Match, 412 reloads the checklist; routed page `/tasks/:id`)
- `SPEC.md` §4.2 (If-Match / 412), §5.2 (task fields)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- Parent instructions (checklist, service, page, TaskDetail gaps)

## Test files changed

- `web/src/app/features/gtd/task-detail/task-checklist.spec.ts` (new)
- `web/src/app/features/gtd/task-detail/task-detail.service.spec.ts` (new)
- `web/src/app/features/gtd/task-detail/task-detail-page.spec.ts` (new)
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts` (extended; existing tests unchanged
  apart from `render()` now also keeping the host instance)
- `web/src/app/features/gtd/task-detail/recurrence.spec.ts` (extended with more accept/reject cases)

## Tests added or updated

Count: 77 (TaskChecklist 25, TaskDetailService 8, TaskDetailPage and routes 9, TaskDetail 24,
recurrence parameter cases 11). Focused suite: 149 tests, 148 pass, 1 fails (TW-1).

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include='src/app/features/gtd/task-detail/**/*.spec.ts'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 every field is shown and edited (title, notes, list, project, contexts, energy, creativity, estimate, due date/time, scheduled, tickler, waiting for) | `shows every field of the task`, `edits energy, creativity, estimate, project and due date and saves them`, `marks a typed estimate that is not whole minutes`, `moves first, then patches with the new version`, tickler: `moves the task with its tickler date`, `refuses a tickler date that is not in the future`, `sends the scheduled date together with the list` | F | Pass |
| AC1 checklist add | TaskChecklist add: `adds a trimmed step with the button and clears the input`, `adds a step with Enter`, `does not add an empty or blank step`, `keeps the text and shows the error when adding fails`, `shows a generic message for a non-API error` | F | Pass |
| AC1 checklist toggle (optimistic, rollback on error) | toggle: `checks a step at once and saves it with the item`, `unchecks a done step`, `uses the saved version for the next change of the same step`, `rolls the step back and shows the error when saving fails` | F | Pass |
| AC1 checklist remove | remove: `removes a step with its version`, `puts the step back and shows the error when removing fails` | F | Pass |
| AC1 checklist reorder (up/down, position saves) | reorder: `disables moving the first step up and the last step down`, `moves a step down and saves only the positions that changed`, `moves a step up`, `ignores moves outside the list or onto the same place`, `reloads what the server has when a position save fails half-way`, `disables moving while a change is saving` | F | Pass |
| AC1 recurrence editor (presets + custom RRULE) | `applies a recurrence preset and its mode`, `starts a custom rule from the chosen preset`, `removes the recurrence with "none"`, recurrence.spec (all), `keeps the repeat mode when the chosen mode is pressed again` | F | Fail: TW-1 (the rest pass) |
| AC2 saves with If-Match (task, move, checklist items), silent errors | `saves changed fields with If-Match, silently`, `moves first, then patches with the new version`, TaskDetailService: all 8 tests | F | Pass |
| AC2 a 412 shows "changed elsewhere" and reloads (task form) | `on 412 says the task changed elsewhere and reloads it`, `on 412 of a move says the task changed elsewhere and reloads it` | F | Pass |
| AC2 a 412 on a checklist change reloads the checklist with a message | toggle/remove/reorder `on 412 reloads the checklist and says it changed elsewhere` | F | Pass |
| Other save errors (generic, unexpected, field errors, partial move) | save errors: `shows a generic API error, keeps the changes and allows another try`, `shows a fallback message for an unexpected error`, `keeps a move that went through when the patch after it fails`, `shows a server error on the estimate under the estimate field`, `shows server field errors next to the field`, `does not save twice while a save is running` | F | Pass |
| AC3 linked items panel included | `shows every field of the task` (asserts `app-linked-items`) | F | Pass |
| Close / back navigation | close: `closes at once without changes`, `asks before dropping unsaved changes and stays when the user cancels`, `closes after the user confirms dropping the changes`, `goes back from a missing task`, `goes back from a task moved to the Trash`; TaskDetailPage: `goes back when the app has a previous page`, `goes to the Inbox when the editor was the first page opened`, `goes to the Inbox when there is no navigation state`, `goes back when the editor is closed` (real editor) | F | Pass |
| Routing `/tasks/:id` | `shows the editor for the task in the route`, `follows a change of the route id`, `shows nothing without an id`, `route :id shows the page`, `GTD route /tasks lazily loads the task detail routes` | F | Pass |
| Loading, retry, ordering | loading: `shows a load failure and loads again on retry`, `ignores a change event of another task`, `shows a background reload failure without leaving the form`, `shows only the newest task when the id changes during a load`, `ignores a late load failure of the previous task`; TaskChecklist task change: 3 tests | F | Pass |
| AC4 Vitest tests cover validation, 412, presets; `cd web && npm run check` passes | all of the above | `npm --prefix web run check` | Fail: lint passes; test step fails only on TW-1 (906 tests, 905 pass) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/gtd/task-detail/**/*.spec.ts'`

Result:

Fail: 6 files, 149 tests, 148 passed, 1 failed (`TaskDetail > keeps the repeat mode when the chosen
mode is pressed again`, TW-1). Before the TW-1 test was added the same command passed 134/134.

## Full test execution

Command:

`npm --prefix web run check` (api:check, lint, e2e:typecheck, test:ci, ng build)

Result:

Fail: `ng lint --max-warnings=0` reports "All files pass linting"; test:ci has 51 files, 906 tests,
905 passed, 1 failed (TW-1). At the earlier snapshot without the TW-1 test, `npm run check` exited 0
(891 tests). `npx prettier --check src/app/features/gtd/task-detail/` passes.

## .taskcheck

Result:

Not run: `.taskcheck` (`sh scripts/check.sh`) includes `npm run -s check` for the web part, which
fails on TW-1. Rerun it after the fix.

## Bugs found

### Pressing the chosen "Next occurrence" mode again shows no mode selected

Finding ID:

`TW-1`

Test:

`TaskDetail > keeps the repeat mode when the chosen mode is pressed again`
(`web/src/app/features/gtd/task-detail/task-detail.spec.ts`)

Production source:

`web/src/app/features/gtd/task-detail/task-detail.ts:200-202` (`setRepeatMode`) with
`task-detail.html:284-289` (`<app-segmented [value]="d.repeatMode" (valueChange)="setRepeatMode($event)">`)

Requirement:

AC1 recurrence editor. A recurring task always has a mode (`setRepeatMode` deliberately ignores
`undefined`, and the saved draft keeps the mode), so the editor should keep showing it.

Expected behavior:

When the user clicks the already pressed mode button ("After completion" or "Fixed"), the button
stays pressed: the draft still holds that mode.

Actual behavior:

`Segmented` sets its own `model` value to `undefined` and emits it. `setRepeatMode` ignores it, so
the draft and the `[value]` input binding don't change, and Angular doesn't write the same input
value again. Both mode buttons then show `aria-pressed="false"`, while the draft and the next save
still use the old mode. The displayed state and the saved state no longer match.
Possible fix approaches (for the fixer): use two-way binding to a value that is put back, or a
segmented variant that can't be cleared.

Severity:

Low

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial-failed. The final run on the final test snapshot exited 1 because of TW-1, and Vitest
wrote no coverage report (the `web/coverage/web/` files were absent afterwards). The numbers below
come from the last successful run. That run measured the same production code but an earlier test
snapshot, so they are a lower bound for the current tests and are not final verified coverage.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (all scoped `.ts` files are new in this task, so whole-file
coverage equals changed-file coverage, except for `gtd.routes.ts`, which had one line added).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-task-detail;
  base faf560c2cb8d28dd5218795b7ba326cf7eecb103; HEAD da9707fe268e0f66e9536d8cd7ad2986b118f258;
  commits 813f5a7d, da9707fe as supplied by the parent and confirmed with `git log faf560c2..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): the production files match HEAD
  (sha256 of task-detail.ts 7deb0663..., task-checklist.ts ea6a844b..., task-detail.service.ts
  0c51d48a..., task-detail-page.ts 66bbaace..., task-draft.ts 111b461e..., recurrence.ts d3c1d7d8...,
  gtd.routes.ts 461c7018...). Test snapshot at the measured run: task-checklist.spec.ts ec855d18...,
  task-detail.service.spec.ts 57b4b908..., task-detail-page.spec.ts fafc2130...,
  task-detail.spec.ts 0b211def..., recurrence.spec.ts 02a4d5e9... (unmodified). Final test snapshot
  (not measured): task-detail-page.spec.ts 61b3dfcc..., task-detail.spec.ts 2e42f443...,
  recurrence.spec.ts ca4aa00c...
- Exact included files: `web/src/app/features/gtd/gtd.routes.ts`,
  `web/src/app/features/gtd/task-detail/{recurrence,task-checklist,task-detail-page,task-detail.routes,task-detail.service,task-detail,task-draft}.ts`.
  Excluded: `*.spec.ts` (tests); `*.scss` (styles, no executable behavior); `task-detail.html` and
  `task-checklist.html` (templates). They are not reported as separate files: their AOT-compiled code
  is source-mapped into the component `.ts` files. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with
  `@vitest/coverage-v8` 5.0.5; `npm --prefix web run coverage` (= `ng test --watch=false --coverage ...`)
  in `web/`; the full web suite (51 files).
- Run start/completion time and exit status: measured run 2026-10-20T23:12:23+02:00 to
  23:13:04+02:00, exit 0 (51 files passed). Final run 23:15:00 to 23:15:23+02:00, exit 1 (TW-1),
  no report written.
- Generated report paths and report hashes/run identifier (measured run):
  `web/coverage/web/coverage-final.json` sha256 6fbb430a...924b, `coverage-summary.json` sha256
  d63571d3...af0af, written 23:13:04 by that run.
- Evidence outputs are fresh and inputs stayed unchanged: yes for the measured run. Production
  hashes were the same before and after it.
- Final run after latest test additions and production fixes: no. The final run failed on TW-1 and
  wrote no report. Run `npm run coverage` again after TW-1 is fixed.

Values below use covered/total (percentage) from the provider's `coverage-summary.json`;
zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/gtd.routes.ts` | 7/8 (87.50%) | N/A | 6/7 (85.71%) | 7/8 (87.50%) |
| `web/src/app/features/gtd/task-detail/recurrence.ts` | 59/62 (95.16%) | 48/52 (92.31%) | 16/17 (94.12%) | 50/51 (98.04%) |
| `web/src/app/features/gtd/task-detail/task-checklist.ts` | 78/79 (98.73%) | 32/33 (96.97%) | 27/28 (96.43%) | 61/62 (98.39%) |
| `web/src/app/features/gtd/task-detail/task-detail-page.ts` | 18/18 (100.00%) | 13/16 (81.25%) | 4/4 (100.00%) | 10/10 (100.00%) |
| `web/src/app/features/gtd/task-detail/task-detail.routes.ts` | 1/1 (100.00%) | N/A | N/A | 1/1 (100.00%) |
| `web/src/app/features/gtd/task-detail/task-detail.service.ts` | 12/12 (100.00%) | 5/5 (100.00%) | 8/8 (100.00%) | 10/10 (100.00%) |
| `web/src/app/features/gtd/task-detail/task-detail.ts` | 153/157 (97.45%) | 89/98 (90.82%) | 39/40 (97.50%) | 126/127 (99.21%) |
| `web/src/app/features/gtd/task-detail/task-draft.ts` | 74/74 (100.00%) | 108/108 (100.00%) | 10/10 (100.00%) | 60/60 (100.00%) |
| Aggregate (summed counters, 8 files, none missing) | 402/411 (97.81%) | 295/312 (94.55%) | 110/114 (96.49%) | 325/329 (98.78%) |

Baseline before the new tests (first coverage run, exit 0, same production code): aggregate
393/411 statements, 290/312 branches, 106/114 functions, 320/329 lines. TaskDetailService was
already 100%; task-detail-page.ts was at 12/18 statements.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `gtd.routes.ts:14` (`tasks` loadChildren) | `/tasks/:id` route reachable | Test `GTD route /tasks lazily loads the task detail routes` added after the measured run (passes); not reflected in the numbers above |
| `recurrence.ts:150-154` (BYWEEKNO), `:95` (inRange sign), `:59` (`?? ''` for an unknown preset) | Custom RRULE validation | BYWEEKNO/BYYEARDAY/BYSETPOS/WKST/UNTIL cases added after the measured run (pass). `:59` can't be reached with typed input |
| `task-checklist.ts:87-88` (`drop`, CDK drag) | Reorder by dragging | Not tested: jsdom can't run CDK drag-and-drop. `drop` only forwards to `reorder()`, which is tested directly and through the up/down buttons |
| `task-checklist.ts:33` (`nextUid` branch) | none (id generation) | Non-material |
| `task-detail-page.ts:14,19` (branches) | Template and navigation-state optional chaining | Remaining branches are AOT template/optional-chain artifacts; `navigationId` 1, 3 and null states are tested |
| `task-detail.ts:201` (`setRepeatMode(undefined)`) | Repeat mode stays set | Test added after the measured run; it fails and exposes TW-1 |
| `task-detail.ts:291` (stale load failure ignored) | Ordering | Test `ignores a late load failure of the previous task` added after the measured run (passes) |
| `task-detail.ts:301` (Saved flag reset after 2.5 s) | Cosmetic | Not tested: needs fake timers mixed with the macrotask-based settle helper; low value |
| `task-detail.ts:316` (`messageOf` of a non-API error during a load) | Fallback message | Non-material: same helper as the tested save path |
| `task-detail.ts:83,121,170,205,211,217,315` (branches) | Defensive null checks (`draft` null, `task` null), switch fall-through | Non-material defensive branches |

## Unable to test

- Drag-and-drop reordering through the CDK (`cdkDropListDropped`): jsdom has no pointer-driven
  drag. The reorder logic is covered through `reorder()` and the up/down buttons. A Playwright test
  would be needed to prove dragging in a browser. Follow-up is optional, because the AC does not
  name e2e.
- Observation, not a failing test: the checkbox and the remove button stay enabled while a
  checklist change is saving. Two quick toggles of the same step send the second request with the
  version from before the first response, which can produce a spurious "changed elsewhere" reload.
  The requirements don't define what should happen, so no test asserts it. The parent can decide
  whether to disable the controls while `busy`.
- Observation: if a toggle fails before Angular has rendered the optimistic state (a failure in the
  same microtask), the rolled-back model still leaves the native checkbox checked, because the bound
  value never changed. Real HTTP failures arrive asynchronously after rendering, and the realistic
  test passes, so this is not reported as a bug.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion.
(TW-1, Low: the recurrence mode is no longer shown after pressing the chosen mode again. Coverage
must be measured again after the fix, because the final coverage run failed on that test.)
