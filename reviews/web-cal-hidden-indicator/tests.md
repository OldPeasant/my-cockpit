# Test Report

Date: 2026-11-22

Task: web-cal-hidden-indicator - Web calendar: day/week show which entries are scrolled out of view

Scope: task base f8cec873d (current merge-base 907999ac8), final revision 3f2853bf94badf83d1471c2773d99ecd52efe0fc. Task commits are 8cf62154b, 8818208fe, c0a1ad40d and 4bd88909f (merged in d92382f27), fixer commit f86cb06e3, the test commits up to 6923fb36b, and test-only commit 3f2853bf9. This is a re-run for the final revision. The only code change since the previous report (af6a82f22) is 3f2853bf9, which changes one test in `calendar-view.spec.ts`. Production code is unchanged. The production files are `web/src/app/features/calendar/views/{hidden-entries.ts, hidden-chips.ts, calendar-view.ts, calendar-view.html, calendar-view.scss}` and `web/src/locale/messages{,.de}.xlf`.

Requirements:
- `tasks/web-cal-hidden-indicator.md` acceptance criteria and notes (assumptions)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- Parent instructions (re-run at the final revision, add tests only for a real gap, and record the parent's browser check)

## Final-revision check (3f2853bf9)

Commit 3f2853bf9 changed `CalendarView > linked event > shows an all-day linked event at its start date and refetches after a change`. The editor result now comes from a deferred promise. The test waits for the date change and its range load, takes the `listEventOccurrences` count, and only then closes the editor with `true`. It then expects exactly one more load. This is a valid fix:
- The test still checks the required behavior: `gotoDate('2026-04-10')`, the editor is opened with `allDay: true`, and a saved change triggers a refetch.
- The test no longer counts the range load from `gotoDate`, which caused the "3 instead of 2" failure after merging main.
- It is not weaker than before. The assertion is still an exact `loads + 1`.

Minor note (not a defect): the test waits a real 50 ms (`setTimeout`) so the range load can land before the count is taken. Under heavy load this could in theory still be too short. If that happens, the count would include the range load and the test would fail visibly. It cannot produce a false pass. The test passed in all 3 runs in this round.

## Test files changed

None by the test-writer in this run. The parent changed `calendar-view.spec.ts` in 3f2853bf9 (reviewed above). Earlier in this task (now committed):
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
| AC7: unit tests; `npm run check` / `scripts/check.sh` pass | full suite below | `npm --prefix web run test:ci` | Not run again this round. Previous round: Fail, with 4 failures in the unrelated `gtd/task-reminders.spec.ts` (see below). The parent must confirm on the final revision. |
| Cleanup on destroy | `stops listening for scrolling once destroyed` | C | Pass |

## Focused test execution

Command:

Run at revision 3f2853bf9 on 2026-11-22:
- `npm --prefix web run test:ci -- --include src/app/features/calendar/views/calendar-view.spec.ts` -> 1 file, 69 passed (10:35:05)
- `npm --prefix web run test:ci -- --include src/app/features/calendar/views/calendar-view.spec.ts --include src/app/features/calendar/views/hidden-entries.spec.ts --include src/app/features/calendar/views/hidden-chips.spec.ts` -> 3 files, 92 passed (10:35:28)
- `npm --prefix web run coverage -- --include='src/app/features/calendar/views/**/*.spec.ts'` -> 6 files, 136 passed (10:35:13)

Result:

Pass

## Full test execution

Command:

`npm --prefix web run test:ci`

Result:

Not run again in this round, because the parent asked for focused runs only. The result below is from the previous round (revision af6a82f22): 4952 passed, 4 failed (197 of 198 files passed). All 4 failures are in `src/app/features/gtd/task-detail/task-reminders.spec.ts` ("shows the error when adding or loading fails", "does not let a reloading list bring back a reminder removed meanwhile", "offers a retry instead of claiming there are none when loading fails", "does not reload while an add after a failed load is pending"). This file is outside the task's scope, and the previous report found that it also fails when run alone. The timing-dependent `CalendarView > linked event > shows an all-day linked event...` test is now made deterministic by 3f2853bf9 (see above).

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
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-cal-hidden-indicator; base f8cec873d (merge-base 907999ac8); HEAD 3f2853bf94badf83d1471c2773d99ecd52efe0fc. Commits and file list come from the parent. `git show --stat 3f2853bf9` shows that only `calendar-view.spec.ts` changed.
- Source/test snapshot identifier (including uncommitted inputs): committed tree 3f2853bf9. `git status` shows no uncommitted changes under `web/src`. The only modified file is `reviews/web-cal-hidden-indicator/review.md`, which is not a test input. Production files are unchanged from the previous snapshot.
- Exact included files; renamed/deleted/excluded files and reasons: included hidden-entries.ts, hidden-chips.ts and calendar-view.ts. Excluded: calendar-view.html (template compiled into the component, with no separate counters), calendar-view.scss (styles), messages*.xlf (translations) and spec files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8; `npm --prefix web run coverage -- --include='src/app/features/calendar/views/**/*.spec.ts'` from the clone root (runs in `web/`); the 6 spec files in the calendar views folder.
- Run start/completion time and exit status: 2026-11-22T10:35:13+01:00, duration 7.5 s, 136/136 passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 f250d0ab01f528431c91480a4368ecfbe66b25ccf04fbf2b020a311f9bf33688), `coverage-final.json`, `lcov.info`. The summary hash equals the previous run's. This is expected: production files are unchanged, and the changed test exercises the same code paths.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The run was made at 3f2853bf9 with a clean `web/src`.
- Final run after latest test additions and production fixes: yes (final revision 3f2853bf9).

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

Tests and task-scoped coverage are sufficient, and no production defects were found. The test-only fix in 3f2853bf9 is valid. The focused specs (69 in calendar-view.spec.ts, 92 across the three relevant specs, 136 in the calendar views folder) pass at the final revision 3f2853bf9, with coverage unchanged: 100% for hidden-entries.ts and hidden-chips.ts, and 100% lines / 93.94% branches for calendar-view.ts. The test-writer did not run the full suite and `.taskcheck` again in this round. The parent should confirm them on the final merged revision before `done`. In the previous round, the full suite had 4 failures in the unrelated `gtd/task-reminders.spec.ts`.
