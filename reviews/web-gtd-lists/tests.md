# Test Report

Date: 2026-10-20

Task: web-gtd-lists: Waiting For, Someday, Scheduled, Logbook views

Scope: `git diff fb311883e8797cb88b2e5624280001e9f8349870..1613d825d43987781113750b1e05f374edbd0ff0`
on branch `main`. Task commits 23dde64f, ce2eebce, 1613d825 (rebased copies of the commits named by
the parent, 18038b9e, b5b95050, 216b7319; `git patch-id` identical pairwise). The other commits in
the range only touch `tasks/*.md` (heartbeats/assumptions of other agents) and are excluded.
Uncommitted inputs at measurement: only the three test files changed by this report (see below).

Requirements:
- `tasks/web-gtd-lists.md` acceptance criteria and recorded assumptions
- `SPEC.md` §5.1, §5.3 (Waiting For sorted by `waitingSince` with "follow up"; Logbook)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`, `docs/QUALITY.md`

## Test files changed

- `web/src/app/features/gtd/lists/list-pages.spec.ts` (19 tests added, a `hold()` helper that
  keeps a fake API call pending until the test releases it)
- `web/src/app/features/gtd/lists/list-views.spec.ts` (1 test added)
- `web/src/app/app.i18n.spec.ts` (1 test updated: see "Existing test broken by the task")

## Tests added or updated

Count: 21 (20 added, 1 updated)

Added:
- Waiting For: `accents a wait only once it is longer than a week` (8 days long, exactly 7 not,
  `1 day` singular, future `waitingSince` shows `Today`); `follow up keeps the project of the waiting
  task`; `creates only one follow up while one is running` (button disabled while pending, other
  rows stay enabled, a forced second click creates nothing, status after release);
  `shows a loading state until the list arrives`.
- Someday: `completes a someday task from its checkbox`; `moves the task only once on a double
  activate`.
- Scheduled: `heads past dates with Yesterday and undated tasks with No date`; `completes a
  scheduled task from its checkbox`.
- Logbook: `heads older days with their full date`; `shows done tasks with a checked checkbox`.
- `Someday|Scheduled|Logbook shared behavior` (3 pages each): `shows a loading state until the
  list arrives`, `opens a task from its title`, `shows an error with a retry that loads the list`.
- `followUpTask`: `treats a blank person as no person`.

### Existing test broken by the task

`app.i18n.spec.ts > translates the shell and placeholder texts through their message ids` failed on
the task's HEAD (`expected undefined to be 'Irgendwann'`): it navigated to `/someday` and expected
the `app-coming-soon` placeholder, which this task replaced with the real Someday page. The test's
own comment says it must use "a section that still shows the placeholder". I moved it to
`/bookmarks` (still a placeholder; `web-bookmarks` is `todo`) with the same assertions
(`nav.bookmarks` -> `Lesezeichen`). No assertion was removed. Without this change
`npm run check` and `.taskcheck` fail on the task's code. The parent should commit it with the task.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Waiting For sorted by `waitingSince` | `byWaitingSince *`, `loads the WAITING list sorted by waitingSince, oldest first` | `cd web && npx ng test --watch=false --include 'src/app/features/gtd/lists/**/*.spec.ts' --include src/app/app.i18n.spec.ts` | Pass |
| AC1 'follow up' creates a task | `follow up creates a next action and keeps the waiting task`, `follow up keeps the project of the waiting task`, `creates only one follow up while one is running`, `announces nothing when the follow up fails`, `followUpTask *` | same | Pass |
| AC1 waiting age display (assumption) | `shows how long each task waits...`, `accents a wait only once it is longer than a week`, `daysBetween *` | same | Pass |
| AC1 Someday with 'activate' | `activate moves the task to Next and out of the list`, `brings the task back when activating fails`, `moves the task only once on a double activate` | same | Pass |
| AC1 Scheduled grouped by date | `groupByScheduledDate *`, `loads the SCHEDULED list grouped by date, earliest first`, `heads past dates with Yesterday and undated tasks with No date`, `relativeDay *` | same | Pass |
| AC1 Logbook (completed, by day, uncomplete) | `groupByCompletedDay *`, `loads completed tasks of all lists grouped by day, newest first`, `heads older days with their full date`, `shows done tasks with a checked checkbox`, `uncompletes a task from its checkbox...`, `keeps the task when uncompleting fails` | same | Pass |
| Loading / error / retry / empty states, open, complete (all four views) | `* shared behavior *`, Waiting `shows a load error with a retry`, `shows an empty state` (x4), `opens a task from its title`, `completes ...` | same | Pass |
| AC2 Vitest tests cover sorting and actions; `cd web && npm run check` passes | all of the above | `cd web && npm run check` | Pass (47 files, 805 tests at the first green run; 812 in `.taskcheck` after the last additions; build OK) only with the `app.i18n.spec.ts` update; Fail on the task's committed HEAD without it |
| AC3 Playwright `e2e/gtd-lists/lists.spec.ts` against the e2e stack | `web/e2e/gtd-lists/lists.spec.ts` | Not run: per parent instructions (already run by the parent; spec unchanged here) | Not run: the parent's evidence covers it; this report did not execute it |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include 'src/app/features/gtd/lists/**/*.spec.ts' --include src/app/app.i18n.spec.ts`

