# Test Report

Date: 2026-10-21

Task: web-gtd-convert-event — Inbox/task detail: to calendar

Scope: `git diff 7de4e6a303c48c0bff62fe777de0730c354fa9c2..d45cbf24a59f559cf8297896bab3248fd0b1ffc3 -- web`
(branch `main`, HEAD `d45cbf24a`). Task-owned commits at HEAD: `f89cf8772` (dialog, service, store,
task detail, inbox) and `d45cbf24a` (inbox tests, separate button, formatting). The parent named
`2d171060d`/`5c4603cc6`; those are the same two commits before a rebase (same subjects, the only
commits in base..HEAD). Ownership was established from the task file, the parent's base and the commit
subjects. Uncommitted inputs at measurement time: only the test files listed below.

Requirements:
- `tasks/web-gtd-convert-event.md` acceptance criteria and its three recorded assumptions
- Parent instructions (missing tests to add)
- `AGENTS.md`, `docs/ARCHITECTURE.md` "Coverage", existing spec conventions

## Test files changed

- `web/src/app/features/gtd/task-detail/to-calendar-dialog.spec.ts` (new)
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts`
- `web/src/app/features/gtd/task-detail/task-detail.service.spec.ts`
- `web/src/app/features/gtd/data/task-store.spec.ts`

## Tests added or updated

Count: 48 (dialog spec 25, task-detail spec 18, service spec 2, store spec 3). No existing test was
changed, weakened or skipped.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Dialog picks calendar (writable only, default preselected, first writable as fallback) | `ToCalendarDialog` › `names the task and preselects the default calendar among the writable ones`, `preselects the first writable calendar when none is the default`, `converts the chosen calendar and typed times` | F1 | Pass |
| AC1 Dialog picks time: next full hour / +1 h in the settings' zone; settings loaded late; settings load failure; typed times kept | `starts at the next full hour…`, `moves the default to the next day after 23:00`, `loads missing settings…`, `keeps times the user typed…`, `still converts in the browser zone…` | F1 | Pass |
| AC1 All-day request with exclusive end date | `converts an all-day event with an exclusive end date…`, `converts a one-day all-day event…`, `convertRequest` › both tests | F1 | Pass |
| AC1 Validation: end before / equal to start, missing time/date | `refuses an end before the start…`, `refuses an event that ends when it starts`, `refuses an all-day event ending on an earlier day`, `does not convert a timed event without its times or a date`, `endsAfterStart` › both | F1 | Pass |
| AC1 No writable calendar; calendars loading; load error + retry | `says so and cannot convert when there is no writable calendar`, `loads the calendars when they are not loaded yet…`, `offers a retry when the calendars cannot be loaded` | F1 | Pass |
| AC1 Cancel / dialog plumbing | `closes without a request on Cancel`, `askForCalendar` › both | F1 | Pass |
| AC1 Unsaved warning | `warns that unsaved changes are not part of the event`; task detail `tells the dialog about unsaved changes and converts the saved version without saving` | F1 | Pass |
| AC1 Converts: service (If-Match, body, silent errors) | `TaskDetailService` › `converts a task to an event with If-Match…`, `passes a failed conversion to an event on to the caller` | F1 | Pass |
| AC1 Converts: store (optimistic removal, rollback, stale list answer) | `TaskStore` › `convertToEvent` › 3 tests | F1 | Pass |
| AC1 Converts: task detail (cancel, success + navigate `/calendar?date=`, day in event zone, no-zone fallback, all-day day, reloaded version, running state, save running/started, 412/400/500/unexpected errors, editor switched task before/after) | `TaskDetail` › `to calendar` › 18 tests | F1 | Pass |
| AC1 Converts: inbox processing (key C, dialog, status + Open calendar link) | existing `InboxPage` › `To calendar` tests (not written by me) | F2 | Pass |
| AC1 in a real browser against the running stack | none | — | Not run: the criterion does not name an E2E/browser run and no Playwright test exists for this flow; covered by Vitest component tests only |
| AC2 Vitest tests cover convert flow; `cd web && npm run check` passes | all of the above | C1 | Pass |

Commands:
- F1: `cd web && npx ng test --watch=false --include='src/app/features/gtd/task-detail/**/*.spec.ts' --include='src/app/features/gtd/data/task-store.spec.ts'` → 8 files, 276 tests passed (before the last added test; the last one passed in F2/C1)
- F2: `cd web && npx ng test --watch=false --include='src/app/features/gtd/**/*.spec.ts'` (run via the coverage command, see Coverage) → 31 files, 716 passed
- C1: `cd web && npm run check` → exit 0

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/gtd/task-detail/to-calendar-dialog.spec.ts'` → 25 passed;
then F1 above → 276 passed; `npx ng lint --max-warnings=0` → "All files pass linting.";
`npx prettier --check src/app/features/gtd/task-detail src/app/features/gtd/data` → all formatted.

Result:

Pass

`npx prettier --check src/app/features/gtd` reports 3 files that this task did not touch
(`projects/markdown.ts`, `projects/markdown.spec.ts`, `ui/context-chips.ts`): pre-existing, out of scope.

## Full test execution

Command:

`cd web && npm run check` (api:check, lint, e2e:typecheck, `ng test --watch=false`, `ng build`), 2026-10-21 18:31:55Z–18:32:45Z

Result:

Pass — exit 0; 121 test files, 2417 tests passed; build succeeded with the bundle budget warning
(initial 657.72 kB > 500 kB), which is a warning, not an error, and not introduced by test files.

## .taskcheck

Result:

Not run: `sh scripts/check.sh` also runs the Gradle builds of server and android, which this helper
environment cannot run reliably (known limitation for sub-agents); the task changes only `web/`, whose
part of the check (`npm run check`) passed above. The parent must run `.taskcheck`.

