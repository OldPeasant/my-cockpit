# Test Report

Date: 2026-10-20

Task: web-gtd-data - Web GTD data layer and shared task components

Scope: base `5c73783d1973f0ac35e574a5794870968dbf434f`..HEAD `e0ca00be8a968498bac941e5279b78c25b29c7f9`. The parent named the task commits dd457aa2, fa46741c and a20d40ff. While this run was in progress they were rebased onto main as b9662dc7, e5ad6f05 and e0ca00be. `git diff a20d40ff HEAD -- web/src/app/features/gtd` is empty, so the task code is identical. Since the base, the only changes under `web/src` are the task's files in `web/src/app/features/gtd/{data,ui}`. Uncommitted inputs are only the test files listed below.

Requirements:
- `tasks/web-gtd-data.md` acceptance criteria 1-4
- `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md` project notes
- Scope and helper instructions from the parent worker

## Test files changed

- `web/src/app/features/gtd/data/project-store.spec.ts` (new)
- `web/src/app/features/gtd/data/context-store.spec.ts` (new)
- `web/src/app/features/gtd/data/list-store.spec.ts` (new)
- `web/src/app/features/gtd/data/dates.spec.ts` (new)
- `web/src/app/features/gtd/ui/labels.spec.ts` (new)
- `web/src/app/features/gtd/data/task-store.spec.ts` (extended)
- `web/src/app/features/gtd/ui/task-row.spec.ts` (extended)
- `web/src/app/features/gtd/ui/pickers.spec.ts` (extended; Prettier also reformatted two existing lines without changing what they assert)

## Tests added or updated

Count: 51 (50 new `it` cases plus 1 extended existing case, the pickers `chooses a value...` test). Before this run the focused suite had 27 tests; it now has 77.

## Requirement coverage

Command for all rows: `cd web && npx ng test --watch=false --include='src/app/features/gtd/**/*.spec.ts'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: stores load via the generated client (paging, filters) | TaskStore `loads all pages...`; ProjectStore `loads all projects once with ensureLoaded, following cursors...`, `applies the query filters...`; ContextStore `loads the contexts once, ordered by position then id`, `does not start a second request while loading` | focused | Pass |
| AC1: optimistic update with rollback on error | TaskStore `rolls an optimistic update back...`, `removes a completed task at once...`, `moves a task out...`, `reopens a done task in the Logbook...`, `keeps the other optimistic write when one of two overlapping writes fails`; ProjectStore `rolls a next step back when the server refuses it`, `updates optimistically and rolls back on an error`, `removes a project at once and brings it back on an error`; ContextStore `rolls an update back on an error`, `removes a context at once, keeps it gone on success and restores it on an error` | focused | Pass |
| AC1: optimistic result is correct (setNextStep/stalled, patch semantics, move fields) | ProjectStore `sets the next step at once...`, `clearing the next step of an active project shows it stalled, but not one on hold`; `patched` (5 cases), `etag`; TaskStore `moves a task to Waiting...`, `moves a task to Scheduled with its date`; ContextStore `reorders at once when a position changes` | focused | Pass |
| AC1: refetch on SSE `resync`, apply ChangeEvents | TaskStore `refetches on an SSE resync`, `does not fetch on a resync before the first load`, `removes a task on its DELETED event...`, `does not bring back a task deleted by an event while a list request was running`; ProjectStore `refetches on task events ... drops on PROJECT DELETED`; ContextStore `drops a context on its DELETED event, refetches on others, ignores task events` | focused | Pass |
| AC1: load failure ends loading, retry works | TaskStore `ends loading after a failed load and recovers on a retry`; ProjectStore `can retry ensureLoaded after a failed load` | focused | Pass |
| AC2: an older list request never overwrites a newer one (reversed order) | TaskStore `never lets a slower older list answer overwrite a newer one`, `ignores an older answer of a previous query...`, `keeps the error of the newest request only`, `keeps the error of a newer failed request when an older one succeeds later`, `clears the old items when switching to another query...`, `keeps a local change when a list answer requested before it arrives later`, `keeps a task created while an older list request runs...` | focused | Pass |
| AC2-related: the same write sent twice, answers in reverse order | TaskStore `keeps the newer server answer when two writes to the same task answer in reverse order` | focused | Pass |
| Store filters mirror the server (`matchesTask`) | `matchesTask` 5 cases (list, completion, context, estimate, project, energy, creativity, due-window edges) | focused | Pass |
| AC3: task row (checkbox, title, context, energy, due and project chips) | TaskRow 7 cases, including the new `colors the context dot and omits a project that is not known` | focused | Pass |
| AC3: energy/creativity/estimate pickers, context chips | GTD pickers 5 cases (creativity switch added); labels 2 cases | focused | Pass |
| AC3: date helpers used for due windows | `localDate` 2 cases, `addDays` 5 cases (month/year end, leap year, negative, DST weeks) | focused | Pass |
| AC4: `cd web && npm run check` passes | Full web check, run inside `.taskcheck` | `sh scripts/check.sh` | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/gtd/**/*.spec.ts'`

