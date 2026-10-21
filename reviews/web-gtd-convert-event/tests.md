# Test Report

Date: 2026-10-21

Task: web-gtd-convert-event — Inbox/task detail: to calendar

Scope: `git diff c099b9d147c92bfdaa0ad675e2d3ce41bf5a5a9e..b840b0addf884f2a1718161ecba653d90e512a7d -- web`
(branch `main`, HEAD `b840b0add`), plus the uncommitted test changes of this round listed below.
Task-owned commits (the only commits in base..HEAD, all with the `web-gtd-convert-event:` subject):
`7c9695f3e` (dialog, service, store, task detail, inbox), `afd0f01bd` (inbox tests, separate button,
formatting), `69fcd18fd` (first-round tests and report), `1b0b6f95a` (review report), `b840b0add`
(fixer: CR-1..CR-3). Base and commits were given by the parent and match the commit subjects.

This is the second round, on the final code after the fixer. First round (at the pre-rebase HEAD
`d45cbf24a`): 48 tests added in `to-calendar-dialog.spec.ts` (new, 25), `task-detail.spec.ts` (18),
`task-detail.service.spec.ts` (2), `task-store.spec.ts` (3); all passed, `npm run check` passed, no
production defects. The fixer then added 4 tests (2 for CR-1 in `task-detail.spec.ts`, 2 for CR-3 in
`to-calendar-dialog.spec.ts`). This round adds tests for the fixed behavior the fixer did not cover.

Requirements:
- `tasks/web-gtd-convert-event.md` acceptance criteria and its recorded assumptions
- `reviews/web-gtd-convert-event/review.md` (CR-1..CR-3) and `fixes.md`
- Parent instructions (normal Save with a 400 on a field the form does not show; `eventStartDay` directly)
- `AGENTS.md`, `docs/ARCHITECTURE.md` "Coverage", existing spec conventions

## Test files changed

