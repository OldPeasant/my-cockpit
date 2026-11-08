# Test Report

Date: 2026-11-09

Task: web-task-reminders - Reminders on tasks (web)

Scope: `git diff f1b60527da71148ded53116921e7f7423e613727..69f24631e4aa64fcb2ecb8e76db70438e1a5e99f` (task base and final revision supplied by the parent). Re-run on the final code after the fixer commit 69f24631e (CR-1 loadState/locked gating, CR-2 Retry after a failed load, see `reviews/web-task-reminders/fixes.md`). Branch `main`. Uncommitted inputs: only `web/src/app/features/gtd/task-detail/task-reminders.spec.ts`, edited by this report (hash below). `reviews/web-task-reminders/review.md` was also modified in the working tree during this run, not by this helper; it is not a test input.

Requirements:
- `tasks/web-task-reminders.md` acceptance criteria and recorded assumptions
- `reviews/web-task-reminders/review.md` findings CR-1, CR-2, CR-3 and `fixes.md` (the behavior the fix promises: no stale load overwrites a change, no "No reminders." after a failed load, Retry stays until the list has loaded)
- `docs/ARCHITECTURE.md` "Coverage", AGENTS.md, parent instructions (no Gradle, lint + prettier, braces instead of angle brackets)

## Test files changed

- `web/src/app/features/gtd/task-detail/task-reminders.spec.ts`

## Tests added or updated

Count: 5 added in this round (the service fake also records each load call in a new `loads` array). Earlier rounds: 23 by the first test-writer run, 3 by the fixer; all kept unchanged.

Added to `task-reminders.spec.ts` (TaskReminders):
- shows the error again and keeps the retry when the retry fails too
- does not reload while an add after a failed load is pending (covers the `locked` guard in `retry()`)
- drops the retry when switching to a task whose list loads
- ignores a late answer from an earlier visit of the same task (FAILS, TW-1)
- ignores a late load failure from an earlier visit of the same task (FAILS, TW-1)

Pending and out-of-order responses are controlled with deferred promises, never with real delays.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: existing reminders listed (GET by itemType TASK and itemId) | service: lists the reminders of a task silently...; component: lists the existing reminders...; says so when there are none; reloads for another task; ignores a late answer / late load failure for the previous task | `npm --prefix web run test:ci -- --include='src/app/features/gtd/task-detail/**/*.spec.ts' --include='src/locale/**/*.spec.ts'` | Pass |
| AC1: add from presets / exact time, past refused, POST body | preset function tests; adds a reminder from a preset...; offers the scheduled day...; adds a reminder at an exact local date and time; Enter key; refuses a time that has passed / exactly now; service: adds a notification reminder... | same | Pass |
| AC1: remove reminders (DELETE, 404 counts as removed, rollback on failure) | removes a reminder, and treats one already gone as removed; puts a reminder back...; removes a reminder from the list before the server answers; service: deletes a reminder... | same | Pass |
| CR-1: a load cannot overwrite an add or remove (controls locked while loading) | keeps the controls disabled until the list has loaded...; does not let a reloading list bring back a reminder removed meanwhile; does not reload while an add after a failed load is pending | same | Pass |
| CR-1 (same class): a load of an earlier visit of the same task must not overwrite the current visit | ignores a late answer from an earlier visit of the same task; ignores a late load failure from an earlier visit of the same task | same | Fail (TW-1) |
| CR-2: no "No reminders." after a failed load; Retry until loaded; retry failing again | offers a retry instead of claiming there are none when loading fails; shows the error again and keeps the retry when the retry fails too; drops the retry when switching to a task whose list loads | same | Pass |
| CR-3: stale responses after a task switch | the five "previous task" tests from the first round | same | Pass |
| AC2: reminder fires as a browser notification and opens the task | none (unchanged `core/notifications`) | none | Not run: needs a browser with notification permission and the server scheduler; the parent skipped it with a reason |
| AC3: English and German strings (incl. new `taskDetail.reminder.retry`) | `src/locale/messages-de.spec.ts` (unit per message, placeholders kept); component tests assert the English texts | same | Pass |
| AC4: unit tests cover the new behavior; `sh scripts/check.sh` passes | this report | `npm --prefix web run coverage`; `npm --prefix web run lint` | Fail: 2 web tests fail (TW-1); lint Pass; `scripts/check.sh` Not run (includes Gradle, excluded by the parent) |

