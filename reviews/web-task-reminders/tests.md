# Test Report

Date: 2026-11-09

Task: web-task-reminders - Reminders on tasks (web)

Scope: `git diff f1b60527da71148ded53116921e7f7423e613727..25d8f589d80b5f05ab135363020ab83223099656` (task base supplied by the parent; implementation commit 25d8f589d). Measured at HEAD 75a4a8ec712ca56fd40114f66d8a9d1863821d57, which differs from 25d8f589d only in `tasks/web-task-reminders.md`. Branch `main`. Uncommitted inputs: only the two test files edited by this report (hashes below).

Requirements:
- `tasks/web-task-reminders.md` acceptance criteria and its recorded assumption (presets mirror Android: when due, 1 h before due only with a due time, 1 day before due, on the scheduled day; a date without a time reminds at 09:00 local; only future presets; exact time via datetime-local; saved task dates)
- SPEC.md section 4.6 (as referenced by the task), `docs/ARCHITECTURE.md` "Coverage", AGENTS.md
- Parent instructions (test command, no Gradle, prettier + lint)

## Test files changed

- `web/src/app/features/gtd/task-detail/task-detail.service.spec.ts`
- `web/src/app/features/gtd/task-detail/task-reminders.spec.ts`

## Tests added or updated

Count: 23 (4 service tests, 5 preset function tests, 14 component tests). The 14 existing reminder tests and the existing task-detail tests were kept unchanged.

Added to `task-detail.service.spec.ts`:
- lists the reminders of a task silently, by item type TASK and item id
- adds a notification reminder to a task silently
- deletes a reminder silently by its id
- passes reminder errors on to the caller

Added to `task-reminders.spec.ts` (reminder presets):
- does not offer a preset exactly at the current time, only one strictly ahead
- places the day before across a month and year boundary
- keeps the time of day for the day before across a DST change
- places the hour before across midnight
- ignores an empty due time and rejects malformed times

Added to `task-reminders.spec.ts` (TaskReminders):
- adds the exact time with the Enter key
- disables the exact add until a complete date and time is typed
- refuses a reminder exactly at the current time
- lists added reminders in time order
- shows the server message of a failed add
- clears a previous error when the next change succeeds
- disables the controls while a change is pending and ignores a second submit
- removes a reminder from the list before the server answers
- does not add a reminder to another task when the task changed while adding
- shows no error on another task when an add of the previous one fails late
- does not put a reminder back on another task when a remove fails late
- ignores a late answer for the previous task when loading
- ignores a late load failure for the previous task
- clears the typed time and the error when the task changes

Pending and out-of-order responses are controlled with deferred promises in the service fake, never with real delays.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: existing reminders listed (GET by itemType TASK and itemId) | service: lists the reminders of a task silently...; component: lists the existing reminders, earliest first, marking fired ones; says so when there are none; reloads for another task; ignores a late answer / late load failure for the previous task | `npx ng test --watch=false --include='src/app/features/gtd/task-detail/**/*.spec.ts'` | Pass |
| AC1: add from presets relative to due/scheduled date (at time, 1 h, 1 day before, scheduled day, 09:00 default, only future) | preset tests (places each preset..., uses 09:00..., offers only presets..., exactly at the current time, month/year boundary, DST, across midnight, empty/malformed times); component: adds a reminder from a preset...; offers the scheduled day...; lists added reminders in time order | same | Pass |
| AC1: add at an exact date/time (datetime-local), past refused | adds a reminder at an exact local date and time; adds the exact time with the Enter key; disables the exact add until a complete date and time is typed; refuses a time that has passed; refuses a reminder exactly at the current time | same | Pass |
| AC1: POST body (itemType TASK, channel NOTIFICATION, ISO instant) | service: adds a notification reminder to a task silently | same | Pass |
| AC1: remove reminders (DELETE, 404 counts as removed, rollback on failure) | service: deletes a reminder silently by its id; component: removes a reminder, and treats one already gone as removed; puts a reminder back...; removes a reminder from the list before the server answers | same | Pass |
| AC1: errors shown, no double submit, no cross-task leakage | shows the error when adding or loading fails; shows the server message of a failed add; clears a previous error...; disables the controls while a change is pending...; the three "task changed" tests; clears the typed time and the error when the task changes; service: passes reminder errors on to the caller | same | Pass |
| AC1: section present in task detail | TaskDetail spec (existing, extended by the implementation): `app-task-reminders` rendered | same | Pass |
| AC2: reminder fires as a browser notification and opens the task | none (handled by unchanged `core/notifications`) | none | Not run: needs a browser with notification permission and a running stack; manual verification is the parent's decision |
| AC3: English and German strings | component tests assert the English texts; German translations not exercised by unit tests | none | Not run: the parent should run `npm run build:de` (fails on a missing translation) |
| AC4: unit tests cover the new behavior; `sh scripts/check.sh` passes | this report; full web suite and lint below | `npm --prefix web run coverage`; `npx ng lint --max-warnings=0` | Pass (web part); `scripts/check.sh` Not run: it includes Gradle, which the parent told this helper not to run |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/gtd/task-detail/**/*.spec.ts'`

