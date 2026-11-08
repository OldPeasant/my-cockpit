# Test Report

Date: 2026-11-09

Task: web-task-reminders - Reminders on tasks (web)

Scope: `git diff f1b60527da71148ded53116921e7f7423e613727..2274f57a7f14e097f1aaa50e8b1790994fb7e1af` (task base and final revision supplied by the parent). Final re-test after the round-2 fixer commit 2274f57a7 (TW-1/CR-1: per-load sequence number in `TaskReminders.load()`; CR-2: Retry disabled while `locked()`, see `reviews/web-task-reminders/fixes.md` "round 2"). Task commits: ef7f2bedf, 358e07dd6, 69f24631e, 428d572f0, 2274f57a7. Branch `main`. Uncommitted inputs: only `web/src/app/features/gtd/task-detail/task-reminders.spec.ts`, edited by this report (hash below). `reviews/web-task-reminders/review.md` was also modified in the working tree during this run, not by this helper. It is not a test input.

Requirements:
- `tasks/web-task-reminders.md` acceptance criteria and recorded assumptions
- `reviews/web-task-reminders/fixes.md` round 2 (TW-1, CR-1, CR-2) and `review.md`
- `docs/ARCHITECTURE.md` "Coverage", AGENTS.md, parent instructions (no Gradle, lint, braces instead of angle brackets)

## Test files changed

- `web/src/app/features/gtd/task-detail/task-reminders.spec.ts`

## Tests added or updated

Count: 2 added in this round. All earlier tests are unchanged: the first run, the fixer's tests, the previous re-test round, and the fixer's round-2 assertion that Retry is disabled while an add is pending.

Added to `task-reminders.spec.ts` (TaskReminders):
- keeps the controls locked when an earlier visit answers before the current load: t1 -> t2 -> t1, and both t1 loads are pending. The first visit's load answers first. The chips stay disabled and no list and no "No reminders." are shown. The list appears and the chips unlock only after the current visit's load answers. This fails with the pre-fix `taskId` guard and passes with `loadSeq`.
- ignores a retry of the previous task that fails after switching task: a Retry load for t1 is pending, the user switches to t2 (which loads), and then the t1 retry fails. t2 shows no error, no Retry, and keeps its own list. This covers a load started by `retry()` rather than by the task-switch effect.

The existing tests already cover the round-2 fixes:
- TW-1/CR-1: "ignores a late answer from an earlier visit of the same task" and "ignores a late load failure from an earlier visit of the same task". Both failed before the fix and pass now.
- CR-2: "does not reload while an add after a failed load is pending". It checks that Retry is disabled while the add is pending, that a click causes no reload, and that Retry is enabled again afterwards.

Out-of-order responses are controlled with deferred promises. The tests use no real delays.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: existing reminders listed (GET by itemType TASK and itemId) | service: lists the reminders of a task silently...; component: lists the existing reminders...; says so when there are none; reloads for another task | `npm --prefix web run test:ci -- --include='src/app/features/gtd/task-detail/**/*.spec.ts' --include='src/locale/**/*.spec.ts'` | Pass |
| AC1: add from presets / exact time, past refused, POST body | preset function tests; adds a reminder from a preset...; offers the scheduled day...; adds a reminder at an exact local date and time; Enter key; refuses a time that has passed / exactly now; service: adds a notification reminder... | same | Pass |
| AC1: remove reminders (DELETE, 404 counts as removed, rollback on failure) | removes a reminder, and treats one already gone as removed; puts a reminder back...; removes a reminder from the list before the server answers; service: deletes a reminder... | same | Pass |
| CR-1: a load cannot overwrite an add or remove (controls locked while loading) | keeps the controls disabled until the list has loaded...; does not let a reloading list bring back a reminder removed meanwhile | same | Pass |
| TW-1 / CR-1 round 2: only the latest load applies, also on a revisit of the same task | ignores a late answer from an earlier visit of the same task; ignores a late load failure from an earlier visit of the same task; keeps the controls locked when an earlier visit answers before the current load (new); ignores a retry of the previous task that fails after switching task (new) | same | Pass |
| CR-2 round 2: Retry disabled while locked | does not reload while an add after a failed load is pending | same | Pass |
| CR-2: no "No reminders." after a failed load; Retry until loaded | offers a retry instead of claiming there are none...; shows the error again and keeps the retry when the retry fails too; drops the retry when switching to a task whose list loads | same | Pass |
| CR-3: stale add/remove/load responses after a task switch | the five "previous task" tests | same | Pass |
| AC2: reminder fires as a browser notification and opens the task | none (`core/notifications` is unchanged) | none | Not run: needs a browser with notification permission and the server scheduler. The parent skipped it with a reason |
| AC3: English and German strings | `src/locale/messages-de.spec.ts` (one unit per message, placeholders kept); component tests assert the English texts | same | Pass |
| AC4: unit tests cover the new behavior; `sh scripts/check.sh` passes | this report | `npm --prefix web run coverage`; `npm --prefix web run lint` | Web suite Pass (4630 tests), lint Pass; `scripts/check.sh` Not run (it includes Gradle, which the parent excluded) |

