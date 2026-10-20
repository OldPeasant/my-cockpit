# Test Report

Date: 2026-10-20

Task: web-task-detail - Task detail editor

Scope: task commits 103f1d74, 6ddb6706, b94c4e7f, 93e96703, 27456db6, 0e87a2a4 on top of base
449b9ca5ef129f2bf0834c8073c0414f837f176d (`git diff 449b9ca5..0e87a2a4 -- web/`), measured at
HEAD 0e87a2a4585709c68139b347096125c3e9df4b3f on branch main. This is the final revision after the
fixes for TW-1 and CR-1..CR-4. Uncommitted inputs: the two test files listed below (my changes).
No uncommitted production changes. `reviews/web-task-detail/review.md` was also modified in the
working tree, but not by this helper. It is not a test input and is not part of this report.

Requirements:
- `tasks/web-task-detail.md` acceptance criteria and recorded assumptions (routed page `/tasks/:id`
  that follows route id changes; move then PATCH with the new ETag; per-item checklist saves with
  If-Match, a 412 reloads the checklist)
- `SPEC.md` §4.2 (If-Match / 412), §5.2 (task fields)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- `reviews/web-task-detail/review.md` (CR-1..CR-4) and `fixes.md`, including the fixer's open
  observation that a save of the previous task can finish after the route id changed

## Test files changed

- `web/src/app/features/gtd/task-detail/task-detail.spec.ts` (2 tests added at the end; existing tests unchanged)
- `web/src/app/features/gtd/task-detail/task-checklist.spec.ts` (2 tests added in `task change`; existing tests unchanged)

## Tests added or updated

Count: 4 added in this run, all new; no existing test changed.
- `TaskDetail > hides the "Saved" note again after a moment` (fake timers; passes)
- `TaskDetail > keeps showing the new task when a save of the previous task answers late` (fails: TW-2)
- `TaskChecklist > task change > does not show a step added to the previous task in the new one` (fails: TW-3)
- `TaskChecklist > task change > does not put the steps of the previous task back when its remove fails late` (fails: TW-4)

The fix regression tests for TW-1 and CR-1..CR-4 that the fixer added were reviewed and run. They
pass on the final code, and they assert the required behavior (not implementation details).

Focused suite at the final snapshot: 6 files, 164 tests, 161 pass, 3 fail (TW-2, TW-3, TW-4).
At committed HEAD without my 4 tests: 160/160 pass.

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include='src/app/features/gtd/task-detail/**/*.spec.ts'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 every field is shown and edited (title, notes, list, project, contexts, energy, creativity, estimate, due date/time, scheduled, tickler, waiting for) | `shows every field of the task`, `edits energy, creativity, estimate, project and due date and saves them`, `marks a typed estimate that is not whole minutes`, `moves first, then patches with the new version`, tickler tests (3), `links every field error to its field` | F | Pass |
| AC1 checklist add / toggle / remove / reorder, serialized changes (CR-2) | TaskChecklist add (5), toggle (6, incl. `lets no second toggle or remove start while a change is saving`), remove (3), reorder (6, incl. `disables moving while a change is saving`) | F | Pass |
| AC1 checklist follows a change of the task | `loads the checklist of the new task`, `ignores a late answer for the previous task`, `ignores a late failure for the previous task` (pass); `does not show a step added to the previous task in the new one`, `does not put the steps of the previous task back when its remove fails late` | F | Fail: TW-3, TW-4 (the other 3 pass) |
| AC1 recurrence editor (presets + custom RRULE), repeat mode (TW-1) | `applies a recurrence preset and its mode`, `starts a custom rule from the chosen preset`, `removes the recurrence with "none"`, `keeps the repeat mode when the chosen mode is pressed again`, `switches the repeat mode after the chosen one was pressed again`, recurrence.spec (all) | F | Pass |
| AC2 saves with If-Match (task, move, checklist items) | `saves changed fields with If-Match, silently`, `moves first, then patches with the new version`, TaskDetailService (8) | F | Pass |
| AC2 a 412 shows "changed elsewhere" and reloads | `on 412 says the task changed elsewhere and reloads it`, `on 412 of a move says ...`, checklist toggle/remove/reorder 412 tests, `still applies the conflict reload when a background reload was pending` | F | Pass |
| Background reloads vs. edits and saves (CR-1) | `keeps what the user typed while a background reload was running`, `does not replace a newer save with an older background reload`, `shows a change made elsewhere after the own save`, `ignores a background answer older than the version shown` | F | Pass |
| A save of the previous task must not replace the task shown after a route id change | `keeps showing the new task when a save of the previous task answers late` | F | Fail: TW-2 |
| Save status feedback (Saving..., Saved, cleared after a moment) | `does not save twice while a save is running`, `hides the "Saved" note again after a moment` | F | Pass |
| Accessibility of errors and shortcuts (CR-3, CR-4) | `links every field error to its field`, `links a server error on notes and due time to the field`, `links a server rule error to the repeat choice when no custom rule is edited`, `announces the save shortcuts on the save button` | F | Pass |
| AC3 linked items panel included | `shows every field of the task` (asserts `app-linked-items`) | F | Pass |
| Routing `/tasks/:id`, close/back | TaskDetailPage and routes (9), close (5) | F | Pass |
| AC4 Vitest tests cover validation, 412, presets; `cd web && npm run check` passes | all of the above | `cd web && npm run check` | Fail: api:check, lint, e2e:typecheck pass; test:ci fails only on TW-2, TW-3, TW-4 (921 tests, 918 pass) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/gtd/task-detail/**/*.spec.ts'`

