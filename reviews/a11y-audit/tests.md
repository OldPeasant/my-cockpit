# Test Report

Date: 2026-10-26

Task: a11y-audit - Accessibility audit

Scope: task commits 29b12262d and 4c6070ef9 (`git diff 4682b60fecefe51e45ac2f2483ed93739bc09297..4c6070ef966aff5db139e3c0cabbbd3ebb7f8b77`), plus review finding CR-1 (`reviews/a11y-audit/review.md`), which asks for unit tests of the calendar time-grid DOM patch.

Requirements:
- `tasks/a11y-audit.md` acceptance criterion and its assumptions. The FullCalendar scroller patch assumption says: the scroller gets tabindex=0 and role=rowgroup, and its parent gets role=none.
- `reviews/a11y-audit/review.md` CR-1 (unit tests for `focusableScrollers`) and CR-2 (scroller focusable regardless of overflow)
- Parent instructions; `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md`

## Test files changed

- `web/src/app/features/calendar/views/calendar-view.spec.ts` (new `describe('keyboard access to the time grid')`, 8 tests, additions only)

## Tests added or updated

Count: 8

All 8 tests go through the component. They call `options().datesSet(...)` and then wait one macrotask (`settle()`), so the patch's `setTimeout` runs. The fake scrollers are `div[role=rowgroup] > div` elements inside the host's `full-calendar` element. Like FullCalendar's `Scroller`, they set inline `overflowX`/`overflowY`. jsdom does not parse the two-value `overflow: hidden auto` shorthand, which is why the fixture sets the longhands.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Time-grid scroller inside a rowgroup becomes role=rowgroup + tabindex=0 and its parent role=none (assumption, CR-1) | `makes a scroller inside a rowgroup a focusable rowgroup and its parent presentational` | `npx --prefix web ng test --watch=false --include src/app/features/calendar/views/calendar-view.spec.ts` | Pass |
| Patched also when it does not overflow yet (CR-2: scrollHeight guard dropped) | `also patches a scroller with overflow-y scroll that does not overflow (yet)` | same | Pass |
| Idempotent on a second datesSet (CR-1) | `changes nothing on a second datesSet` | same | Pass |
| Parent not a rowgroup: left alone (CR-1) | `leaves scrollers alone whose parent is no rowgroup` (no role, role=grid) | same | Pass |
| Already has a tabindex: left alone (CR-1) | `leaves scrollers alone that already have a tabindex` | same | Pass |
| Not vertically scrollable (overflow-y hidden, horizontal scroller): left alone | `leaves elements alone that do not scroll vertically` | same | Pass |
| Only elements inside `full-calendar` are patched | `leaves scrollers outside the calendar alone` | same | Pass |
| Today button's accessible name contains its visible text (WCAG 2.5.3) | `names the today button with its visible text` (checks the option's `hint` and the rendered FullCalendar button's `title`) | same | Pass |
| axe-core scan of every main page in light and dark, plus the keyboard walkthrough | `web/e2e/a11y/a11y.spec.ts` (not changed by me) | none | Not run: this needs the podman e2e stack, and the parent runs it (parent instruction) |

## Focused test execution

Command:

`npx --prefix web ng test --watch=false --include src/app/features/calendar/views/calendar-view.spec.ts`

Result:

Pass: 1 file, 59 tests passed (51 existing + 8 new).

While writing the tests, two runs failed because of my fixtures, not the product code. One used string `overflow: hidden auto` inline styles, which jsdom cannot parse; the other had typos. I fixed the fixtures; no product code was touched.

## Full test execution

Command:

`npm --prefix web run check` (api:check, `ng lint --max-warnings=0`, e2e typecheck, `ng test --watch=false`, `ng build`)

Result:

Pass: lint clean; 175 test files, 3680 tests passed; build complete. The build printed the bundle-budget warning, which already exists and is not related to this change. Prettier `--check` on the spec file also passed.

## .taskcheck

Result:

Not run by the test-writer. `sh scripts/check.sh` also runs the Gradle server and Android checks, and the parent runs those (the parent runs `.taskcheck` before `done`). Only the web part was run here (`npm --prefix web run check`, Pass).

## Bugs found

None.

## Coverage

