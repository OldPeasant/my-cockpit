# Test Report

Date: 2026-11-09

Task: review-reminder-web - Weekly review reminder control in web settings

Scope: base 8c540b53290c779efb7947fd75d40196cd44c20f .. HEAD c7a216b19e953be99c4b0a4b6e509808e6ca98d8 (single task commit c7a216b19, supplied by the parent worker), plus the uncommitted test additions listed below.

Requirements:
- tasks/review-reminder-web.md acceptance criteria
- Parent worker's description of the setting format (`off` or `<DAY>@HH:mm`, server setting `weeklyReviewReminder`)
- docs/ARCHITECTURE.md "Coverage"; existing conventions in general-settings.spec.ts

## Test files changed

- `web/src/app/features/settings/general/general-settings.spec.ts`

## Tests added or updated

Count: 5 (added in `describe('weekly review reminder')`; no existing test changed)

- `shows an unknown stored value as off and saves a picked day with the default time`
- `keeps the chosen time while off and uses it when switched on again`
- `does not save a time changed back to the stored one`
- `keeps the second day picked while the first save is still running`
- `saves the stored day again when it is picked back while a save is running`

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: control in settings/general, unit tests for load | `shows a stored day and time`, `shows off without a time field when the reminder is off or unset`, `shows an unknown stored value as off and saves a picked day with the default time` | `npx ng test --watch=false --include src/app/features/settings/general/general-settings.spec.ts` | Pass (46 tests) |
| AC1: save off | `saves off`, `keeps the chosen time while off and uses it when switched on again` | same | Pass |
| AC1: save day+time | `saves a day with the default time, then a changed time`, `keeps the chosen time while off ...`, `does not save an empty time`, `does not save a time changed back to the stored one` | same | Pass |
| AC1: refused / overlapping saves (robustness) | `puts the stored reminder back when the server refuses the save`, `keeps the second day picked while the first save is still running`, `saves the stored day again when it is picked back while a save is running` | same | Pass |
| AC2: WEEKLY_REVIEW notification navigates to /review | `weekly review reminder > opens the weekly review when its browser notification is clicked`, `... opens the weekly review from the toast while notifications are off` (written by the implementer) | `npx ng test --watch=false --include src/app/features/settings/general/general-settings.spec.ts --include src/app/core/notifications/reminder-notifications.service.spec.ts` | Pass (89 tests in 2 files) |
| AC3: EN/DE strings; `npm run check` passes | Whole web suite and lint | `cd web && npm run check` | Pass (ng lint --max-warnings=0 clean; 196 files, 4765 tests passed). The xlf content itself was not checked string by string. |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/features/settings/general/general-settings.spec.ts --include src/app/core/notifications/reminder-notifications.service.spec.ts`

Result:

Pass (2 files, 89 tests). After the last edit, general-settings.spec.ts alone: Pass (46 tests). `npx eslint` and `npx prettier --check` on the spec: clean.

## Full test execution

Command:

`cd web && npm run check`

Result:

Pass (lint clean, 196 test files, 4765 tests)

## .taskcheck

Result:

Not run (`scripts/check.sh` also runs server and Android; the task only touches web, and `npm run check` for web passed. The parent runs `.taskcheck` before `done`.)

## Bugs found

None.

## Coverage

Status: verified (whole-file, web part)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: review-reminder-web; base 8c540b53290c779efb7947fd75d40196cd44c20f; HEAD c7a216b19e953be99c4b0a4b6e509808e6ca98d8; task commit and file list supplied by the parent and confirmed with `git show --stat HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD c7a216b19 plus uncommitted `general-settings.spec.ts` sha256 261b5871b47d73bee9219787fa043cd96000591262f66192183d7c45e2a80a79; `general-settings.ts` sha256 699917fab875d52d20ce34d36e657e1367bdaa0b718a4c623734b38f2259150c (unchanged from HEAD).
- Exact included files: `web/src/app/features/settings/general/general-settings.ts`. Not in the metric: `general-settings.html` (compiled into the component; no separate counters), `general-settings.scss` and `web/src/locale/*.xlf` (not executable), the two `*.spec.ts` files (tests). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8, `npm run coverage`, working directory `web/`, full web suite.
- Run start/completion time and exit status: final run completed 2026-11-09T04:36:06+01:00 (report mtime); 196 files / 4765 tests passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 667cd10ecd1e14491bec73fef0d5d9b0bf6a5986f3aa8018d034bd14c4fe51f0, `web/coverage/web/coverage-summary.json`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report was rewritten by this run (new mtime and hash compared with the earlier run, acc84a74...), and no source or test file changed after the run.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage), taken from coverage-summary.json.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/settings/general/general-settings.ts` | 165/166 (99.39%) | 85/92 (92.39%) | 35/35 (100%) | 138/138 (100%) |

Before the added tests: statements 164/166, branches 84/92. The first test run left the "value unchanged" early return in `saveReminder` (line 265) uncovered, and `does not save a time changed back to the stored one` now covers it.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `general-settings.ts:333` (fallback branch to `DEFAULT_REMINDER_TIME` when the kept time is invalid) | Reminder row: the user empties the time and then picks Off or gets a refused save, and the default time comes back | Gap left open: an edge of an edge, low risk. A test would need an invalid time plus a reset. |
| `general-settings.ts:207, 208, 250, 287, 299` | Time zone autocomplete, pending counter defaults, time zone blur without settings, duplicate sender removal guard | Code from before this task, outside its scope |
| `general-settings.ts:111` | Branch emitted by the decorator/class compile | Not meaningful |

## Unable to test

- The German translations in `messages.de.xlf` were not reviewed or rendered in German by these tests. Only `npm run check` (lint + unit tests) was run.
- The server's handling of `weeklyReviewReminder` belongs to review-reminder-server and is out of scope.

## Summary

Tests added and all validation passed.
