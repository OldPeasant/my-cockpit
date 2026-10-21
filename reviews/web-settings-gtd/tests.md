# Test Report

Date: 2026-10-21

Task: web-settings-gtd (Settings: contexts and areas)

Scope: `git diff 3aa4bbbd3a9abc422a576d0cf520bbcdebc6fb81..HEAD`. The task commit was 79e2c255. While testing it was rebased to 4516346ff7cb110920a81281631482b58a207d96. `git diff 79e2c255..4516346f` touches only `tasks/*.md`, so the production code is identical. Branch `main`. The only uncommitted files are the test files listed below and this report (`reviews/web-settings-gtd/review.md` belongs to the code-reviewer).

Requirements:
- `tasks/web-settings-gtd.md` acceptance criteria 1 and 2
- SPEC.md §5.1 (contexts: name, optional color, position; areas: title, position), §4.2 (If-Match, Problem Details)
- docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md, AGENTS.md
- Parent instructions (edge cases to cover)

## Test files changed

- `web/src/app/features/settings/gtd/gtd-settings.spec.ts` (extended; the `setup` helper was split into `create` + `setup` so that load errors can be tested; no existing assertion was changed)
- `web/src/app/features/settings/gtd/gtd-settings.service.spec.ts` (extended)
- `web/src/app/features/settings/gtd/delete-dialog.spec.ts` (new)

## Tests added or updated

Count: 43 added (30 component, 5 service, 8 delete-dialog unit). Settings suite went from 112 to 155 tests.

## Requirement coverage

All rows were run with `cd web && npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts'`. That is the focused command (F). The same tests also ran in the full suite via `npm run coverage` and in `sh scripts/check.sh`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Create context (with/without color, trimmed name), area | `creates a context with a color and renames an area`, `creates a context without a color`, `creates an area with a trimmed title and rejects a blank one`, service `creates and renames with If-Match`, `creates an area` | F | Pass |
| Create: blank rejected, 409 duplicate shown, input kept, no reload | `does not send a blank context name and shows the field error`, `keeps the input and shows the error when the name already exists`, `keeps the area title and shows the error when creating fails`, service `rejects a duplicate context name with the 409 problem, without a toast`, `shows a generic message for an error that is not an API error` | F | Pass |
| Rename contexts/areas: save, blank/unchanged/Escape restore, Enter finishes, failure restores, 404 reloads | `saves a new context name`, `puts the old name back for a blank or unchanged name without saving`, `puts the old name back on Escape`, `finishes editing a name with Enter`, `puts the old name back and shows the error when saving fails`, `puts the old area title back and shows the error when saving fails`, `reloads the list when the context was deleted elsewhere` | F | Pass |
| Context color: set, clear (`null`), unchanged not saved, `#rgb` shown as `#rrggbb` | `sets a color picked for a context`, `clears the color with null`, `does not save the color a context already has`, `shows a short #rgb color in the picker as #rrggbb` | F | Pass |
| Reorder (buttons, drag/drop, failure restores, busy guard) | existing `reorder` block (5) + `restores the old context order when saving fails`, `ignores a second drop while the first order is being saved`, service `reorders contexts and areas with every id in the new order` | F | Pass |
| Reorder failure must not drop a rename that was saved meanwhile | `keeps a rename saved while a failing reorder was pending` | F | **Fail** (TW-1) |
| Delete asks what happens to assigned tasks/projects (clear / move, counts, no move without other targets, cancel) | existing `delete` block (5) + `moves the projects to another area when chosen, then deletes`, `offers no move when no other area exists`, `delete-dialog.spec.ts` (8 tests) | F | Pass |
| Delete failures: reassignment fails, then no delete, reload, controls usable; DELETE fails; lookup fails, then no dialog; list busy while deleting | `keeps the context, does not delete it and reloads when reassigning a task fails`, `keeps the area and shows the error when the delete itself fails`, `shows the error and opens no dialog when the assigned tasks cannot be listed`, `waits with the delete buttons of a list while one of its deletes is running`, service `stops before deleting when moving an item fails, and does not toast`, `includes done tasks when replacing a context, and stops when one update fails`, `deletes an area without listing its projects when they just lose it`, `collects the projects of an area over all pages` | F | Pass |
| Load: loading, empty, per-list error + retry | `shows a loading note until the lists arrive`, `shows an empty note for each list without items`, `shows the load error of one list and loads it again on retry`, `shows the area load error with its own retry` | F | Pass |
| Route `/settings/gtd` | `settings.routes.spec.ts` `opens Tasks & projects at /settings/gtd` (existing) | F | Pass |
| AC2: `cd web && npm run check` passes | whole web suite | `npm run lint` + `npm run coverage` (full suite) + `sh scripts/check.sh` | **Fail**: only because of TW-1 (1 failed, 1252 passed). Lint passes. |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts'`