## Focused test execution

Command:

`npm --prefix web run test:ci -- --include='src/app/features/gtd/task-detail/**/*.spec.ts' --include='src/locale/**/*.spec.ts'`

Result:

Fail: 9 files, 380 tests, 378 passed, 2 failed (the two TW-1 tests). Note: running the same includes via `npm --prefix web exec -- ng test ...` from the repository root makes `src/locale/messages-de.spec.ts` fail with ENOENT, because it resolves the locale folder from the working directory; the package script runs inside `web/` and the locale spec passes there.

Lint: `npm --prefix web run lint` -> "All files pass linting." Prettier: `npm --prefix web exec -- prettier --write web/src/app/features/gtd/task-detail/task-reminders.spec.ts` -> unchanged.

## Full test execution

Command:

`npm --prefix web run coverage` (full web Vitest suite with coverage)

Result:

Fail: 194 files, 4628 tests, 4626 passed, 2 failed (the two TW-1 tests), exit 1. Server and Android not run (Gradle excluded by the parent; the task changes no server or Android code).

## .taskcheck

Result:

Not run: `.taskcheck` is `sh scripts/check.sh`, which runs Gradle; the parent told this helper not to run Gradle. With the two TW-1 tests it would fail in the web part.

## Bugs found

### A load from an earlier visit of the same task overwrites the list (ABA on taskId)

Finding ID:

`TW-1`

Test:

`TaskReminders > ignores a late answer from an earlier visit of the same task`; `TaskReminders > ignores a late load failure from an earlier visit of the same task`

Production source:

`web/src/app/features/gtd/task-detail/task-reminders.ts:235-247` (`load()` guards only with `taskId !== this.taskId()`)

Requirement:

The CR-1 fix promises that a late list cannot overwrite changes ("Changes wait for the load, so a late list cannot overwrite them"), and the component handles `taskId` changes itself (its effect reloads on every change). Acceptance criterion 1 (existing reminders listed, add works).

Expected behavior:

When the input switches t1, then t2, then back to t1 while the first t1 load is still pending, only the load started for the current visit settles the list and `loadState`. A late answer from the first visit is ignored, so a reminder added after the current visit loaded stays in the list. A late failure of the first visit shows no error and no Retry.

Actual behavior:

The first visit's load passes the `taskId` check (it is t1 again). Its success replaces the list with the snapshot from before the add (2 rows become 1). Its failure shows "Something went wrong. Try again." and sets `loadState` to `failed` although the current load succeeded. A per-load sequence number (as `TaskDetail.load` uses `loadSeq`) would close it.

Severity:

Low. Not reachable through the current task detail page: `TaskDetail` sets its state to `loading` on every task switch, which destroys and recreates `TaskReminders` (`task-detail.html` `@switch`, `task-detail.ts:540-543`), so each visit gets a new instance. It breaks the component's own task-switch contract, which matters for any other host or future reuse of the instance.

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.` (The parent may instead decide that the finding is out of scope, given that it cannot happen through the current page.)

## Coverage

Status: partial-failed. The full coverage run (`npm --prefix web run coverage`) exited 1 because of the two TW-1 tests and wrote no coverage report (Vitest does not report on failure; `web/coverage/web/coverage-summary.json` was absent afterwards). The numbers below come from a diagnostic run over the task-detail specs that left out only the two failing tests (via `--filter`). They are diagnostic only, not verified task coverage.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no changed-line tooling configured). `task-reminders.ts` is entirely task code, so its whole-file numbers equal its changed-code numbers; `task-detail.service.ts` gained the three reminder methods.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-task-reminders; base f1b60527da71148ded53116921e7f7423e613727 and final revision 69f24631e4aa64fcb2ecb8e76db70438e1a5e99f from the parent; task commits ef7f2bedf, 358e07dd6, 69f24631e; file list from `git diff --stat f1b60527..69f24631e`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 task-reminders.ts 1adb8e765bc1c670a4952fe06549ebe7bb77a9f87fe733c97dadb08b38596c12; task-reminders.html b99cc263e712e7645ce813b3ad4c8fed7d89054e6dec15039ecbf525c2439b21; task-detail.service.ts ffbf1dc066a7c148d6da352a99f1f9c24f20b114cc2c66e4d583b8498799c754; task-reminders.spec.ts (uncommitted) 8937528e61d917523f56b693d71153295d5ddd8b2cf0007d50b4b1a82fbe0684; task-detail.service.spec.ts a0e9445b00722156739d2134c555931271bfa67dd9b9ac154deaee564202a87e. Identical before and after the runs.
- Exact included files: `web/src/app/features/gtd/task-detail/task-reminders.ts`, `task-detail.service.ts`, `task-detail.ts`. Excluded: `task-reminders.html`, `task-detail.html` (templates, exercised through component tests, not instrumented separately), `task-reminders.scss`, `controls.scss` (styles), `web/src/locale/*.xlf` (translation data, checked by the locale spec), spec files. No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with v8 coverage. Full run: `npm --prefix web run coverage` (cwd web/, all specs) -> exit 1, no report. Diagnostic run: `npm --prefix web run coverage -- --include='src/app/features/gtd/task-detail/**/*.spec.ts' --filter='^(?!.*earlier visit of the same task)'` (cwd web/; 8 files, 368 passed, 2 left out by the filter).
- Run start/completion time and exit status: full run started 2026-11-09 00:48:05 +01:00, about 46 s, exit 1; diagnostic run started 00:49:05 +01:00, about 7 s, exit 0.
- Generated report paths and report hashes/run identifier: diagnostic run `web/coverage/web/coverage-summary.json` sha256 79b1abb46dbba899e3e947d56d9f0b926a27b8218ccb6183cbdb573438bd1114; `web/coverage/web/coverage-final.json` sha256 7d70f70eff9af3e6a3de5b6139fb5031d4947cd1240d56bc3d6fb5862c212bc7.
- Evidence outputs are fresh and inputs stayed unchanged: yes (the full run left no report, so the files above can only come from the diagnostic run; input hashes unchanged).
- Final run after latest test additions and production fixes: yes for the tests (final revision 69f24631e plus this round's tests); a fix for TW-1 would make it stale and needs a fresh full coverage run.

Values below use covered/total (percentage); diagnostic run.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/task-detail/task-reminders.ts` | 117/117 (100%) | 69/71 (97.18%) | 30/30 (100%) | 96/96 (100%) |
| `web/src/app/features/gtd/task-detail/task-detail.service.ts` | 20/20 (100%) | 8/8 (100%) | 16/16 (100%) | 18/18 (100%) |
| `web/src/app/features/gtd/task-detail/task-detail.ts` | 303/307 (98.69%) | 202/215 (93.95%) | 64/64 (100%) | 248/248 (100%) |

Aggregate over the three files (summed counters, diagnostic): statements 440/444 (99.10%), branches 279/294 (94.90%), functions 110/110 (100%), lines 362/362 (100%). Excluded: templates, styles and locale files listed above. No scoped file was missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `task-reminders.ts:46` (the `null` arm of `Number.isNaN(result.getTime())`) | Defensive: digits matched by the regexes always give a valid Date | Justified gap, no test |
| `task-reminders.ts:103` (an `if` branch reported on the class decorator line) | Compiler-generated code from the Angular component compilation, no source branch | Justified gap, no test |
| `task-reminders.ts:235-247` (`load()`), all branches covered | Covered, but the guard is incomplete for a same-task revisit | TW-1 (two failing tests) |
| `task-detail.ts:94, 156, 170, 199, 231, 262, 266, 317, 391, 408, 423, 428` | Code from before this task; the task only added the `TaskReminders` import and its `imports` entry (covered) | Out of task scope, no test |

## Unable to test

- AC2 (the reminder fires as a browser notification and opens the task): needs a real browser, notification permission and the server scheduler; `core/notifications` is unchanged. The parent skipped it with a reason.
- `sh scripts/check.sh` (.taskcheck): not run because it includes Gradle. Follow-up: the parent runs it after TW-1 is resolved.
- Verified task coverage: blocked by the failing TW-1 tests (no report on failure). Follow-up: rerun `npm --prefix web run coverage` after the fix.
- Observation, not a defect: the Retry button stays enabled while an add is pending after a failed load, and a click then does nothing (the `locked` guard). Harmless, so no test asserts a disabled state.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion. TW-1 (Low, not reachable through the current task detail page) makes two new tests fail; the CR-1 and CR-2 fixes themselves are covered and pass. Lint and prettier pass.