Status: verified (web part, whole-file and changed-line).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 9/9 executable added lines covered (100%). Method: `git diff -U0 4682b60fe..4c6070ef9 -- web/src/app/features/calendar/views/calendar-view.ts` added lines, intersected with the `DA:` entries for that file in `web/coverage/web/lcov.info`. The executable added lines are 72, 295, 319, 322, 323, 328, 329, 330 and 331. Changed-branch coverage was not measured separately.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: a11y-audit; base 4682b60fecefe51e45ac2f2483ed93739bc09297; HEAD 4c6070ef966aff5db139e3c0cabbbd3ebb7f8b77; the task commits 29b12262d and 4c6070ef9 were supplied by the parent and match `git log 4682b60fe..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 4c6070ef9 plus the uncommitted `web/src/app/features/calendar/views/calendar-view.spec.ts` (sha256 ec68ac31ddddce44e247153c16baac360813b8096fe633a078ce2133f496e4ab). `calendar-view.ts` sha256 is d75acbbc86c13836bba3290b48150363125cdfad5df5b9430624b2cf74f84aae and is unchanged from HEAD. Both hashes were identical before and after the coverage run.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/calendar/views/calendar-view.ts`. Excluded: `calendar-view.scss`, `gtd/inbox/inbox-page.scss` and `notes/editor/markdown-editor.scss` (stylesheets, not executable; checked by the e2e axe scan); `web/e2e/a11y/a11y.spec.ts` (test code); `web/playwright.config.ts` (test configuration); `reviews/*` (documentation). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8` 5.0.5, via `npm --prefix web run coverage` (`ng test --watch=false --coverage ...`), cwd = repository root, all web unit tests.
- Run start/completion time and exit status: started 2026-10-26 10:54:59 +01:00, reports written 10:55:43 +01:00, exit 0, 175 files / 3680 tests passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256 ce0f482b34f1a9e9e14bdd6a88dae3807e1596e4a76534a500e73a913bb5cf5c), `web/coverage/web/coverage-summary.json` (sha256 c6a3d597010e564220f459a779d18c023285d1471c70be98369a9d71719ee63d), `web/coverage/web/lcov.info`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Vitest rewrote `coverage/` during this run (file mtimes are 10:55:43, after the 10:54:59 start), and the snapshot hashes match before and after.
- Final run after latest test additions and production fixes: yes. It ran after all 8 tests were added and on the final task code 4c6070ef9 (which includes the CR-2 fix).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/calendar/views/calendar-view.ts` | 139/144 (96.52%) | 66/71 (92.95%) | 41/42 (97.61%) | 118/118 (100%) |

The aggregate equals the single file above. No scoped executable file is unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `calendar-view.ts:83` (`viewChild(FullCalendarComponent)` arrow fn, branch at 67) | Angular-generated query function, existing code | Not task code; no test added |
| `calendar-view.ts:218, 242, 263, 276` (`if (!timeZone) return` / `if (!settings) return undefined` / `if (!context) return` guards) | Defensive guards that only trigger when settings are null in event handlers; existing code | Not task code; no test added |
| `focusableScrollers` (lines 317-333) and `onDatesSet` patch scheduling (295) | CR-1 | Fully covered by the 8 new tests |

## Unable to test

- The real FullCalendar 7 time-grid DOM, and whether its scroller really is a direct child of `role=rowgroup`, needs browser layout. jsdom does not parse the `overflow: hidden auto` shorthand that browsers serialize. The unit tests therefore use a fake structure that mirrors FullCalendar's `Scroller` (inline `overflowX`/`overflowY`). The real DOM is covered only by the e2e axe scan of the day and week views (`web/e2e/a11y/a11y.spec.ts`). The parent runs that scan against the podman stack; it is not part of `.taskcheck`. If a FullCalendar upgrade changes this DOM, only that e2e run will notice.
- The contrast fixes in the SCSS files cannot be unit tested; the e2e axe scan covers them.

## Summary

Tests added and all validation passed. CR-1 is addressed with 8 component-level tests of `focusableScrollers` and the today-button hint. No production defects were found. Changed-line coverage of `calendar-view.ts` is 9/9. The e2e a11y suite and the full `.taskcheck` are left to the parent.