Result:

Pass (3 files, 58 tests). Prettier `--check` and `eslint --max-warnings=0` on the changed spec
files: pass.

## Full test execution

Command:

`cd web && npm run check`

Result:

Pass (after the `app.i18n.spec.ts` update). Before it: Fail, 1 failed / 804 passed
(`app.i18n.spec.ts`, see above).

## .taskcheck

Result:

Pass: `sh scripts/check.sh` on the final snapshot -> `== all checks passed` (tfp gate, server
ktlint + tests, web eslint + tests (47 files, 812 tests) + build, android ktlint + unit tests + lint).

## Bugs found

No production defects found.

Note for the parent (test defect, not a production bug, already fixed in a test file): the task's
HEAD fails `npm run check` / `.taskcheck` because `app.i18n.spec.ts` relied on `/someday` being a
placeholder. The update must be committed with the task.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (every scoped file is new or a one-line change; whole-file
numbers are given instead).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-gtd-lists`;
  base `fb311883e8797cb88b2e5624280001e9f8349870`; HEAD `1613d825d43987781113750b1e05f374edbd0ff0`;
  commits 23dde64f, ce2eebce, 1613d825 (patch-id equal to the parent-supplied 18038b9e, b5b95050,
  216b7319); files from `git diff --name-only fb311883..HEAD -- web/src` minus `*.spec.ts`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 over the sorted
  task-changed `web/src` files plus working-tree contents = `e61756f75cda0db4...e8b9` before and
  after the run; spec hashes: `list-pages.spec.ts` a7b3c2d6..., `list-views.spec.ts` 8f454539...,
  `app.i18n.spec.ts` 6ee032fd....
- Exact included files: `web/src/app/features/gtd/gtd.routes.ts`, `web/src/app/shell/nav-sections.ts`,
  and in `web/src/app/features/gtd/lists/`: `list-page.ts`, `list-views.ts`, `waiting-page.ts/.html`,
  `someday-page.ts/.html`, `scheduled-page.ts/.html`, `logbook-page.ts/.html`, `waiting.routes.ts`,
  `someday.routes.ts`, `scheduled.routes.ts`, `logbook.routes.ts`. Excluded: `lists.scss` (stylesheet,
  not instrumented), `web/src/app/features/README.md` (docs), `app.spec.ts` and `*.spec.ts` (tests),
  `web/e2e/gtd-lists/lists.spec.ts` (e2e test). No renamed or deleted files. No file missing from the report.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8`
  (node v24.21.0), `npm run coverage`, `web/`, the full web unit suite (47 files, 812 tests).