Result:

Fail: 8 files, 155 tests, 154 passed, 1 failed (`keeps a rename saved while a failing reorder was pending`, TW-1). Baseline before adding tests: 112/112 passed.

## Full test execution

Command:

`cd web && npm run coverage` (full Vitest suite with coverage), `cd web && npm run lint`

Result:

Fail: 67 files, 1253 tests, 1252 passed, 1 failed (TW-1). Exit 1. Lint: "All files pass linting." Prettier and ESLint are clean on the changed spec files.

## .taskcheck

Result:

Fail: `sh scripts/check.sh` exit 1. The TFP gate tests passed (20 OK), server ktlint + tests passed, and web api:check + lint passed. Web unit tests failed only on TW-1. Android was not reached because the script stops at the first failure.

## Bugs found

### A failing reorder discards a rename (or color change) saved while it was pending

Finding ID:

`TW-1`

Test:

`GtdSettings > reorder edge cases > keeps a rename saved while a failing reorder was pending` (`web/src/app/features/settings/gtd/gtd-settings.spec.ts`)

Production source:

`web/src/app/features/settings/gtd/gtd-settings.ts:153-164` (`reorder`: `this.setList(kind, saved ?? list)`)

Requirement:

AC1 rename/reorder. The `reorder` doc says "a failure restores the old one", meaning the old order. SPEC §4.2 optimistic locking needs the client to keep the current version.

Expected behavior:

When the order save fails, only the order goes back. A rename (or color change) that was saved in the meantime stays visible, with its new version.

Actual behavior:

`reorder` remembers the list from before the move and puts it back on failure. The row inputs and color pickers stay enabled while the reorder runs. If a rename succeeds during that time, the failure brings back the old object: the list shows `@phone` instead of `@mobile`, and it keeps version 1 while the server already has version 2. The next edit of that item then gets a 412 ("changed elsewhere") and the list reloads.

Severity:

Low (needs a slow or failing reorder overlapping a rename. No data is lost on the server, but the UI shows stale data and the next edit fails.)

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

Note (not a defect): the bare `mat-dialog-close` on Cancel closes with `''`, not `undefined`. `remove()` treats it correctly because it checks `if (!result)`, so the dialog test checks for a falsy result.

## Coverage