Result:

Pass: 8 files, 362 tests passed (task-reminders.spec.ts 33 tests).

Lint: `cd web && npx ng lint --max-warnings=0` -> "All files pass linting." Prettier run on both edited test files.

## Full test execution

Command:

`npm --prefix web run coverage` (full web Vitest suite with coverage)

Result:

Pass: 194 files, 4620 tests passed, exit 0. Server and Android suites not run (Gradle excluded by the parent; the task changes no server or Android code).

## .taskcheck

Result:

Not run: `.taskcheck` is `sh scripts/check.sh`, which runs Gradle; the parent instructed this helper not to run Gradle. The parent runs it.

## Bugs found

None. All new tests pass against the implementation.

## Coverage

Status: verified (web part, task-changed TypeScript files).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no changed-line tooling configured). Both new files are entirely task code, so their whole-file numbers equal their changed-code numbers.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-task-reminders; base f1b60527da71148ded53116921e7f7423e613727 (from the parent); implementation 25d8f589d80b5f05ab135363020ab83223099656; measured HEAD 75a4a8ec712ca56fd40114f66d8a9d1863821d57 (only a tasks/ file differs); file list from `git diff --stat f1b60527..25d8f589d` matching the parent's list.
- Source/test snapshot identifier (including uncommitted inputs): sha256 task-reminders.ts 73659f54335376bd4d39c0560b9a2c081c8c11d1fdfb9d8024f24cd9a49c97a8; task-detail.service.ts ffbf1dc066a7c148d6da352a99f1f9c24f20b114cc2c66e4d583b8498799c754; task-reminders.spec.ts 1bbc283e1f8b8bba84dbcb3b933a7278fe5726ef0af40438881c2b2096f6653a; task-detail.service.spec.ts a0e9445b00722156739d2134c555931271bfa67dd9b9ac154deaee564202a87e. Identical before and after the run.
- Exact included files: `web/src/app/features/gtd/task-detail/task-reminders.ts`, `task-detail.service.ts`, `task-detail.ts`. Excluded: `task-reminders.html` and `task-detail.html` (templates, exercised through the component tests but not instrumented separately), `task-reminders.scss`, `controls.scss` (styles), `web/src/locale/*.xlf` (translation data), spec files. No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8` via `npm --prefix web run coverage` (`ng test --watch=false --coverage` with text-summary, json-summary, json and lcov reporters); working directory web/; full web suite.
- Run start/completion time and exit status: started 2026-11-09 00:41:15 +01:00, about 47 s test duration, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 e6b050a74c791558157e3ba0dff99466fde564027584315cb02ea1b87a9feee3; `web/coverage/web/coverage-final.json` sha256 055ba78a3073a7fd818590d864897105a676f3f8c70391b5d65816a2660a9720 (both rewritten by this run).
- Evidence outputs are fresh and inputs stayed unchanged: yes (report mtimes after the run start, source/test hashes unchanged).
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/task-detail/task-reminders.ts` | 105/105 (100%) | 65/67 (97.01%) | 28/28 (100%) | 86/86 (100%) |
| `web/src/app/features/gtd/task-detail/task-detail.service.ts` | 20/20 (100%) | 8/8 (100%) | 16/16 (100%) | 18/18 (100%) |
| `web/src/app/features/gtd/task-detail/task-detail.ts` | 303/307 (98.69%) | 202/215 (93.95%) | 64/64 (100%) | 248/248 (100%) |

Aggregate over the three files (summed counters): statements 428/432 (99.07%), branches 275/290 (94.83%), functions 108/108 (100%), lines 352/352 (100%). Excluded: templates, styles and locale files listed above.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `task-reminders.ts:46` (the `null` arm of `Number.isNaN(result.getTime())`) | Defensive: digits matched by the regexes always give a valid Date, so this arm is unreachable | Justified gap, no test |
| `task-reminders.ts:103` (an `if` branch reported on the class line) | Compiler-generated code from the Angular component compilation, no source branch | Justified gap, no test |
| `task-detail.ts:94, 156, 170, 199, 231, 262, 266, 317, 391, 408, 423, 428` | Code that existed before this task; the task only added the `TaskReminders` import and its entry in `imports` (both covered) | Out of task scope, no test |

## Unable to test

- AC2 (the reminder fires as a browser notification and opens the task): needs a real browser, notification permission and the server's reminder scheduler. `core/notifications` is unchanged. Follow-up: manual check or skip with reason by the parent.
- German translations (AC3): not checked by unit tests. Follow-up: the parent runs `npm run build:de`.
- `sh scripts/check.sh` (.taskcheck): not run because it includes Gradle. Follow-up: the parent runs it.
- Note, not a defect: `localDateTime` accepts out-of-range parts such as month 13 (JavaScript Date rolls them over). Inputs come from the datetime-local input and from server dates, so this is not reachable through the UI and no test was added.

## Summary

Tests added and all validation passed (focused task-detail tests, full web suite with coverage, lint). No production defects found. `.taskcheck` and AC2 remain for the parent.