Result:

Fail: 6 files, 164 tests, 161 passed, 3 failed (TW-2, TW-3, TW-4). Before my 4 tests were added,
the same command on committed HEAD passed 160/160 (2026-10-20 23:30:16).
`npx prettier --check src/app/features/gtd/task-detail/` - Pass.

## Full test execution

Command:

`cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, ng build)

Result:

Fail: "src/app/api/generated is up to date.", lint "All files pass linting.", e2e:typecheck passes;
test:ci 51 files, 921 tests, 918 passed, 3 failed (TW-2, TW-3, TW-4), so `ng build` did not run.
On committed HEAD (without my tests) the full suite passed: `npm run coverage` ran all 51 files,
917/917 passed, exit 0.

## .taskcheck

Result:

Not run: this helper's sandbox does not allow running `sh scripts/check.sh` from the repository
root. Its web step (`npm run check`) fails on TW-2..TW-4, so `.taskcheck` would fail too until they
are fixed. The parent must run it after the fix.

## Bugs found

### A save of the previous task replaces the task shown after the route id changed

Finding ID:

`TW-2`

Test:

`TaskDetail > keeps showing the new task when a save of the previous task answers late`
(`web/src/app/features/gtd/task-detail/task-detail.spec.ts`)

Production source:

`web/src/app/features/gtd/task-detail/task-detail.ts:245-271` (`save()`: `this.task.set(current)`
and `this.draft.set(draftOf(current))` at lines 261-262, and `this.task.set(current)` at line 266
in the error path) together with the `taskId` effect at lines 145-148.

Requirement:

AC1/AC2 with the recorded assumption that the editor is the routed page `/tasks/:id` and follows a
change of the route id (tested by `follows a change of the route id`). The linked items panel
inside the editor links to other tasks, so the id can change while a save is running. A save must
update the task it saved, not the one the editor shows now.

Expected behavior:

User saves task t1, then opens t2 before the save answers. t2 loads and is shown. When the t1
save answers, the editor keeps showing t2. The next save PATCHes t2 with t2's version.

Actual behavior:

When the t1 save answers, `save()` puts t1 into `task` and `draft`. The editor at `/tasks/t2`
then shows t1's fields ("Call the plumber today"), while the checklist and the linked items
(bound to `taskId()`) show t2. The next save would send a PATCH for t1 (`task()` is t1). In the
error path, `task.set(current)` has the same effect, and a 412 reload loads t2 but sets the
conflict banner. This is the fixer's open observation in `fixes.md`, now confirmed by a test.
Possible approach (for the fixer): capture the id at the start of `save()` and apply the result
only if `taskId()` is still that id. Or end a running save's effect when `load(..., 'initial')`
starts for another id.

Severity:

Medium (wrong task shown under the URL, and a later save can write to the other task)

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

### A checklist step added to the previous task appears in the new task's checklist

Finding ID:

`TW-3`

Test:

`TaskChecklist > task change > does not show a step added to the previous task in the new one`
(`web/src/app/features/gtd/task-detail/task-checklist.spec.ts`)

Production source:

`web/src/app/features/gtd/task-detail/task-checklist.ts:59-68` (`addItem`:
`this.items.update((items) => [...items, item])` runs without checking that `taskId()` is unchanged)

Requirement:

AC1 checklist add, with the editor following a route id change (`load` already ignores late
answers for a previous task: `ignores a late answer for the previous task`).

Expected behavior:

A step added to t1 whose answer arrives after the editor switched to t2 is not shown in t2's
checklist.

Actual behavior:

t2's checklist shows `['Other task step', 'Buy fittings']`. "Buy fittings" belongs to t1. It also
clears t2's new-step input (`newItem.set('')`).

Severity:

Low (wrong items shown until the next reload; changing such a step writes to t1's item)

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

### A failed remove of the previous task puts its steps into the new task's checklist

Finding ID:

`TW-4`

Test:

`TaskChecklist > task change > does not put the steps of the previous task back when its remove fails late`
(`web/src/app/features/gtd/task-detail/task-checklist.spec.ts`)

Production source:

`web/src/app/features/gtd/task-detail/task-checklist.ts:80-88` (`removeItem` rollback
`() => this.items.set(before)`) through `change()` at lines 119-135

Requirement:

AC1 checklist remove (rollback on error), with the editor following a route id change.

Expected behavior:

If removing a step of t1 fails after the editor switched to t2, t2's checklist stays as it is.

Actual behavior:

The rollback sets the whole checklist to t1's items: t2 shows `['Step a', 'Step b', 'Step c']`
instead of `['Other task step']`. The error of the t1 request is also shown on t2.
Possible approach (for TW-3 and TW-4): in `change()`, capture `taskId()` and skip the success
update, the rollback and the error when the task changed meanwhile.

Severity:

Low (wrong items shown until the next reload; changing them writes to t1's items)

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: verified for the final production code with the committed tests (HEAD 0e87a2a4, before my
4 tests). partial-failed for the final test snapshot. The coverage run that included my 4 tests
exited 1 (TW-2..TW-4), and Vitest wrote no report (`coverage/web/` was absent afterwards), so it
gives no numbers. Rerun `npm run coverage` after the fixes.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. All scoped `.ts` files except `gtd.routes.ts` are
new in this task, so whole-file coverage equals changed-line coverage for them. `gtd.routes.ts`
had one line added and is fully covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-task-detail;
  base 449b9ca5ef129f2bf0834c8073c0414f837f176d; HEAD 0e87a2a4585709c68139b347096125c3e9df4b3f;
  commits 103f1d74, 6ddb6706, b94c4e7f, 93e96703, 27456db6, 0e87a2a4 as supplied by the parent and
  confirmed with `git log 449b9ca5..HEAD` and `git diff --stat 449b9ca5..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): verified run on a clean working
  tree (`git status --short` empty) at HEAD 0e87a2a4. Production sha256 prefixes (unchanged at the
  end): gtd.routes.ts 461c7018c88d, recurrence.ts d3c1d7d8e09b, task-checklist.ts 0c70fff946be,
  task-detail-page.ts 66bbaace3f29, task-detail.routes.ts efc9e4a52115, task-detail.service.ts
  0c51d48a18d0, task-detail.ts 661d7c8dbd50, task-draft.ts 111b461e6488, task-checklist.html
  c7ffea6f610b, task-detail.html 9049aa24d94e. Committed specs: task-detail.spec.ts 359e4a7da059,
  task-checklist.spec.ts 2cc3a8018733. Final (unmeasured) specs: task-detail.spec.ts 5c9c062bae7c,
  task-checklist.spec.ts bc306c97764f.