This round (uncommitted):
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts`
- `web/src/app/features/gtd/task-detail/to-calendar-dialog.spec.ts`

Earlier rounds (committed): the two files above plus `task-detail.service.spec.ts`, `task-store.spec.ts`.

## Tests added or updated

Count this round: 10 added (task-detail spec 3, dialog spec 7). No existing test was changed,
weakened or skipped (only the import list of the dialog spec grew). Total over the task: 62 tests by
the test-writer and fixer.

Added this round:
- `TaskDetail` › `save errors` › `shows a 400 error of a field the form does not show in the save bar`
  (CR-1 on normal Save: `contextIds` error in the save bar, the mapped `timeEstimateMin` error stays
  at the estimate field only, changes kept, Save enabled; fails on the pre-fix code, which dropped it)
- `TaskDetail` › `save errors` › `falls back to the message of the answer when a hidden field error has no text`
- `TaskDetail` › `save errors` › `shows no save bar error when every 400 error is at a field of the form`
  (`title` and `recurrenceRule` → `customRule` map to shown fields: no save bar alert)
- `ToCalendarDialog` › `keeps the one-day range when the settings answer late while all day is ticked` (CR-3)
- `eventStartDay` › 5 tests: day in the event's zone (Zurich vs New York across midnight), browser zone
  without an event zone, all-day start date ignoring an instant, no start instant, all-day without a
  start date falls back to today (CR-2)
- `browserTimeZone` › `gives the resolved zone of the browser` (CR-2)

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Dialog picks calendar (writable only, default preselected, first writable fallback, none writable, load + retry) | `ToCalendarDialog` › `names the task and preselects…`, `preselects the first writable calendar…`, `says so and cannot convert…`, `loads the calendars when they are not loaded yet…`, `offers a retry…`, `converts the chosen calendar and typed times` | F1, F2 | Pass |
| AC1 Dialog picks time (next full hour in the settings' zone, after 23:00, late/failed settings, typed times kept) | `starts at the next full hour…`, `moves the default to the next day after 23:00`, `loads missing settings…`, `keeps times the user typed…`, `still converts in the browser zone…` | F1, F2 | Pass |
| AC1 All-day (exclusive end, one-day; CR-3 default times across midnight, typed dates kept, late settings while ticked) | `converts an all-day event…`, `converts a one-day all-day event…`, `makes the default times spanning midnight a one-day event…` (fixer), `keeps dates the user typed when ticking all day` (fixer), `keeps the one-day range when the settings answer late while all day is ticked` (new), `convertRequest` › 2 | F1, F2 | Pass |
| AC1 Validation, Cancel, unsaved warning, `askForCalendar` | `refuses…` (3), `does not convert a timed event without…`, `endsAfterStart` › 2, `closes without a request on Cancel`, `warns that unsaved changes…`, `askForCalendar` › 2 | F1, F2 | Pass |
| AC1 Converts: service and store | `TaskDetailService` › 2 convert tests; `TaskStore` › `convertToEvent` › 3 | F2 | Pass |
| AC1 Converts: task detail (success + navigate to the start day, running state, 412/400/500/unexpected errors, task switched) | `TaskDetail` › `to calendar` › 20 tests incl. fixer's `shows 400 errors of the calendar in the save bar…`, `shows 400 errors of the times in the save bar next to errors of the form` | F1, F2 | Pass |
| CR-1 400 errors of fields the form does not show reach the save bar (convert and normal Save), shown fields stay at the field, empty message fallback | fixer's 2 tests above; new `save errors` › 3 tests | F1, F2 | Pass |
| CR-2 shared `eventStartDay` / `browserTimeZone` (task detail and inbox link day) | `eventStartDay` › 5, `browserTimeZone` › 1; task detail `opens the calendar on the start day…` tests; `InboxPage` › `To calendar` tests | F1, F2 | Pass |
| AC1 Converts: inbox processing (key C, dialog, status + Open calendar link) | `InboxPage` › `To calendar` tests | F2 | Pass |
| AC1 in a real browser against the running stack | none | — | Not run: no Playwright test for this flow and the criterion does not name an E2E run; covered by Vitest component tests with the real dialog in jsdom |
| AC2 Vitest tests cover the convert flow; `cd web && npm run check` passes | all of the above | F2; `npm run check` | Vitest: Pass. `npm run check`: Not run in this round (parent instruction: the parent runs `.taskcheck`, which includes it); the fixer reported it passing at `b840b0add` without this round's tests |

Commands:
- F1: `cd web && npx ng test --watch=false --include='src/app/features/gtd/task-detail/**/*.spec.ts'` → 7 files, 251 tests passed
- F2: `cd web && npm run coverage -- --include='src/app/features/gtd/**/*.spec.ts'` (same specs as `npx ng test --watch=false --include='src/app/features/gtd/**/*.spec.ts'`, with coverage) → 31 files, 735 tests passed

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/gtd/task-detail/**/*.spec.ts'` → 251 passed;
`npx ng lint --max-warnings=0` → "All files pass linting.";
`npx prettier --check src/app/features/gtd/task-detail src/app/features/gtd/inbox src/app/features/gtd/data` → "All matched files use Prettier code style!"

Result:

Pass

## Full test execution

Command:

`cd web && npm run coverage -- --include='src/app/features/gtd/**/*.spec.ts'` (all gtd specs).

Result:

Pass — 31 files, 735 tests. The full web suite (`npm run check`) was not run in this round on the
parent's instruction; the parent runs it through `.taskcheck`.

## .taskcheck

Result:

Not run: the parent runs `.taskcheck` (`sh scripts/check.sh` also builds server and android with
Gradle, which helpers cannot run here).

## Bugs found

None. All tests, including the new ones for the fixed behavior, pass against HEAD `b840b0add`.

## Coverage