- Run start/completion time and exit status: 2026-10-20T23:47:00+02:00 to 23:47:19+02:00; all tests
  passed, summary printed, report files rewritten.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json`
  (sha256 af418a7f...c9a2), `web/coverage/web/coverage-final.json` (sha256 277b2ae5...554f), both
  written 23:47:18; per-file counters read from `coverage-summary.json`, uncovered locations from
  `coverage-final.json`.
- Evidence outputs are fresh and inputs stayed unchanged: yes (file times inside the run window;
  snapshot hash identical before and after).
- Final run after latest test additions and production fixes: yes (no production changes were made).

Values below use covered/total (percentage); zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/gtd.routes.ts` | 8/8 (100%) | N/A | 7/7 (100%) | 8/8 (100%) |
| `web/src/app/shell/nav-sections.ts` | 3/3 (100%) | N/A | 1/1 (100%) | 2/2 (100%) |
| `web/src/app/features/gtd/lists/list-page.ts` | 28/28 (100%) | 8/9 (88.9%) | 8/8 (100%) | 24/24 (100%) |
| `web/src/app/features/gtd/lists/list-views.ts` | 44/44 (100%) | 22/22 (100%) | 14/14 (100%) | 33/33 (100%) |
| `web/src/app/features/gtd/lists/waiting-page.ts` | 16/16 (100%) | 15/17 (88.2%) | 8/8 (100%) | 10/10 (100%) |
| `web/src/app/features/gtd/lists/waiting-page.html` | 69/69 (100%) | 10/10 (100%) | 4/4 (100%) | 37/37 (100%) |
| `web/src/app/features/gtd/lists/someday-page.ts` | 9/9 (100%) | 10/11 (90.9%) | 5/5 (100%) | 5/5 (100%) |
| `web/src/app/features/gtd/lists/someday-page.html` | 40/40 (100%) | 2/2 (100%) | 4/4 (100%) | 22/22 (100%) |
| `web/src/app/features/gtd/lists/scheduled-page.ts` | 9/9 (100%) | 10/11 (90.9%) | 3/3 (100%) | 5/5 (100%) |
| `web/src/app/features/gtd/lists/scheduled-page.html` | 50/50 (100%) | 10/10 (100%) | 3/3 (100%) | 28/28 (100%) |
| `web/src/app/features/gtd/lists/logbook-page.ts` | 9/9 (100%) | 10/11 (90.9%) | 3/3 (100%) | 5/5 (100%) |
| `web/src/app/features/gtd/lists/logbook-page.html` | 44/44 (100%) | 6/6 (100%) | 3/3 (100%) | 25/25 (100%) |
| `web/src/app/features/gtd/lists/waiting.routes.ts` | 1/1 (100%) | N/A | N/A | 1/1 (100%) |
| `web/src/app/features/gtd/lists/someday.routes.ts` | 1/1 (100%) | N/A | N/A | 1/1 (100%) |
| `web/src/app/features/gtd/lists/scheduled.routes.ts` | 1/1 (100%) | N/A | N/A | 1/1 (100%) |
| `web/src/app/features/gtd/lists/logbook.routes.ts` | 1/1 (100%) | N/A | N/A | 1/1 (100%) |
| **Aggregate (summed counters, 16 files)** | 333/333 (100%) | 103/109 (94.5%) | 63/63 (100%) | 208/208 (100%) |

Aggregate excludes `lists.scss` (not instrumented) and `features/README.md` (docs); no scoped file is
unmeasured.

Before the added tests (same command, 23:44:42 run, snapshot without the last additions) the
aggregate was 319/333 statements, 59/63 functions: the Someday row open/complete listeners, the
Scheduled/Logbook open listeners and the loading states of Someday/Scheduled/Logbook were unexercised.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `list-page.ts:12`, `waiting-page.ts:17`, `someday-page.ts:12`, `scheduled-page.ts:13`, `logbook-page.ts:16` (one branch each) | None: branches reported on the `@Directive`/`@Component` decorator line, emitted by the Angular compiler (class metadata/factory) | Justified gap, not reachable from behavior |
| `waiting-page.ts:35` (`this.days(task) ?? 0` fallback) | Accent for a task without `waitingSince` | Justified gap: the template only calls `long()` inside `@if (days(task))`, so `days` is never undefined there |

## Unable to test

- AC3 Playwright e2e (`web/e2e/gtd-lists/lists.spec.ts` against the e2e stack): not run by me, as
  instructed by the parent (it has its own run evidence; this report did not change the spec).
- Day headings near midnight in a time zone different from the server's: tests use the browser's
  local date, consistent with the recorded assumption; no follow-up needed.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. One pre-existing test
(`web/src/app/app.i18n.spec.ts`) was broken by the task (it relied on `/someday` being a placeholder)
and was updated to another placeholder section; that change must be committed with the task or
`npm run check` / `.taskcheck` fail.