Result:

Pass: 8 files, 77 tests.

## Full test execution

Command:

`cd web && npm run coverage` (full Vitest suite with coverage); `cd web && npm run lint`

Result:

Pass: 41 files, 626 tests. Lint: "All files pass linting".

## .taskcheck

Result:

Pass. `sh scripts/check.sh` exited 0: completion-gate tests, server `./gradlew check`, web `npm run check` (41 files, 626 tests, plus the build), and Android ktlint, unit tests and lint all passed.

## Bugs found

None. Every new test passed against the current production code. No production code was modified.

## Coverage

Status: verified (fresh run, exit 0, inputs unchanged during the run).

Metric: whole-file coverage of task-changed production files. Changed-line coverage was not measured separately. Every scoped file was added by this task (git diff shows only insertions apart from the in-task rework), so whole-file coverage equals coverage of the added lines here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-gtd-data`. Base `5c73783d1973f0ac35e574a5794870968dbf434f`, measured HEAD `e0ca00be8a968498bac941e5279b78c25b29c7f9`. The parent named commits dd457aa2/fa46741c/a20d40ff, which were rebased to b9662dc7/e5ad6f05/e0ca00be with identical content under `web/src/app/features/gtd` (empty diff). Scope comes from `git diff --name-only 5c73783d..HEAD -- web/src` and the task's `touches` field.
- Source/test snapshot identifier (including uncommitted inputs): sha256 of the concatenated sha256sums of `src/app/features/gtd/{data,ui}/*.ts` and `ui/*.html` (production and spec files) is `b9ec744ffbf82e274e9af7a7f15960f5eb62f91581dd7292f83241805b29eea8`. It was identical before and after the run, and HEAD did not change.
- Exact included files: `data/context-store.ts`, `data/dates.ts`, `data/list-store.ts`, `data/project-store.ts`, `data/task-store.ts`, `ui/context-chips.ts`, `ui/labels.ts`, `ui/pickers.ts`, `ui/segmented.ts`, `ui/task-row.ts`, `ui/task-row.html` (all under `web/src/app/features/gtd/`). Excluded: `data/index.ts` and `ui/index.ts`, which are re-export-only barrels with no executable code and do not appear in the v8 report; `*.scss` (styles); `*.spec.ts`. No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8` 5.0.5. Command `npm run coverage`, run in `web/`, on the full suite.
- Run start/completion time and exit status: 2026-10-20T21:16:00+02:00 to 21:16:17+02:00, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256 `8a58027b0f379a3dd7b43aaab8a6a203d37e7fb2ca5ba261f568fe7e43adf98e`), `web/coverage/web/coverage-summary.json` (sha256 `bf3e57fe6308bcd010be04439e1219caf6c208af7b685f48c1c64fe90a414fc1`). Vitest rewrote both files during this run.
- Evidence outputs are fresh and inputs stayed unchanged: yes, by the snapshot hash and HEAD before and after the run.
- Final run after latest test additions and production fixes: yes. No production fixes were made.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/data/context-store.ts` | 19/19 (100%) | 10/11 (90.9%) | 12/12 (100%) | 15/15 (100%) |
| `web/src/app/features/gtd/data/dates.ts` | 5/5 (100%) | 1/1 (100%) | 2/2 (100%) | 5/5 (100%) |
| `web/src/app/features/gtd/data/list-store.ts` | 126/126 (100%) | 53/54 (98.1%) | 35/35 (100%) | 93/93 (100%) |
| `web/src/app/features/gtd/data/project-store.ts` | 34/34 (100%) | 30/33 (90.9%) | 15/15 (100%) | 27/27 (100%) |
| `web/src/app/features/gtd/data/task-store.ts` | 65/65 (100%) | 71/74 (95.9%) | 21/21 (100%) | 51/51 (100%) |
| `web/src/app/features/gtd/ui/context-chips.ts` | 28/28 (100%) | 12/13 (92.3%) | 4/4 (100%) | 19/19 (100%) |
| `web/src/app/features/gtd/ui/labels.ts` | 14/14 (100%) | 6/8 (75%) | 5/5 (100%) | 10/10 (100%) |
| `web/src/app/features/gtd/ui/pickers.ts` | 37/37 (100%) | 38/41 (92.7%) | 7/7 (100%) | 25/25 (100%) |
| `web/src/app/features/gtd/ui/segmented.ts` | 21/21 (100%) | 13/13 (100%) | 3/3 (100%) | 15/15 (100%) |
| `web/src/app/features/gtd/ui/task-row.ts` | 38/38 (100%) | 24/25 (96%) | 11/11 (100%) | 30/30 (100%) |
| `web/src/app/features/gtd/ui/task-row.html` | 52/52 (100%) | 14/14 (100%) | 2/2 (100%) | 28/28 (100%) |
| **Aggregate (summed counters, 11 files)** | 439/439 (100%) | 272/287 (94.8%) | 117/117 (100%) | 318/318 (100%) |

Aggregate exclusions: the two barrel `index.ts` files (no executable code, not in the report). No scoped file is missing.

An earlier fresh run in this session, made before the last batch of tests (full suite, exit 0, coverage-final sha256 `ecb4d984...`), measured statements 433/439, branches 260/287 and functions 116/117 for the same files. That run had list-store at 50/54 branches, task-store at 63/74 and pickers at 37/41 with 6/7 functions. The new tests closed the meaningful gaps: list-store `mergeLoaded` keeping items created during a running list request and pruning old local changes (lines 207 and 210), the `matchesTask` project/energy/creativity filters and due-window edges, the Scheduled move, and the creativity picker's change handler.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `data/list-store.ts:179:57` | `: other` arm of the map in `accept` (another item stays unchanged) | Justified: only reachable with several items while one is replaced. That behavior is exercised indirectly; the uncovered arm is the identity case. |
| `data/task-store.ts:52,91,109`; `data/project-store.ts:50,65,67`; `data/context-store.ts:42` | `current ?? item` fallback when the edited item is not in the store's list | Low risk: the fallback only matters for edits of items that are not shown. The move fallback (`task-store.ts:67`) is covered by the Waiting/Scheduled move tests. |
| `ui/labels.ts:25,29` | `?? energy` / `?? creativity` fallback for an enum value without a label | Justified: unreachable with the generated types. |
| `ui/pickers.ts:21,40,62`; `ui/context-chips.ts:37`; `ui/task-row.ts:27` | Angular compiler-generated input/model metadata branches | Not behavior: generated glue. |

## Unable to test

- Nothing within the acceptance criteria. Real SSE transport and the generated HTTP client are replaced by fakes in unit tests. This follows the existing test pattern; the real stack is the subject of other tasks' e2e suites.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient, and no production defects were found.
