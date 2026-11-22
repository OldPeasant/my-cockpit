# Test Report

Date: 2026-11-22

Task: web-cal-hidden-indicator - Web calendar: day/week show which entries are scrolled out of view

Scope: `git diff 44db0f76cfc74ae775589ab8efbb9e2d231aa846..f86cb06e3ecba3595091070a1ccb100213beb351 -- web` (task commits 8cf62154b, 8818208fe, c0a1ad40d, 4bd88909f merged in d92382f27, plus fixer commit f86cb06e3). Production files: `web/src/app/features/calendar/views/{hidden-entries.ts, hidden-chips.ts, calendar-view.ts, calendar-view.html, calendar-view.scss}`, `web/src/locale/messages{,.de}.xlf`.

Requirements:
- `tasks/web-cal-hidden-indicator.md` acceptance criteria and notes (assumptions)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- Parent instructions (scope, wanted tests); coordinator message about fixer commit f86cb06e3

## Test files changed

- `web/src/app/features/calendar/views/hidden-chips.spec.ts` (new)
- `web/src/app/features/calendar/views/calendar-view.spec.ts` (new `describe('hidden-entry chips')` block)
- `web/src/app/features/calendar/views/hidden-entries.spec.ts` (one test added; Prettier reflowed three existing assertions, no content change)

## Tests added or updated

Count: 23 (12 in hidden-chips.spec.ts, 10 in calendar-view.spec.ts, 1 in hidden-entries.spec.ts)

## Requirement coverage