Status: partial-failed. The project command `npm run coverage` exits 1 because of TW-1, and Vitest writes no coverage report on failure (`coverage/web` was absent afterwards). The numbers below come from a diagnostic run with the same provider and reporters, limited to the settings specs, with the single failing TW-1 test filtered out. It ran 154 tests (1 skipped by the filter), exit 0. The numbers are not a passing validation result.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured with a separate tool. Four of the five scoped files are new in this task, so every line in them is task-added and whole-file coverage equals changed-line coverage there. The only modified file is `settings.routes.ts`, which is 6/6 lines, so its changed lines are covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-settings-gtd`; base 3aa4bbbd3a9abc422a576d0cf520bbcdebc6fb81; measured HEAD 4516346ff7cb110920a81281631482b58a207d96 (rebased 79e2c255, no code change). Scope comes from the parent prompt and `git diff --name-only 3aa4bbbd..HEAD -- web/src`, minus specs.
- Source/test snapshot identifier (including uncommitted inputs): sha256 `delete-dialog.ts` c8ed7c35…, `gtd-settings.ts` dfe8c32c…, `gtd-settings.html` 571f50c0…, `gtd-settings.service.ts` 9a96db6a…, `settings.routes.ts` afc79a47…. Specs: `gtd-settings.spec.ts`, `gtd-settings.service.spec.ts`, `delete-dialog.spec.ts`, `settings.routes.spec.ts` (hashes recorded in `.tmp/snap-before.txt`, verified unchanged after the run).
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/settings/gtd/{delete-dialog.ts, gtd-settings.ts, gtd-settings.html, gtd-settings.service.ts}` and `web/src/app/features/settings/settings.routes.ts`. `gtd-settings.scss` is styles only (0/0). `security-settings.html` is excluded because its change is formatting only (attribute line breaks), with no behavior. Spec files are excluded. No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` (node 24.21.0), working directory `web/`, command `npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts' --filter='^(?!.*keeps a rename saved while a failing reorder was pending)' --coverage --coverage-include='src/app/features/settings/**' --coverage-reporters=json-summary --coverage-reporters=json`. No configuration was changed.
- Run start/completion time and exit status: 2026-10-21T03:33:51+02:00 to 03:34:05+02:00, exit 0. The full `npm run coverage` ran 03:33:05 to 03:33:41, exit 1, and produced no report.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 900ed632a2a5c1c3b1e8729369042ede28bf9ed3c185833881fa7dbd63df3791), `web/coverage/web/coverage-final.json` (sha256 f9500cefc647c5803255db2cd21cb83c2315d0419202772169b0d8eae47e3c5d), both written 03:34:05.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `coverage/web` did not exist before the run (the failed full run left none). The snapshot hashes matched after the run.
- Final run after latest test additions and production fixes: yes for the latest tests. No production fix exists yet. After TW-1 is fixed, coverage must be re-measured with the unfiltered `npm run coverage`.

Values below use covered/total (percentage), from provider-native counters in `coverage-summary.json`. Zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/settings/gtd/gtd-settings.ts` | 144/145 (99.31%) | 86/94 (91.49%) | 53/53 (100%) | 119/119 (100%) |
| `web/src/app/features/settings/gtd/gtd-settings.html` | 220/220 (100%) | 30/30 (100%) | 21/21 (100%) | 143/143 (100%) |
| `web/src/app/features/settings/gtd/gtd-settings.service.ts` | 40/40 (100%) | 15/15 (100%) | 15/15 (100%) | 37/37 (100%) |
| `web/src/app/features/settings/gtd/delete-dialog.ts` | 85/85 (100%) | 29/30 (96.67%) | 5/5 (100%) | 55/55 (100%) |
| `web/src/app/features/settings/settings.routes.ts` | 6/6 (100%) | N/A (0/0) | 5/5 (100%) | 6/6 (100%) |
| **Aggregate (summed counters, 5 files)** | 495/496 (99.80%) | 160/169 (94.67%) | 99/99 (100%) | 360/360 (100%) |

Aggregate excludes `gtd-settings.scss` (0/0, styles) and `security-settings.html` (formatting-only change). No scoped file is missing from the report. Before the tests added in this run, `gtd-settings.ts` was at 142/145 statements and 83/94 branches, and the template at 214/220 statements (diagnostic run at 03:31, same command).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `gtd-settings.ts:229` (`if (this.busy().has(kind)) return;` in `remove`) | Second delete while a change of that list is running | Not reachable from the UI: the delete buttons are disabled while busy (covered by `waits with the delete buttons of a list while one of its deletes is running`). Defensive guard, so the gap is justified. |
| `gtd-settings.ts:101, 114, 232, 250, 295` (`all ?? []` / `this.list(kind) ?? []` right side) | List still `null` when a create, delete or replace finishes | Defensive: the forms and rows only render after the list has loaded. Justified gap. |
| `gtd-settings.ts:47` and one branch without a source location | Decorator / compiler-emitted branch | Not behavior. Justified gap. |
| `delete-dialog.ts:144` (`if` at the class declaration) | Compiler-emitted branch at the decorated class | Not behavior. Justified gap. |

## Unable to test

- Real drag gestures with the CDK pointer (mouse/touch) were not tested. The tests fire `cdkDropListDropped` directly, as the existing tests already do, and the gesture belongs to the CDK library. No follow-up needed unless an E2E task asks for it.
- Visual correctness of the color swatch and layout (SCSS) was not tested. jsdom does no layout. The picker value is checked instead.
- AC2's "`npm run check` passes" cannot be met until TW-1 is fixed. `ng build` and `e2e:typecheck` from `npm run check` were not run separately, because the check stops at the failing unit tests.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion. TW-1 (Low): a failing reorder discards a rename or color change saved while it was pending. Everything else passes. Coverage of the task files is partial-failed (diagnostic, TW-1 test filtered): 495/496 statements, 160/169 branches, 99/99 functions, 360/360 lines. The remaining gaps are defensive or compiler-generated.
