# Test Report

Date: 2026-11-22

Task: web-cal-hidden-indicator - Web calendar: day/week show which entries are scrolled out of view

Scope: `git diff 44db0f76cfc74ae775589ab8efbb9e2d231aa846..af6a82f2292a149a60354ea80127c43dba8acf89 -- web/src`. Task commits are 8cf62154b, 8818208fe, c0a1ad40d and 4bd88909f (merged in d92382f27), fixer commit f86cb06e3, and the test commits up to 6923fb36b. This is a re-run on the final revision af6a82f22. `git diff --stat 6923fb36b af6a82f22 -- web/src` is empty, so production code and unit tests are unchanged since the previous report. The production files are `web/src/app/features/calendar/views/{hidden-entries.ts, hidden-chips.ts, calendar-view.ts, calendar-view.html, calendar-view.scss}` and `web/src/locale/messages{,.de}.xlf`.

Requirements:
- `tasks/web-cal-hidden-indicator.md` acceptance criteria and notes (assumptions)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- Parent instructions (re-run at the final revision, add tests only for a real gap, and record the parent's browser check)

## Test files changed

None in this run. Earlier in this task (now committed):
- `web/src/app/features/calendar/views/hidden-chips.spec.ts` (new)
- `web/src/app/features/calendar/views/calendar-view.spec.ts` (new `describe('hidden-entry chips')` block)
- `web/src/app/features/calendar/views/hidden-entries.spec.ts` (one test added)

## Tests added or updated

Count: 0 in this run (23 earlier in this task: 12 in hidden-chips.spec.ts, 10 in calendar-view.spec.ts, 1 in hidden-entries.spec.ts). The re-check found no new gap. The browser check run by the parent covers the remaining layout, keyboard and resize gaps (see below).

## Requirement coverage

Commands:
- C = `npm --prefix web run coverage -- --include='src/app/features/calendar/views/**/*.spec.ts'` (the same as `cd web && npm run coverage -- --include=...`)
- B = the parent's real-browser Playwright check `reviews/web-cal-hidden-indicator/browser-check.spec.ts.txt`, run by the parent against the e2e stack at 1280x800 and 360x640, day and week view. The test-writer did not run it again.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: chip at the top edge when n entries are entirely above, bottom edge when entirely below, per day column | `hiddenChips places a chip at the top and the bottom edge...`, `counts all hidden entries and targets the nearest one`, `gives each column the chips of its own day only`, `places the columns by their offset...`; `CalendarView hidden-entry chips shows a chip at the top and the bottom edge...`, `shows the chips in the day view too`; `timeGridOf` (5 tests) | C; B | Pass; B passed (parent) |
| AC1: partly visible entries count as visible; no chip when n = 0 | `hiddenChips shows no chip when all entries are visible or partly visible`, `CalendarView ... shows no chip when every entry is (partly) visible`; existing hidden-entries tests | C | Pass |
| AC1: update while scrolling, on resize, on period change, on events change | `updates the chips while scrolling`, `updates the chips on resize`, `recomputes the chips when the period changes, before its events load`, `updates the chips when the events change`, `removes the chips when switching to the month view`; browser check: viewport resize to 3000px height removes the chips | C; B | Pass; B passed (parent) |
| AC2: arrow + count, wide text from 88px | `shows a chip at the top and the bottom edge...` (▲/▼, count, "1 earlier"/"1 later", wide class at 96px), `updates the chips while scrolling` ("2 earlier") | C; B | Pass; B passed (parent) |
| AC2: does not cover the all-day row/headers, fits a 360px viewport | browser check: chip top >= bottom of the all-day event, and the chip's right edge <= viewport width, at 1280 and 360 px, day and week | B | Passed (run by the parent, real browser), not run again by the test-writer |
| AC3: click scrolls smoothly to the nearest hidden entry | `are buttons that scroll smoothly to the nearest hidden entry, also from the keyboard`; browser check: clicks reveal the 02:00, then 01:00, then 22:00 entries | C; B | Pass; B passed (parent) |
| AC4: buttons with an accessible label, keyboard | same unit test plus label assertions; browser check: `getByRole('button', {name})`, focus + Enter scrolls | C; B | Pass; B passed (parent) |
| AC5: pure visibility function with unit tests | hidden-entries.spec.ts (existing + `finds the nearest hidden entries whatever their order`) | C | Pass |
| AC6: English + German texts | none | - | Not testable in unit tests (xlf content); not checked here |
| AC7: unit tests; `npm run check` / `scripts/check.sh` pass | full suite below | `npm --prefix web run test:ci` | Fail: 4 failures in the unrelated `gtd/task-reminders.spec.ts` (see below) |
| Cleanup on destroy | `stops listening for scrolling once destroyed` | C | Pass |

## Focused test execution

Command:

`npm --prefix web run coverage -- --include='src/app/features/calendar/views/**/*.spec.ts'` -> 6 files, 136 passed (2026-11-22 09:53)

Result:

Pass

## Full test execution

Command:

`npm --prefix web run test:ci`

Result:

Fail: 4952 passed, 4 failed (197 of 198 files passed). All 4 failures are in `src/app/features/gtd/task-detail/task-reminders.spec.ts` ("shows the error when adding or loading fails", "does not let a reloading list bring back a reminder removed meanwhile", "offers a retry instead of claiming there are none when loading fails", "does not reload while an add after a failed load is pending"). This file is outside the task's scope, and the previous report found that it also fails when run alone. The timing-dependent `CalendarView > linked event > shows an all-day linked event...` test from the previous report passed in this run. It was intermittent before, so one pass does not prove it is fixed.

## .taskcheck

Result:

Not run by the test-writer (`sh scripts/check.sh` would fail on the unrelated task-reminders failures above). Left to the parent.

## Bugs found

No production defects were found in the task's code. The remaining full-suite failures are in an unrelated feature (gtd task reminders).

## Coverage

Status: verified (focused run, calendar views specs only).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-cal-hidden-indicator; base 44db0f76cfc74ae775589ab8efbb9e2d231aa846; HEAD af6a82f2292a149a60354ea80127c43dba8acf89. Commits and file list come from the parent, checked with `git diff --stat 6923fb36b HEAD -- web/src` (empty).
- Source/test snapshot identifier (including uncommitted inputs): sha256 calendar-view.ts 99ddafa1299c..., hidden-chips.ts 5edf3b39ee4b..., hidden-entries.ts 3cba82665d91..., calendar-view.html 2d6f52eb368b...; calendar-view.spec.ts 692eb00243b9..., hidden-chips.spec.ts ae5aef02c566..., hidden-entries.spec.ts 0b43dab0a9d8.... All are committed, with no uncommitted changes under `web/src`. They are identical to the previous report's snapshot. The only uncommitted inputs in the clone are the parent's untracked `web/e2e/calendar-views/zz-hidden-check.spec.ts` (not a unit-test input) and `tasks/web-cal-hidden-indicator.md`.
- Exact included files; renamed/deleted/excluded files and reasons: included hidden-entries.ts, hidden-chips.ts and calendar-view.ts. Excluded: calendar-view.html (template compiled into the component, with no separate counters), calendar-view.scss (styles), messages*.xlf (translations) and spec files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8; `npm --prefix web run coverage -- --include='src/app/features/calendar/views/**/*.spec.ts'` from the clone root (runs in `web/`); the 6 spec files in the calendar views folder.
- Run start/completion time and exit status: 2026-11-22T09:53:20+01:00 - about 09:53:25+01:00, 136/136 passed, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 f250d0ab01f528431c91480a4368ecfbe66b25ccf04fbf2b020a311f9bf33688, mtime 09:53:25, inside the run window), `coverage-final.json`, `lcov.info`. The hash equals the previous run's because the inputs are identical.
- Evidence outputs are fresh and inputs stayed unchanged: yes (report mtime is inside the run window; input hashes were checked after the run and match).
- Final run after latest test additions and production fixes: yes (final revision af6a82f22; no production or test change since f86cb06e3 / 6923fb36b).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/calendar/views/hidden-entries.ts` | 32/32 (100%) | 22/22 (100%) | 4/4 (100%) | 28/28 (100%) |
| `web/src/app/features/calendar/views/hidden-chips.ts` | 39/39 (100%) | 26/26 (100%) | 3/3 (100%) | 33/33 (100%) |
| `web/src/app/features/calendar/views/calendar-view.ts` | 190/195 (97.44%) | 93/99 (93.94%) | 55/56 (98.21%) | 165/165 (100%) |
| Aggregate (3 files; html/scss/xlf excluded as above) | 261/266 (98.12%) | 141/147 (95.92%) | 62/63 (98.41%) | 226/226 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `calendar-view.ts:185` (branch: `new ResizeObserver`) | Chips update on container resize | jsdom has no ResizeObserver, so the window `resize` path is unit-tested instead. In a real browser, the parent's check showed that the chips update on a viewport resize. |
| `calendar-view.ts:70, 86, 317, 341, 362, 375` | Decorator / viewChild / pre-existing editing and linked-event code | Outside this task's changes |

## Unable to test

- AC2 layout and real smooth scrolling were not unit-testable (jsdom does no layout). They are covered by the parent's real-browser Playwright check (`reviews/web-cal-hidden-indicator/browser-check.spec.ts.txt`), which passed at 1280x800 and 360x640 in day and week view. The parent ran it; the test-writer did not run it again.
- Native Enter/Space activation does not work in jsdom. The browser check covers Enter.
- German/English xlf content (AC6): not unit-testable here.
- ResizeObserver path: not available in jsdom (see the coverage gap above).

## Summary

Tests and task-scoped coverage are sufficient, and no production defects were found. The focused specs (136) pass at the final revision af6a82f22, with coverage unchanged: 100% for hidden-entries.ts and hidden-chips.ts, and 100% lines / 93.94% branches for calendar-view.ts. Full validation is still not green because of 4 failures in the unrelated `gtd/task-reminders.spec.ts`. The parent must resolve that, or establish that it is out of scope, before `.taskcheck`/`done`.