- Exact included files: `web/src/app/features/gtd/gtd.routes.ts`,
  `web/src/app/features/gtd/task-detail/{recurrence,task-checklist,task-detail-page,task-detail.routes,task-detail.service,task-detail,task-draft}.ts`.
  Excluded: `*.spec.ts` (tests); `*.scss` (styles, no executable behavior); `task-detail.html`
  and `task-checklist.html` are not listed separately because their AOT-compiled code is
  source-mapped into the component `.ts` files. No renamed or deleted files. All 8 files are in the
  report; none are missing.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with
  `@vitest/coverage-v8` (^5.0.5, v8); `npm run coverage` (= `ng test --watch=false --coverage` with
  text-summary, json-summary, json, lcov reporters) in `web/`; full web suite, 51 files.
- Run start/completion time and exit status: verified run started 2026-10-20 23:30:34+02:00,
  reports written 23:30:48+02:00, 917/917 tests passed, exit 0. Final-snapshot run 23:32:50 to
  23:33:04+02:00: 918/921 passed, exit 1, no report.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json`
  sha256 6d5dee59d56c57a91831c397f4578091eeb8268fb7cadf9180d63c3c17e9c1d8,
  `web/coverage/web/coverage-final.json` sha256
  4cc0146212335698e03bde247b1b3885103c78553104b99488708a6f57908556 (verified run). The failed run
  then removed `web/coverage/web/`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report files were written by
  the verified run (timestamps within the run, no earlier report reused). Production hashes were
  the same before and after all runs. No production code changed during this session.
- Final run after latest test additions and production fixes: no. It includes the fixer's
  production fixes and all committed tests, but not my 4 new tests (that run failed and wrote no
  report). The only line in scope that the new passing test is expected to add is the
  `task-detail.ts:325` timer callback.

Values below use covered/total (percentage) from the provider's `coverage-summary.json`;
zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/gtd.routes.ts` | 8/8 (100.00%) | N/A | 7/7 (100.00%) | 8/8 (100.00%) |
| `web/src/app/features/gtd/task-detail/recurrence.ts` | 62/62 (100.00%) | 51/52 (98.07%) | 17/17 (100.00%) | 51/51 (100.00%) |
| `web/src/app/features/gtd/task-detail/task-checklist.ts` | 82/83 (98.79%) | 36/37 (97.29%) | 27/28 (96.42%) | 63/64 (98.43%) |
| `web/src/app/features/gtd/task-detail/task-detail-page.ts` | 18/18 (100.00%) | 13/16 (81.25%) | 4/4 (100.00%) | 10/10 (100.00%) |
| `web/src/app/features/gtd/task-detail/task-detail.routes.ts` | 1/1 (100.00%) | N/A | N/A | 1/1 (100.00%) |
| `web/src/app/features/gtd/task-detail/task-detail.service.ts` | 12/12 (100.00%) | 5/5 (100.00%) | 8/8 (100.00%) | 10/10 (100.00%) |
| `web/src/app/features/gtd/task-detail/task-detail.ts` | 164/166 (98.79%) | 102/108 (94.44%) | 40/41 (97.56%) | 134/134 (100.00%) |
| `web/src/app/features/gtd/task-detail/task-draft.ts` | 74/74 (100.00%) | 108/108 (100.00%) | 10/10 (100.00%) | 60/60 (100.00%) |
| Aggregate (summed counters, 8 files, none missing or excluded) | 421/424 (99.29%) | 315/326 (96.63%) | 113/115 (98.26%) | 337/338 (99.70%) |

