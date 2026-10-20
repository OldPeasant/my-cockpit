# Test Report

Date: 2026-10-20

Task: web-gtd-data - Web GTD data layer and shared task components

Scope: second pass on the final code after the fixes. The base is `f43a070a4c7e449d542c2516b3222ee31b1d1ae8` and the measured HEAD is `bfec77d57c5c7a555e23ab8a63a023fe17265e35`. The task commits are b9662dc7, e5ad6f05, e0ca00be, d63a6324 and bfec77d5 (`git log --oneline f43a070a..HEAD`). This pass focuses on the fix commit bfec77d5, which covers CR-1 (`ListStore.ifMatch` now sends the caller's `item.version`) and CR-2 (`ContextStore`/`ProjectStore.ensureLoaded` share the running load). The whole task file scope was re-measured for coverage. `git diff --name-only f43a070a..HEAD -- web/src server/src/main android/app/src/main` lists only files under `web/src/app/features/gtd/{data,ui}`, matching the task's `touches`. The first pass used base `5c73783d`, and the task file set is the same. Uncommitted inputs are only the two spec files listed below. `reviews/web-gtd-data/review.md` was also modified in the working tree; that file was not written by this helper and is not a test input.

Requirements:
- `tasks/web-gtd-data.md` acceptance criteria 1-4
- `reviews/web-gtd-data/fixes.md` (CR-1, CR-2), `reviews/web-gtd-data/review.md`
- SPEC.md §4.2 (optimistic locking via ETag/If-Match)
- `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md` project notes
- Scope and helper instructions from the parent worker

## Test files changed

In this pass (uncommitted):
- `web/src/app/features/gtd/data/context-store.spec.ts` (2 tests added)
- `web/src/app/features/gtd/data/project-store.spec.ts` (2 tests added)

From the first pass and the fixer (already committed): `project-store.spec.ts`, `context-store.spec.ts`, `list-store.spec.ts`, `dates.spec.ts`, `labels.spec.ts`, `task-store.spec.ts`, `task-row.spec.ts`, `pickers.spec.ts`.

## Tests added or updated

Count: 4 in this pass. The focused suite grew from 79 tests (after the fixer) to 83.

Review of the fixer's tests:
- CR-1: the TaskStore test "sends the version of the caller's copy as If-Match, so a stale copy gets a conflict" asserts that a stale v1 copy sends `"1"` while the list holds v3, and that `find('a')` sends `"3"`. It would fail against the old `max(...)` code. Adequate.
- CR-2: the ContextStore test "lets a second caller wait for the running load instead of starting another" and the ProjectStore test "lets a second caller wait until the running load has all pages" both assert that the second caller is still waiting before the (last) answer arrives. They would fail against the old early return. Both stores also have a test that retries after a failure. Adequate.

Gaps closed in this pass:
- CR-1 applies to every `ListStore` subclass, but only TaskStore asserted the stale-copy behavior. Added:
  - ProjectStore `sends the version of the caller's copy as If-Match, so a stale copy gets a conflict`. It covers `setNextStep`, `update` and `remove` with a stale copy (v1 while the list holds v5), and `find('p')` sending `"5"`.
  - ContextStore `sends the version of the caller's copy as If-Match for updates and deletes`.
- CR-2: waiting callers on a load that fails, and the `finally` reset when several callers share the load. Added:
  - ContextStore `settles every waiting caller when the shared load fails, then retries once`. Both callers settle, `error` is set, and two retry callers again share a single request.
  - ProjectStore `settles a waiting caller when a later page of the shared load fails`. Page 2 fails; both callers settle, and `loading` is false. A retry starts again from the first page (no cursor).

## Requirement coverage

Focused command: `cd web && npx ng test --watch=false --include='src/app/features/gtd/**/*.spec.ts'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: stores load via the generated client (paging, filters) | TaskStore `loads all pages...`; ProjectStore `loads all projects once with ensureLoaded, following cursors...`, `applies the query filters...`; ContextStore `loads the contexts once, ordered by position then id` | focused | Pass |
| AC1 / CR-2: concurrent `ensureLoaded` callers share one load and resolve only when it settles; a failed load can be retried | ContextStore `lets a second caller wait for the running load...`, `can retry ensureLoaded after a failed load`, `settles every waiting caller when the shared load fails, then retries once`; ProjectStore `lets a second caller wait until the running load has all pages`, `can retry ensureLoaded after a failed load`, `settles a waiting caller when a later page of the shared load fails` | focused | Pass |
| AC1: optimistic update with rollback on error | TaskStore `rolls an optimistic update back...`, `removes a completed task at once...`, `moves a task out...`, `reopens a done task in the Logbook...`, `keeps the other optimistic write when one of two overlapping writes fails`; ProjectStore `rolls a next step back when the server refuses it`, `updates optimistically and rolls back on an error`, `removes a project at once and brings it back on an error`; ContextStore `rolls an update back on an error`, `removes a context at once, keeps it gone on success and restores it on an error` | focused | Pass |
| CR-1 / SPEC §4.2: If-Match is the caller's copy's version, so a stale copy gets a conflict | TaskStore `sends the version of the caller's copy as If-Match, so a stale copy gets a conflict`; ProjectStore test of the same name; ContextStore `sends the version of the caller's copy as If-Match for updates and deletes` | focused | Pass |
| AC1: optimistic result is correct (setNextStep/stalled, patch semantics, move fields) | ProjectStore `sets the next step at once...`, `clearing the next step of an active project shows it stalled, but not one on hold`; `patched` (5 cases), `etag`; TaskStore `moves a task to Waiting...`, `moves a task to Scheduled with its date`; ContextStore `reorders at once when a position changes` | focused | Pass |
| AC1: refetch on SSE `resync`, apply ChangeEvents | TaskStore `refetches on an SSE resync`, `does not fetch on a resync before the first load`, `removes a task on its DELETED event...`, `does not bring back a task deleted by an event while a list request was running`; ProjectStore `refetches on task events ... drops on PROJECT DELETED`; ContextStore `drops a context on its DELETED event, refetches on others, ignores task events` | focused | Pass |
| AC1: load failure ends loading, retry works | TaskStore `ends loading after a failed load and recovers on a retry`; the store retry tests above | focused | Pass |
| AC2: an older list request never overwrites a newer one (reversed order) | TaskStore `never lets a slower older list answer overwrite a newer one`, `ignores an older answer of a previous query...`, `keeps the error of the newest request only`, `keeps the error of a newer failed request when an older one succeeds later`, `clears the old items when switching to another query...`, `keeps a local change when a list answer requested before it arrives later`, `keeps a task created while an older list request runs...` | focused | Pass |
| AC2-related: the same write sent twice, answers in reverse order | TaskStore `keeps the newer server answer when two writes to the same task answer in reverse order` | focused | Pass |
| Store filters mirror the server (`matchesTask`) | `matchesTask` (5 cases) | focused | Pass |
| AC3: task row (checkbox, title, context, energy, due and project chips) | TaskRow (7 cases) | focused | Pass |
| AC3: energy/creativity/estimate pickers, context chips | GTD pickers (5 cases); labels (2 cases) | focused | Pass |
| AC3: date helpers used for due windows | `localDate` (2 cases), `addDays` (5 cases) | focused | Pass |
| AC4: `cd web && npm run check` passes | Full web check, run inside `.taskcheck` | `sh scripts/check.sh` | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/gtd/**/*.spec.ts'`

Result:

Pass: 8 files, 83 tests.

## Full test execution

Command:

`cd web && npm run coverage` (the full Vitest suite with coverage); `cd web && npm run lint`

Result:

Pass: 41 files, 632 tests. Lint: "All files pass linting."

## .taskcheck

Result:

Pass. `sh scripts/check.sh` ended with "== all checks passed". It ran the server check, the web `npm run check` (including the build) and the Android ktlint, unit tests and lint. The web build only warned that the initial bundle exceeds its budget (599.10 kB of 500 kB), as in earlier runs; this task did not cause that.

## Bugs found

None. All 4 new tests passed against the fixed production code. No production code was modified.

Observation (not a defect, no test involved): `npx prettier --check src/app/features/gtd` reports that `data/task-store.ts` and `ui/context-chips.ts` are not Prettier-formatted. `docs/ARCHITECTURE.md` says web code is formatted with Prettier, but `npm run check`/`.taskcheck` do not enforce it, and lint passes. The new spec files are Prettier-clean.

## Coverage

Status: verified (fresh run, exit 0, inputs unchanged during the run).

Metric: whole-file coverage of task-changed production files. Changed-line coverage was not measured as a separate metric. Every scoped file was added by this task, so whole-file coverage matches added-line coverage. The fix commit's changed lines (`ContextStore`/`ProjectStore.ensureLoaded`, including both arms of `??=`, and `ListStore.ifMatch`) have no uncovered statements or branches in the report.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-gtd-data`. Base `f43a070a4c7e449d542c2516b3222ee31b1d1ae8` (given by the parent). HEAD `bfec77d57c5c7a555e23ab8a63a023fe17265e35`. Task commits b9662dc7, e5ad6f05, e0ca00be, d63a6324 and bfec77d5. The file scope comes from `git diff --name-only f43a070a..HEAD -- web/src server/src/main android/app/src/main` and the task's `touches` field.
- Source/test snapshot identifier (including uncommitted inputs): sha256 of each file under `web/src/app/features/gtd/{data,ui}` (`*.ts`, `*.html`), taken before and after the run with identical results. Key hashes: `context-store.spec.ts` `def358a9...`, `project-store.spec.ts` `5569ecaa...` (both uncommitted), `list-store.ts` `fe1ba588...`, `context-store.ts` `5957a78d...`, `project-store.ts` `c9d2c801...`, `task-store.ts` `1abe1dcc...`. HEAD did not change during the run.
- Exact included files, all under `web/src/app/features/gtd/`: `data/context-store.ts`, `data/dates.ts`, `data/list-store.ts`, `data/project-store.ts`, `data/task-store.ts`, `ui/context-chips.ts`, `ui/labels.ts`, `ui/pickers.ts`, `ui/segmented.ts`, `ui/task-row.ts`, `ui/task-row.html`. Excluded: `data/index.ts` and `ui/index.ts` (re-export barrels with no executable code; not in the v8 report), `*.scss` (styles) and `*.spec.ts`. No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8`. Command `npm run coverage`, run in `web/`, on the full suite (41 files, 632 tests).
- Run start/completion time and exit status: started 2026-10-20T21:24:14+02:00, took about 17 s, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256 `4e8e42958673f0265fda4e4aa499419aab3f3275ce154d29f1f790a28bb14a74`) and `web/coverage/web/coverage-summary.json` (sha256 `eb1845fc723b0e7a00dda4ec9ea9c65e219e4a36aa57cdfd92a419aceed69998`). Both were rewritten by this run; the hashes differ from the first pass, where they were `8a58027b...` and `bf3e57fe...`.
- Evidence outputs are fresh and inputs stayed unchanged: yes (the snapshot hashes and HEAD were the same before and after the run).
- Final run after latest test additions and production fixes: yes. The run measured the code after the CR-1/CR-2 fixes, together with this pass's 4 new tests.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/data/context-store.ts` | 22/22 (100%) | 8/9 (88.9%) | 13/13 (100%) | 17/17 (100%) |
| `web/src/app/features/gtd/data/dates.ts` | 5/5 (100%) | 1/1 (100%) | 2/2 (100%) | 5/5 (100%) |
| `web/src/app/features/gtd/data/list-store.ts` | 125/125 (100%) | 51/52 (98.1%) | 34/34 (100%) | 93/93 (100%) |
| `web/src/app/features/gtd/data/project-store.ts` | 37/37 (100%) | 28/31 (90.3%) | 16/16 (100%) | 29/29 (100%) |
| `web/src/app/features/gtd/data/task-store.ts` | 65/65 (100%) | 71/74 (95.9%) | 21/21 (100%) | 51/51 (100%) |
| `web/src/app/features/gtd/ui/context-chips.ts` | 28/28 (100%) | 12/13 (92.3%) | 4/4 (100%) | 19/19 (100%) |
| `web/src/app/features/gtd/ui/labels.ts` | 14/14 (100%) | 6/8 (75%) | 5/5 (100%) | 10/10 (100%) |
| `web/src/app/features/gtd/ui/pickers.ts` | 37/37 (100%) | 38/41 (92.7%) | 7/7 (100%) | 25/25 (100%) |
| `web/src/app/features/gtd/ui/segmented.ts` | 21/21 (100%) | 13/13 (100%) | 3/3 (100%) | 15/15 (100%) |
| `web/src/app/features/gtd/ui/task-row.ts` | 38/38 (100%) | 24/25 (96%) | 11/11 (100%) | 30/30 (100%) |
| `web/src/app/features/gtd/ui/task-row.html` | 52/52 (100%) | 14/14 (100%) | 2/2 (100%) | 28/28 (100%) |
| **Aggregate (summed counters, 11 files)** | 444/444 (100%) | 266/281 (94.7%) | 118/118 (100%) | 322/322 (100%) |

Aggregate exclusions: the two barrel `index.ts` files (no executable code; not in the report). No scoped file is missing. The counters differ from the first pass (439 statements, 287 branches) because the fixes changed the code: `ifMatch` lost its `max(...)`/`?? 0` branches, and `ensureLoaded` gained a `pendingLoad` field and a `finally` callback.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `data/list-store.ts:183:57` | `: other` arm of the map in `accept`, which leaves the other items unchanged | Justified gap: this is the identity case, only reached when several items exist while one is replaced. The behavior is exercised indirectly. |
| `data/context-store.ts:50`; `data/project-store.ts:58,73,75`; `data/task-store.ts:52,91,109` | `current ?? item` fallback when the edited item is not in the store's list | Low risk: the fallback only matters for edits of items that are not shown. It was left as in the first pass. |
| `ui/labels.ts:25,29` | `?? energy` / `?? creativity` fallback for an enum value without a label | Justified gap: unreachable with the generated types. |
| `ui/pickers.ts:21,40,62`; `ui/context-chips.ts:37`; `ui/task-row.ts:27` | Angular compiler-generated input/model metadata branches | Not behavior: generated glue. |

## Unable to test

- Nothing within the acceptance criteria. The real SSE transport and the generated HTTP client are replaced by fakes in unit tests, following the existing test pattern. The real stack is tested by other tasks' e2e suites.
- One remaining edge case named in `fixes.md` is left untested on purpose: a change event triggers `refresh()` while the first `ensureLoaded` load is running, and `ensureLoaded` then resolves when its own (superseded) request settles, not when the newer one does. A test would encode behavior that the fixer documented as a known limitation rather than a requirement. Whether to address it is the parent worker's decision.

## Summary

Tests added and all validation passed. The CR-1 and CR-2 fixes are adequately tested, task-scoped coverage is sufficient, and no production defects were found.