Commands: F = `cd web && npx ng test --watch=false --include='src/app/features/calendar/views/calendar-view.spec.ts'`; C = `cd web && npm run coverage -- --include='src/app/features/calendar/views/**/*.spec.ts'`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: chip at top edge when n entries entirely above, bottom edge when entirely below, per day column | `hiddenChips places a chip at the top and the bottom edge...`, `counts all hidden entries and targets the nearest one`, `gives each column the chips of its own day only`, `places the columns by their offset...`; `CalendarView hidden-entry chips shows a chip at the top and the bottom edge...`, `shows the chips in the day view too`; `timeGridOf` (5 tests) | F, C | Pass |
| AC1: partly visible count as visible; no chip when n = 0 | `hiddenChips shows no chip when all entries are visible or partly visible`, `CalendarView ... shows no chip when every entry is (partly) visible`; existing hidden-entries tests | F, C | Pass |
| AC1: update while scrolling, on resize, on period change, on events change | `updates the chips while scrolling`, `updates the chips on resize`, `recomputes the chips when the period changes, before its events load` (regression for fixer change: the next week's load is held pending, so only `range()` can trigger it), `updates the chips when the events change`, `removes the chips when switching to the month view` | F, C | Pass |
| AC2: arrow + count, wide text from 88px | `shows a chip at the top and the bottom edge...` (▲/▼, count, "1 earlier"/"1 later", wide class at 96px), `updates the chips while scrolling` ("2 earlier") | F, C | Pass |
| AC2: does not cover all-day row/headers, fits 360px viewport | none (layout) | - | Not run: jsdom lays out nothing; needs a real browser (Playwright) |
| AC3: click scrolls smoothly to the nearest hidden entry | `are buttons that scroll smoothly to the nearest hidden entry, also from the keyboard` (scrollTo `{top:120, behavior:'smooth'}` / `{top:860,...}`) | F, C | Pass |
| AC4: buttons with accessible label, keyboard | same test (native `<button type=button>`, focusable, not disabled), label assertions in all component tests (singular/plural labels of f86cb06e3) | F, C | Pass (Enter/Space activation itself is native browser behavior; jsdom does not synthesize it) |
| AC5: pure visibility function with unit tests | hidden-entries.spec.ts (existing + `finds the nearest hidden entries whatever their order`) | C | Pass |
| AC6: English + German texts | none | - | Not testable in unit tests (xlf content); not checked here |
| AC7: unit tests; `npm run check` / `scripts/check.sh` pass | full suite below | `cd web && npm run test:ci` | Fail: 5 failures, see below (not in this task's code) |
| Cleanup on destroy | `stops listening for scrolling once destroyed` | F, C | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/calendar/views/calendar-view.spec.ts'` -> 69 passed
`cd web && npm run coverage -- --include='src/app/features/calendar/views/**/*.spec.ts'` -> 6 files, 136 passed

Result:

Pass (but see the order/timing-dependent existing test below: the same focused coverage command failed once with it, at 09:36)

## Full test execution

Command:

`cd web && npm run test:ci`

Result:

Fail: 4950 passed, 5 failed (198 files). None of the failures is in the new tests:
- `CalendarView > linked event > shows an all-day linked event at its start date and refetches after a change` (existing test, `calendar-view.spec.ts:~1420`): `expected ... length of 2 but got 3` (`listEventOccurrences` called once more than expected). Intermittent: it passes alone (`--filter='linked event'`), passes in some full-file runs and fails in others. It also failed with the new chip block excluded (`--filter='^(?!.*hidden-entry chips)'`), so the new block is not what leaves state behind. It looks timing-dependent: the real FullCalendar `gotoDate` fires `datesSet` and so triggers a range load. Whether that load lands before the count depends on timing. I could not check whether it also fails at the base commit, because I could not create a worktree in this environment. The parent should investigate it before `.taskcheck`.
- `gtd/task-detail/task-reminders.spec.ts`: 4 failures (`buttons('.chip')[0]` undefined). These fail deterministically when the file runs alone too. The file is not part of this task's scope (likely date-dependent, today 2026-11-22). Out of scope; report to the parent.

## .taskcheck

Result:

Not run (`sh scripts/check.sh` would fail on the web unit failures above; left to the parent).

## Bugs found

No production defects found in the task's code. The failures above are in an existing test (timing-dependent) and in an unrelated feature.

## Coverage

Status: verified (focused run, calendar views specs only).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-cal-hidden-indicator; base 44db0f76cfc74ae775589ab8efbb9e2d231aa846; HEAD f86cb06e3ecba3595091070a1ccb100213beb351; commits from parent + `git log 44db0f76c..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 calendar-view.ts 99ddafa1..., hidden-chips.ts 5edf3b39..., hidden-entries.ts 3cba8266..., calendar-view.html 2d6f52eb...; uncommitted tests: calendar-view.spec.ts 692eb002..., hidden-chips.spec.ts ae5aef02..., hidden-entries.spec.ts 0b43dab0... (hashes taken before and after the run, unchanged).
- Exact included files; renamed/deleted/excluded files and reasons: included hidden-entries.ts, hidden-chips.ts, calendar-view.ts. Excluded: calendar-view.html (template, compiled into the component, no separate counters), calendar-view.scss (styles), messages*.xlf (translations), spec files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8 5.0.5; `npm run coverage -- --include='src/app/features/calendar/views/**/*.spec.ts'` in `web/`; the 6 spec files in the calendar views folder. The full-suite coverage run wrote no report because tests failed (Vitest `reportOnFailure` default).
- Run start/completion time and exit status: 2026-11-22T09:40:03+01:00 - 09:40:11+01:00, 136/136 passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 f250d0ab01f5...), `coverage-final.json`.
- Evidence outputs are fresh and inputs stayed unchanged: yes (report mtime inside the run window; input hashes unchanged).
- Final run after latest test additions and production fixes: yes (after fixer commit f86cb06e3 and all test additions).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/calendar/views/hidden-entries.ts` | 32/32 (100%) | 22/22 (100%) | 4/4 (100%) | 28/28 (100%) |
| `web/src/app/features/calendar/views/hidden-chips.ts` | 39/39 (100%) | 26/26 (100%) | 3/3 (100%) | 33/33 (100%) |
| `web/src/app/features/calendar/views/calendar-view.ts` | 190/195 (97.43%) | 93/99 (93.94%) | 55/56 (98.21%) | 165/165 (100%) |
| Aggregate (3 files) | 261/266 (98.12%) | 141/147 (95.92%) | 62/63 (98.41%) | 226/226 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `calendar-view.ts:185` (branch: `new ResizeObserver`) | Chips update on container resize | jsdom has no ResizeObserver; window `resize` path tested instead. Needs a browser check. |
| `calendar-view.ts:70, 86, 317, 341, 362, 375` | Decorator / viewChild / pre-existing editing and linked-event code | Outside this task's changes |

## Unable to test

- AC2 layout (chip not covering the all-day row or column headers, fits a 360px viewport) and real smooth scrolling: needs Playwright against a real browser. There is an untracked `web/e2e/calendar-views/zz-hidden-chips-scratch.spec.ts` in the clone. I did not write it and did not run it. Follow-up for the parent.
- Native Enter/Space activation of the chip buttons: browser behavior, not reproducible in jsdom.
- German/English xlf content (AC6): not unit-testable here.
- ResizeObserver path: not available in jsdom.

## Summary

Tests added and all focused tests pass. Task-scoped coverage is complete for the new files, and no production defects were found. Full validation is not green: an existing timing-dependent calendar-view test fails intermittently, also without the new tests, and 4 unrelated task-reminders tests fail. The parent must resolve these before `.taskcheck`/`done`.