The project defines no coverage threshold (`docs/ARCHITECTURE.md` "Coverage": not a gate).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `task-detail.ts:325` (function: `saved` reset after 2.5 s) | Save feedback disappears | Test `hides the "Saved" note again after a moment` added (passes); not yet in the numbers above |
| `task-detail.ts:122, 171, 202, 227` (branches: `task`/`draft` null in `errors`, `edit`, `setRepeat`, `discard`) | Defensive null checks; the form only renders when both are set | Non-material: not reachable through the UI |
| `task-detail.ts:206` (`?? ''` when the previous preset is unknown) | Custom rule start value | Non-material: the draft only holds preset values from `RECURRENCE_PRESETS` |
| `task-detail.ts:84` (`if` branch from the AOT template mapped to the decorator) | Template artifact | Non-material: template branches are exercised by the UI tests |
| `task-checklist.ts:90-91` (`drop`, CDK drag) | Reorder by dragging | Not tested: jsdom can't run CDK drag-and-drop. `drop` only forwards to `reorder()`, which is tested directly and through the up/down buttons |
| `task-checklist.ts:33` (`nextUid` branch) | Id generation | Non-material |
| `recurrence.ts:59` (`?? ''` for an unknown preset) | Preset lookup | Non-material: not reachable with typed input |
| `task-detail-page.ts:14, 19` (branches) | Optional chaining in navigation state / template | `navigationId` 1, 3 and missing state are tested; the rest are optional-chain artifacts |
| Task switch during a running save / checklist change (fully covered lines, wrong behavior) | Editor follows the route id | Tests added; they fail and expose TW-2, TW-3, TW-4 |

## Unable to test

- Drag-and-drop reordering through the CDK (`cdkDropListDropped`): jsdom has no pointer-driven
  drag. The reorder logic is covered through `reorder()` and the up/down buttons. Proving that
  dragging works in a browser needs a Playwright test. Follow-up is optional, because the AC does
  not name e2e.
- `.taskcheck` from the repository root: the sandbox does not allow it here (see above).

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion.
(TW-2 Medium: a save that answers after the route id changed puts the previous task into the
editor. TW-3 / TW-4 Low: a checklist add or a failed remove that answers after the task changed
writes the previous task's steps into the new task's checklist. All fixes for TW-1 and CR-1..CR-4
are verified by passing tests. Coverage must be measured again after the fixes, because the run
with the new tests failed and wrote no report.)
