# Test Report

Date: 2026-11-09

Task: review-reminder-web - Weekly review reminder control in web settings

Scope: re-run on the final code after fixes. Base 8c540b53290c779efb7947fd75d40196cd44c20f .. HEAD 81eee9e3d5decc7f7ec3444f37a4a6a4a2967351. Task-owned commits (web): 5508866e9 (implementation), 945ffd61e (tests), 81eee9e3d (fixes CR-1, CR-2). The range also holds `android/` commits of another task (and-notes-checklist-links), which are excluded. One uncommitted test addition is listed below.

Requirements:
- tasks/review-reminder-web.md acceptance criteria
- reviews/review-reminder-web/review.md (CR-1, CR-2) and reviews/review-reminder-web/fixes.md
- docs/ARCHITECTURE.md "Coverage"; existing conventions in general-settings.spec.ts

## Test files changed

- `web/src/app/features/settings/general/general-settings.spec.ts`

## Tests added or updated

Count: 1 (added; no existing test changed)

- `weekly review reminder > keeps a pending reminder pick when another row finishes saving first`. This covers the `!this.reminderPending` guard that the CR-1 fix added to `save()`. The fixer's spec only covered the no-pending side. Steps: a theme save is in flight, the user picks Monday, then the theme answer arrives and still holds `SUNDAY@18:00`. The row must keep Monday. Then the reminder PATCH `MONDAY@18:00` goes out and is answered.

Adequacy of the fix specs:
- CR-1, `shows the reloaded reminder after another row saves into a 412`: adequate. The 412 reload carries FRIDAY@08:00 and the row must show it. Without the fix the row stays on Sunday.
- CR-2, `asks for a time when the time field is emptied and left`: adequate. It checks that "Enter a time." is absent first and present after the field is emptied and blurred, and that no PATCH is sent.
- The added test closes the remaining gap: the guard's pending side.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: control in settings/general, load | `shows a stored day and time`, `shows off without a time field when the reminder is off or unset`, `shows an unknown stored value as off and saves a picked day with the default time` | `cd web && npx ng test --watch=false --include src/app/features/settings/general/general-settings.spec.ts --include src/app/core/notifications/reminder-notifications.service.spec.ts` | Pass |
| AC1: save off / day+time | `saves off`, `saves a day with the default time, then a changed time`, `keeps the chosen time while off and uses it when switched on again`, `does not save an empty time`, `does not save a time changed back to the stored one` | same | Pass |
| AC1 robustness: refused or overlapping saves | `puts the stored reminder back when the server refuses the save`, `keeps the second day picked while the first save is still running`, `saves the stored day again when it is picked back while a save is running`, `keeps a pending reminder pick when another row finishes saving first` | same | Pass |
| CR-1: reminder row refreshed after another row's 412 reload | `shows the reloaded reminder after another row saves into a 412`, `keeps a pending reminder pick when another row finishes saving first` | same | Pass |
| CR-2: feedback for an emptied time | `asks for a time when the time field is emptied and left` | same | Pass |
| AC2: WEEKLY_REVIEW notification navigates to /review | `weekly review reminder > opens the weekly review when its browser notification is clicked`, `... opens the weekly review from the toast while notifications are off` (reminder-notifications.service.spec.ts) | same | Pass |
| AC3: EN/DE strings; `npm run check` passes | Whole web suite (via `npm run coverage`); lint of the changed spec only | `cd web && npm run coverage`; `npx eslint --max-warnings=0` + `npx prettier --check` on the spec | Pass (196 files, 4768 tests; spec lint clean). I did not run the full `npm run check` (lint + build) myself, and the xlf strings were not checked one by one. |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/features/settings/general/general-settings.spec.ts --include src/app/core/notifications/reminder-notifications.service.spec.ts`

Result:

Pass (2 files, 92 tests)

## Full test execution

Command:

`cd web && npm run coverage` (the full web Vitest suite with coverage)

Result:

Pass (196 test files, 4768 tests)

## .taskcheck

Result:

Not run. `scripts/check.sh` also runs the server and Android checks, and this task only touches web. The parent runs `.taskcheck` before `done`.

## Bugs found

None.

## Coverage

Status: verified (whole-file, web part)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: review-reminder-web; base 8c540b53290c779efb7947fd75d40196cd44c20f; HEAD 81eee9e3d5decc7f7ec3444f37a4a6a4a2967351. The parent supplied the base and HEAD. I took the task commits from `git log --stat <base>..HEAD -- web`.
- Source/test snapshot identifier: HEAD 81eee9e3d plus the uncommitted `general-settings.spec.ts` (sha256 61196ac272000eba6024d9c73672070e218fc20bf80a157c3546777f9e9f1c56). `general-settings.ts` sha256 dbc43809b68ac1dfca550cb716362185c32d6d1fc6c3502f6777ec4ba6b864bb and `general-settings.html` sha256 c23a410dcd4da7b7491afc4ad148311af7cedbbbbec1d92d5262dc715de9db90 are unchanged from HEAD.
- Included files: `web/src/app/features/settings/general/general-settings.ts` and `general-settings.html` (v8 reports the template separately). Excluded: `general-settings.scss` and `web/src/locale/*.xlf` (not executable), the two `*.spec.ts` files (tests), and all `android/` paths (another task). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8, `npm run coverage`, working directory `web/`, full web suite.
- Run start/completion time and exit status: started 2026-11-09T04:43:51+01:00; report written 2026-11-09T04:45:13+01:00; all tests passed (196 files, 4768 tests).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 6a78d8278d50031938541b6b6334c64d5c26507f7bbf8b5b81b6dab410d8cd8b; `web/coverage/web/coverage-summary.json`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report mtime falls within this run, and the source and spec hashes matched before and after the run.
- Final run after latest test additions and production fixes: yes (it includes fix commit 81eee9e3d and the added test).

Values below use covered/total (percentage), from coverage-summary.json.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/settings/general/general-settings.ts` | 167/169 (98.82%) | 86/94 (91.49%) | 35/35 (100%) | 140/140 (100%) |
| `web/src/app/features/settings/general/general-settings.html` | 271/278 (97.48%) | 16/16 (100%) | 11/13 (84.62%) | 166/166 (100%) |
| Aggregate (summed counters, both files; no scoped file unmeasured) | 438/447 (97.99%) | 102/110 (92.73%) | 46/48 (95.83%) | 306/306 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `general-settings.ts:252` (`if (!current) return;` true branch) | Settings vanish while a row is saving. Defensive check added by the CR-1 fix | Gap left open: the settings service always holds settings after a save. Not reachable in practice. |
| `general-settings.ts:336` (fallback to `DEFAULT_REMINDER_TIME` when the kept time is invalid) | The time is emptied and the row is then reset (Off picked or the save refused) | Gap left open: low risk, an edge of an edge |
| `general-settings.ts:207, 208, 250, 290, 302`; `:111` | Time zone autocomplete, pending counter default, time zone blur, sender removal guard; a branch the decorator emits | Code from before this task or compiler output, outside the task |
| `general-settings.html:14, 30, 167, 168, 196, 199` | Form submit and autocomplete listeners, energy/creativity label outlets | Template code from before this task, outside the task |
| `general-settings.ts:255` `!this.reminderPending` | CR-1 guard | Both sides are now covered (new test) |

## Unable to test

- The German translations in `messages.de.xlf` (including the new `settings.reviewReminder.timeInvalid`) were not rendered in German by these tests.
- The server's handling of `weeklyReviewReminder` belongs to review-reminder-server and is out of scope.

## Summary

Tests added and all validation passed.