## Bugs found

None. No production defect was found; all new tests pass against HEAD `d45cbf24a`.

## Coverage

Status: verified (whole-file and changed-line, for all task-changed production `.ts` files).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured — executable lines from the provider's `lcov.info` (`DA:` records)
intersected with added/modified lines of `git diff -U0 7de4e6a30..HEAD`: 110/111 (99.1%).
Changed-branch coverage not inferred.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-gtd-convert-event`; base `7de4e6a303c48c0bff62fe777de0730c354fa9c2`; HEAD `d45cbf24a59f559cf8297896bab3248fd0b1ffc3`; commits `f89cf8772`, `d45cbf24a` (the only commits in base..HEAD).
- Source/test snapshot identifier (including uncommitted inputs): HEAD `d45cbf24a` plus sha256 `to-calendar-dialog.spec.ts` 3e8ae1fa…900f1c, `task-detail.spec.ts` e10b07ca…139c97, `task-detail.service.spec.ts` 85e99ec0…a09ad, `task-store.spec.ts` d04aa3ce…7843; identical before and after the run.
- Exact included files: `web/src/app/features/gtd/data/task-store.ts`, `.../inbox/inbox-actions.ts`, `.../inbox/inbox-page.ts`, `.../task-detail/task-detail.service.ts`, `.../task-detail/task-detail.ts`, `.../task-detail/to-calendar-dialog.ts`. Excluded: `inbox-page.html`, `task-detail.html`, `to-calendar-dialog.html` (templates, not instrumented by v8 as separate files; exercised through the component tests), `task-detail.scss`, `to-calendar-dialog.scss` (styles), `inbox-actions.spec.ts`, `inbox-page.spec.ts` (tests). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` (Node 24.21.0); `npm run coverage -- --include='src/app/features/gtd/**/*.spec.ts'` in `web/`; all gtd specs (31 files). The scoped files are only imported by gtd specs and features, so the gtd selection measures them fully.
- Run start/completion time and exit status: 2026-10-21T18:31:38Z – 18:31:51Z, exit 0, 716 tests passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 8225afb8…c534b; `coverage-summary.json` 6886febb…723e; `lcov.info` 8fea2a75…2ef (rewritten by this run; Vitest cleans `coverage/`).
- Evidence outputs are fresh and inputs stayed unchanged: yes (hashes above taken before and after).
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/data/task-store.ts` | 75/75 (100.0%) | 75/78 (96.2%) | 27/27 (100.0%) | 60/60 (100.0%) |
| `web/src/app/features/gtd/inbox/inbox-actions.ts` | 21/21 (100.0%) | 20/20 (100.0%) | 7/7 (100.0%) | 17/17 (100.0%) |
| `web/src/app/features/gtd/inbox/inbox-page.ts` | 184/190 (96.8%) | 110/120 (91.7%) | 39/41 (95.1%) | 155/157 (98.7%) |
| `web/src/app/features/gtd/task-detail/task-detail.service.ts` | 14/14 (100.0%) | 5/5 (100.0%) | 10/10 (100.0%) | 12/12 (100.0%) |
| `web/src/app/features/gtd/task-detail/task-detail.ts` | 211/214 (98.6%) | 147/158 (93.0%) | 49/49 (100.0%) | 173/173 (100.0%) |
| `web/src/app/features/gtd/task-detail/to-calendar-dialog.ts` | 71/71 (100.0%) | 36/38 (94.7%) | 22/22 (100.0%) | 57/57 (100.0%) |
| **Aggregate (summed counters, 6 files; templates/styles excluded as above)** | 576/585 (98.5%) | 393/419 (93.8%) | 154/156 (98.7%) | 474/476 (99.6%) |

Before the new tests (first run, same command, 18:30:38Z–18:30:54Z, 715 tests) the task-detail file
was at 172/173 lines; the added "no zone" test covered `localZone()` there.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `inbox-page.ts:360-361` (branch 1), `:366` (`localZone`, `\|\| 'UTC'`) | Inbox status link date for an event answer without `startDate` (all-day) or without `timeZone` (timed): defensive fallbacks for an incomplete server answer | Not added: same fallback is tested in task detail (`opens the calendar on the start day in the local zone when the event has no zone`); low risk. Optional follow-up. |
| `task-detail.ts:417` (branch 1: all-day without `startDate` → `localDate()`), `:422` (`\|\| 'UTC'`) | Defensive fallbacks for malformed event / browser without a zone | Not reachable with a valid `EventResponse` or in jsdom; justified gap |
| `task-detail.ts:277` (branch 0: guard `!task \|\| saving \|\| converting` in `toCalendar`), `:282` (branch 1: `this.task() ?? task`) | Guard against a second start; the button is disabled in those states so a click never reaches it | Behavior covered through the disabled button (`cannot open the dialog while a save is running`, `shows the running conversion…`); the guard itself is only reachable programmatically |
| `to-calendar-dialog.ts:71` (`\|\| 'UTC'`), `:95` (branch 0: compiler-generated) | Browser without a resolved zone; instrumentation artifact | Not reachable in jsdom; justified |
| `task-store.ts:57,95,113`; `inbox-page.ts:49,180,214,250,262,311,345`; `task-detail.ts:88,138,190,221,225,246,262` | Pre-existing code not changed by this task | Out of scope |

## Unable to test

- Browser/E2E run of the dialog against the running stack: not run (no Playwright test for this flow,
  not named by the criteria). The Vitest component tests render the real dialog with Material
  components in jsdom.
- `.taskcheck` (`scripts/check.sh`): not run here, see above; the parent must run it.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production
defects found.