## Focused test execution

Command:

`npm --prefix web run test:ci -- --include='src/app/features/gtd/task-detail/**/*.spec.ts' --include='src/locale/**/*.spec.ts'`

Result:

Pass: 9 files, 382 tests passed (380 before this round, plus 2 new).

Lint: `npm --prefix web run lint` -> "All files pass linting." Prettier: `npm --prefix web exec -- prettier --check web/src/app/features/gtd/task-detail/task-reminders.spec.ts` -> "All matched files use Prettier code style!"

## Full test execution

Command:

`npm --prefix web run coverage` (full web Vitest suite with coverage)

Result:

Pass: 194 files, 4630 tests passed, exit 0. Server and Android were not run: the parent excluded Gradle, and the task changes no server or Android code.

## .taskcheck

Result:

Not run: `.taskcheck` is `sh scripts/check.sh`, which runs Gradle, and the parent told this helper not to run Gradle. Its web part (lint, unit tests) passed in the runs above.

## Bugs found

None in this round. TW-1 from the previous round is fixed: both of its tests pass, and the new revisit test passes.

## Coverage

Status: verified (fresh full run, exit 0, final revision plus this round's tests).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured, because no changed-line tooling is configured. `task-reminders.ts` is all task code, so its whole-file numbers equal its changed-code numbers. `task-detail.service.ts` gained the three reminder methods.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-task-reminders. Base f1b60527da71148ded53116921e7f7423e613727 and HEAD 2274f57a7f14e097f1aaa50e8b1790994fb7e1af come from the parent. Task commits: ef7f2bedf, 358e07dd6, 69f24631e, 428d572f0, 2274f57a7. File list from `git diff --stat f1b60527..2274f57a7`.
- Source/test snapshot identifier (including uncommitted inputs), sha256: task-reminders.ts 926c25151a285a976a73b203946e556402d4fc632b4bdec19334f4495f6b0ec1; task-reminders.html 9c48586fd70bf8fd50ef9883af0712f79875f76d468be14a20cb2fb99222e803; task-detail.service.ts ffbf1dc066a7c148d6da352a99f1f9c24f20b114cc2c66e4d583b8498799c754; task-detail.ts 29345b268cd5424a2432839f9099cd8852713f8c39a5a0d8ddae9a1c5a62b6cf; task-reminders.spec.ts (uncommitted) cbdff4f0561e64ab8ba7b0c23a7ce5fc5ef6d25a8048ee41d1f9621fd3ee6441; task-detail.service.spec.ts a0e9445b00722156739d2134c555931271bfa67dd9b9ac154deaee564202a87e; task-detail.spec.ts 7a8fdf0b37b364ae5a0e157cb70cfad6fa3b3837cf29908b061e90580fc7b136. The hashes were identical before and after the coverage run.
- Exact included files: `web/src/app/features/gtd/task-detail/task-reminders.ts`, `task-detail.service.ts`, and `task-detail.ts` (the task added only the import and the `imports` entry there). Excluded: `task-reminders.html` and `task-detail.html` (templates, exercised through component tests and not instrumented separately), `task-reminders.scss` and `controls.scss` (styles), `web/src/locale/*.xlf` (translation data, checked by the locale spec), and spec files. No files were renamed or deleted. No scoped file was missing from the report.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8`. `npm --prefix web run coverage` (runs in web/), all specs.
- Run start/completion time and exit status: started 2026-11-09 00:53:18 +01:00, report written 00:54:04 +01:00, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 d769386b6bbc23a3c29089229fbb94daa82d8521c5a1da2a3815017c0d84e8a0; `web/coverage/web/coverage-final.json` sha256 cb7019c99b78933fbbe048a2ec311f5aff290bca4fa2f82e45f1dbce0df17c04.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The previous reports had mtime 00:49:12 and the new ones 00:54:04. The input hashes were unchanged.
- Final run after latest test additions and production fixes: yes (HEAD 2274f57a7, which includes the round-2 fixes, plus the 2 tests added in this round).

Values below use covered/total (percentage), from provider-native counters in `coverage-summary.json`.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/task-detail/task-reminders.ts` | 118/119 (99.15%) | 68/71 (95.77%) | 30/30 (100%) | 98/98 (100%) |
| `web/src/app/features/gtd/task-detail/task-detail.service.ts` | 20/20 (100%) | 8/8 (100%) | 16/16 (100%) | 18/18 (100%) |
| `web/src/app/features/gtd/task-detail/task-detail.ts` | 303/307 (98.69%) | 202/215 (93.95%) | 64/64 (100%) | 248/248 (100%) |

Aggregate over the three files (summed counters): statements 441/446 (98.88%), branches 278/294 (94.56%), functions 110/110 (100%), lines 364/364 (100%). Excluded: the templates, styles and locale files listed above. No scoped file is unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `task-reminders.ts:231` (the `return` of `if (this.locked()) return;` in `retry()`) | Stops a reload over a pending add. Since CR-2 the Retry button is `[disabled]="locked()"`, and a disabled button gets no click, so the template can no longer reach the guard | Justified gap: a defensive guard behind a disabled button. "does not reload while an add after a failed load is pending" asserts that the button is disabled and that no reload happens. Reaching the guard would mean calling the protected method directly, which no spec in this repo does |
| `task-reminders.ts:46` (the `null` arm of `Number.isNaN(result.getTime())`) | Defensive: digits matched by the regexes always give a valid Date | Justified gap, no test |
| `task-reminders.ts:103` (an `if` branch reported on the class decorator line) | Code generated by the Angular component compiler; there is no source branch | Justified gap, no test |
| `task-detail.ts:94, 156, 170, 199, 231, 262, 266, 317, 391, 408, 423, 428` | Code from before this task. The task only added the `TaskReminders` import and `imports` entry, which are covered | Out of task scope, no test |

The previous round's numbers for `task-reminders.ts` (117/117 statements, 69/71 branches) came from a diagnostic run on the pre-fix revision 69f24631e. They are superseded by the numbers above. The small changes come from the round-2 fix (one new statement, `loadSeq`) and from the now-disabled Retry button.

## Unable to test

- AC2 (the reminder fires as a browser notification and opens the task): needs a real browser, notification permission and the server scheduler. `core/notifications` is unchanged, and the parent skipped AC2 with a reason.
- `sh scripts/check.sh` (`.taskcheck`): not run because it includes Gradle. Follow-up: the parent runs it.

## Summary

Tests added and all validation passed. The round-2 fixes (per-load sequence number, Retry disabled while locked) are covered by the existing tests and 2 new ones. The focused run (382), the full web suite with coverage (4630), lint and prettier pass. Task-scoped coverage is verified on the final revision, and the remaining gaps are defensive or out of scope. No production defects were found.