Status: verified (whole-file and changed-line, for all task-changed production `.ts` files).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured — executable lines from the provider's `lcov.info` (`DA:` records)
intersected with added/modified lines of `git diff -U0 c099b9d14..HEAD` for each scoped file: 117/117 (100%).
Changed-branch coverage not inferred.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-gtd-convert-event`; base `c099b9d147c92bfdaa0ad675e2d3ce41bf5a5a9e`; HEAD `b840b0addf884f2a1718161ecba653d90e512a7d`; commits `7c9695f3e`, `afd0f01bd`, `69fcd18fd`, `1b0b6f95a`, `b840b0add` (from the parent, matching subjects).
- Source/test snapshot identifier (including uncommitted inputs): HEAD `b840b0add` plus sha256 `to-calendar-dialog.spec.ts` 4c368ca1…0f541, `task-detail.spec.ts` 851ead31…51ccd (only uncommitted changes); sha256 of all scoped sources, templates and specs taken before the run and `sha256sum -c` OK after it.
- Exact included files: `web/src/app/features/gtd/data/task-store.ts`, `.../inbox/inbox-actions.ts`, `.../inbox/inbox-page.ts`, `.../task-detail/task-detail.service.ts`, `.../task-detail/task-detail.ts`, `.../task-detail/to-calendar-dialog.ts`. Excluded: `inbox-page.html`, `task-detail.html`, `to-calendar-dialog.html` (templates, not instrumented as separate files; exercised through the component tests), `task-detail.scss`, `to-calendar-dialog.scss` (styles), spec files, `reviews/` (reports). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` 5.0.5 (Node 24.21.0); `npm run coverage -- --include='src/app/features/gtd/**/*.spec.ts'` in `web/`; all 31 gtd spec files. The scoped files are only imported by gtd specs and features.
- Run start/completion time and exit status: 2026-10-21T18:42:08Z – 18:42:19Z, exit 0 (735 passed, no failures).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 adad8fa4…df12fe, `coverage-summary.json` 704c53aa…48306, `lcov.info` b3c050bb…6ccb2 (written 18:42:19Z by this run).
- Evidence outputs are fresh and inputs stayed unchanged: yes.
- Final run after latest test additions and production fixes: yes (after the fixer's commit and all tests of this round).

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/data/task-store.ts` | 80/80 (100.0%) | 86/89 (96.6%) | 28/28 (100.0%) | 64/64 (100.0%) |
| `web/src/app/features/gtd/inbox/inbox-actions.ts` | 21/21 (100.0%) | 20/20 (100.0%) | 7/7 (100.0%) | 17/17 (100.0%) |
| `web/src/app/features/gtd/inbox/inbox-page.ts` | 183/188 (97.3%) | 104/110 (94.5%) | 39/40 (97.5%) | 154/155 (99.4%) |
| `web/src/app/features/gtd/task-detail/task-detail.service.ts` | 14/14 (100.0%) | 5/5 (100.0%) | 10/10 (100.0%) | 12/12 (100.0%) |
| `web/src/app/features/gtd/task-detail/task-detail.ts` | 215/218 (98.6%) | 143/152 (94.1%) | 50/50 (100.0%) | 175/175 (100.0%) |
| `web/src/app/features/gtd/task-detail/to-calendar-dialog.ts` | 77/77 (100.0%) | 48/50 (96.0%) | 23/23 (100.0%) | 62/62 (100.0%) |
| **Aggregate (summed counters, 6 files; templates/styles excluded as above)** | 590/598 (98.7%) | 406/426 (95.3%) | 157/158 (99.4%) | 484/485 (99.8%) |

The counters differ from the first round (e.g. `to-calendar-dialog.ts` 71→77 statements) because the
fixer moved `eventStartDay`/`browserTimeZone` there and removed the copies from task detail and inbox.
`eventStartDay` is now fully covered including the all-day-without-start-date fallback, which was a
justified gap in the first round.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `to-calendar-dialog.ts:72` (`\|\| 'UTC'`), `:102` (branch 0, compiler-generated at the class declaration) | Browser without a resolved zone; instrumentation artifact | Not reachable in jsdom; justified |
| `task-detail.ts:276` (guard `!task \|\| saving \|\| converting` in `toCalendar`), `:281` (branch 1, `this.task() ?? task`) | Guard against a second start; the button is disabled in those states | Covered through the disabled button (`cannot open the dialog while a save is running`, `shows the running conversion…`); the guard is only reachable programmatically; justified |
| `task-store.ts:57,95,113`; `inbox-page.ts:48,139,179,213,249,261,310,344`; `task-detail.ts:87,137,189,220,224,245,261` | Pre-existing code not changed by this task (all changed executable lines are covered: 117/117) | Out of scope |

## Unable to test

- Browser/E2E run of the dialog against the running stack: not run (no Playwright test for this flow,
  not named by the criteria).
- `npm run check` and `.taskcheck`: not run in this round on the parent's instruction; the parent runs
  `.taskcheck`, which includes `npm run check`.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production
defects found.
