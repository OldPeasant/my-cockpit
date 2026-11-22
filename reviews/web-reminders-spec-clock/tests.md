# Test Report

Date: 2026-11-22

Task: web-reminders-spec-clock - Web: task-reminders.spec.ts fails since 2026-11-21 (fixture dates in the past, real clock)

Scope: base 710ea397161000c8b5ac41c1450c9d59dda54673 .. task code commit 7e4c276946c95f856cd63c9e17c708e5f62ad6f5 (measured at HEAD 09443ad7945364bd908fd25d37925cebb761b81b, which adds only `reviews/` on top). Changed file: `web/src/app/features/gtd/task-detail/task-reminders.spec.ts` (test only). No production code changed. Working tree clean apart from this report.

Requirements:
- `tasks/web-reminders-spec-clock.md` acceptance criteria
- AGENTS.md / docs/ARCHITECTURE.md test conventions

## Test files changed

- None by the test-writer (the task commit itself changed `web/src/app/features/gtd/task-detail/task-reminders.spec.ts`).

## Tests added or updated

Count: 0

Reason: the task is a test-only fix. Review of the spec for remaining real-clock dependencies:

- `describe('TaskReminders')`: `beforeEach` now fakes only `Date` (`vi.useFakeTimers({ toFake: ['Date'] })`) and sets it to the fixed `now` (2026-11-10 12:00 local) before `TestBed.createComponent`, so the `presets` computed (evaluated on the first render, before `component.now` is replaced in `render()`) and every `add()` past-time check see the fixed time. `afterEach` restores real timers, so the fake clock does not leak.
- `setTimeout` (used by `settle()`) is deliberately not faked, so the async test flow is unchanged.
- `describe('reminder presets')` (pure functions) passes an explicit `now` to `availablePresets` and only builds dates from explicit components; no `new Date()` without arguments, `Date.now` or `performance.now` appears anywhere in the spec (grep).
- Production code reads the clock only through `TaskReminders.now` (`task-reminders.ts:112`, defaulting to `new Date()`), which is covered by both the fake `Date` and the `component.now` override.

Conclusion: the spec is independent of the real date. A far-future real-date simulation (e.g. libfaketime) was not run: it is redundant by construction, since `vi.setSystemTime` replaces the real time for all `Date` reads within each component test, and the remaining block passes `now` explicitly. No additional test would add value without duplicating existing ones (the preset chip tests already fail if presets are hidden).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: spec no longer depends on the real date; the 4 named tests pass | whole `task-reminders.spec.ts` (43 tests), incl. "shows the error when adding or loading fails", "does not let a reloading list bring back a reminder removed meanwhile", "offers a retry instead of claiming there are none when loading fails", "does not reload while an add after a failed load is pending" | `npm --prefix web run test:ci -- --include src/app/features/gtd/task-detail/task-reminders.spec.ts` (real date 2026-11-22, i.e. after the fixture anchors) | Pass (1 file, 43 tests) |
| AC2: `cd web && npm run check` passes | full web lint + build + unit tests | `npm --prefix web run check` | Pass (196 files, 4923 tests; build prints the pre-existing bundle-budget WARNING only) |
| AC2: `sh scripts/check.sh` passes | all parts | `sh scripts/check.sh` | Not run: the command was denied by the sandbox permission policy for this helper; the parent must run it |

## Focused test execution

Command:

`npm --prefix web run test:ci -- --include src/app/features/gtd/task-detail/task-reminders.spec.ts`

Result:

Pass - 1 test file, 43 tests passed (run 2026-11-22 ~09:11 UTC).

## Full test execution

Command:

`npm --prefix web run check`

Result:

Pass - 196 test files, 4923 tests passed; lint and build completed (only the pre-existing initial bundle budget warning).

## .taskcheck

Result:

Not run (`sh scripts/check.sh` was denied by the permission policy in this helper session). Parent worker must run it.

## Bugs found

None.

## Coverage

Status: not applicable - the task changes no production files, so the task coverage scope (task-changed production files) is empty.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no production lines changed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-reminders-spec-clock; base 710ea397161000c8b5ac41c1450c9d59dda54673; HEAD 09443ad7945364bd908fd25d37925cebb761b81b; task code commit 7e4c276946c95f856cd63c9e17c708e5f62ad6f5 (from the parent and `git show --stat`).
- Source/test snapshot identifier (including uncommitted inputs): no uncommitted source/test changes; `task-reminders.spec.ts` sha256 477b74658d87ffa0da42ba3ca34255ea35a0f4ae87b2987035d0d909970febfe.
- Exact included files; renamed/deleted/excluded files and reasons: included production files: none. Excluded: `web/src/app/features/gtd/task-detail/task-reminders.spec.ts` (test file).
- Provider/version, command, working directory, and test selection: no coverage run (empty scope).
- Run start/completion time and exit status: N/A.
- Generated report paths and report hashes/run identifier: N/A.
- Evidence outputs are fresh and inputs stayed unchanged: N/A.
- Final run after latest test additions and production fixes: N/A (no coverage run; focused and web suites ran on the final snapshot above).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production files in scope) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| none | - | test-only change; no production code to measure |

## Unable to test

- `sh scripts/check.sh` (AC2, second half): denied by the helper's permission policy; needs the parent to run it.
- Far-future real-clock simulation: not run (no libfaketime check possible here); not needed because the spec fixes `Date` itself.

## Summary

No additional tests were necessary for the reviewed scope. Focused spec and `npm --prefix web run check` pass; `.taskcheck` must still be run by the parent.
