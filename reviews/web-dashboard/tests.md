# Test Report

Date: 2026-10-22

Task: web-dashboard - Dashboard

Scope: task commits 018e04578, ade6627fc, 86ae4e8d2 (`git diff 4bdf1f58a81817bbf26bbda424261ed1e6d52906..86ae4e8d21749188deae866326be9cf98ed30a42`), on branch `main`, plus this run's uncommitted test files (listed below). There were no uncommitted production changes.

Requirements:
- `tasks/web-dashboard.md` acceptance criteria 1 and 2
- `SPEC.md` §8.7 (dashboard widgets), §4.7 (user settings)
- `docs/ARCHITECTURE.md` "Coverage"
- Parent instructions (web only; the server tests DashboardSettingsWebTests and SettingsServiceTests were run by the parent, because this helper cannot run Gradle)

## Test files changed

- `web/src/app/features/dashboard/dashboard-data.spec.ts` (new)
- `web/src/app/features/dashboard/dashboard-page.spec.ts` (extended; the `open()` helper got an optional `settingsFail` parameter, and the existing tests are unchanged)
- `web/src/app/features/dashboard/dashboard-layout.spec.ts` (extended)

## Tests added or updated

Count: 48 added (29 in dashboard-data.spec.ts, 14 in dashboard-page.spec.ts, 5 in dashboard-layout.spec.ts). The dashboard specs went from 19 to 67 tests. No existing test was changed or weakened.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Today widget: tasks (overdue + due today, deduplicated, overdue first), events (cancelled left out, range from local midnight), habits (due/done counts) | DashboardData > Today: `lists overdue then due-today tasks, each only once`, `counts today's due habits...`, `asks for today's occurrences from local midnight...`, `fails as a whole when one of its sources fails`; DashboardPage: `fills the Today widget with tasks, events and habits`, `says when nothing is due today...` | `cd web && npx ng test --watch=false --include 'src/app/features/dashboard/**/*.spec.ts'` | Pass |
| AC1 Inbox count (tasks + unread mail, with "N+" when the page is cut) | DashboardData: `counts the Inbox tasks and the unread mail, flagging cut pages`, `counts no unread mail when the mail page has no items`; DashboardPage (existing): `fills the widgets with counts and lists` | same | Pass |
| AC1 Stalled projects, Waiting For overdue, Upcoming events (7 days, cancelled left out), Read-later, Pinned notes, Last weekly review | DashboardData: `asks for the upcoming events from now until midnight in seven days...`, `loads overdue Waiting For tasks, stalled projects, the review status, read-later and pinned notes`; DashboardPage: `lists upcoming events, overdue Waiting For and stalled projects, with how many more`, `says so when there are no upcoming events...`, `shows the weekly review status` x5, `shows five entries of a list widget and how many more there are` | same | Pass |
| AC1 Each widget loads on its own, errors are not toasted, a failure only breaks its widget | DashboardData: `sends every request with silent errors...`, `keeps one widget working while another fails`, `does not load a ready widget again when shown again, but retries a failed one`; existing page test `shows a failed widget with a retry...` | same | Pass |
| AC1 Live refresh: change events (debounced), SSE resync, stale answers dropped | DashboardData > loading: `drops an older answer that arrives after a newer one`, `drops an older failure that arrives after a newer answer`, `drops an older answer when the newer request fails`; DashboardData > reloading: `reloads a shown widget once after a burst...`, `keeps the shown data while reloading, and keeps it when the reload fails`, `reloads a failed widget visibly after a change event`, `does not reload hidden widgets or widgets of other types`, `does not reload a widget hidden since it was shown`, `reloads %s after a %s change` x7, `reloads every shown widget, and only those, after a resync`, `stops reloading once destroyed` | same | Pass |
| AC1 show/hide and order saved in settings | dashboard layout: `leaves a layout without the widget unchanged...`, `shows or hides only the given widget...`, `skips a bare hidden suffix...`; DashboardLayout: `does not load the settings again once they are loaded`, `fails ensureLoaded when the settings cannot be loaded...`; DashboardPage: `shows the default layout when the settings fail, without letting it be changed`, `moves a widget up from the customize panel`, `rolls back a refused layout change`, `resets the layout to the default from the customize panel`; plus the 19 existing tests | same | Pass |
| AC2 Vitest tests cover widget configuration persistence | DashboardLayout and DashboardPage persistence tests above, plus the existing ones | same | Pass |
| AC2 `cd web && npm run check` passes | whole web check (api:check, lint, e2e:typecheck, test:ci, ng build) | `cd web && npm run check` | Pass (164 files / 3333 tests; the build shows the initial-bundle budget warning, 676.62 kB of 500 kB, a warning only, not caused by this task's lazy chunk) |
| Server `dashboardWidgets` setting | DashboardSettingsWebTests, SettingsServiceTests | Gradle (run by the parent) | Not run: this helper cannot run Gradle |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include 'src/app/features/dashboard/**/*.spec.ts'`

Result:

Pass: 3 files, 67 tests.

## Full test execution

Command:

`cd web && npm run coverage` (the full Vitest suite with coverage), then `cd web && npm run check`

Result:

Pass: 164 files, 3333 tests, both times. `npm run check` passed every step.

## .taskcheck

Result:

Not run. `sh scripts/check.sh` also runs the Gradle server and Android checks, which this helper cannot run (parent's instruction). Its web part (`npm run check`) passed, see above. The parent runs `.taskcheck`.

## Bugs found

None. No test exposed a production defect.

One maintainability note, not a bug: `DashboardPage.eventStart()` (`web/src/app/features/dashboard/dashboard-page.ts:110`) is called from nowhere. The template formats `event.start` / `event.startDate` directly. That makes it dead code, and it is the only uncovered function in the task (see Coverage). The parent or code-reviewer can decide whether to remove it.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. Every web production file in scope is new in this task, so whole-file coverage equals changed-line coverage for them.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-dashboard`; base 4bdf1f58a81817bbf26bbda424261ed1e6d52906 (given by the parent); HEAD 86ae4e8d21749188deae866326be9cf98ed30a42; task commits 018e04578, ade6627fc, 86ae4e8d2 (given by the parent and matching `git diff --stat 4bdf1f58a..HEAD`).
- Source/test snapshot identifier (including uncommitted inputs): HEAD 86ae4e8d2 with no uncommitted production changes. sha256 of the inputs: dashboard-data.ts e81f0431..., dashboard-layout.ts 1cec903c..., dashboard-page.ts ce658356..., dashboard-page.html cf37be5a..., dashboard.routes.ts d3eb978e..., dashboard-data.spec.ts 2e06d7de..., dashboard-layout.spec.ts 5e0d2c90..., dashboard-page.spec.ts 7ff459fa... Identical before and after the final run.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/dashboard/dashboard-data.ts`, `dashboard-layout.ts`, `dashboard-page.ts`, `dashboard-page.html` (v8 instruments the template), `dashboard.routes.ts`. Excluded: `dashboard-page.scss` (styles, no behavior); `server/src/main/kotlin/.../core/settings/infra/DashboardSettings.kt` (server; JaCoCo needs Gradle, which this helper cannot run, so it is unmeasured here; the parent should take its numbers from `./gradlew test jacocoTestReport` if needed); spec files (`app.spec.ts`, `app.i18n.spec.ts` and the dashboard specs). Nothing was renamed or deleted.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with @vitest/coverage-v8 (node v24.21.0); `npm run coverage` in `web/`; the full web suite (all 164 spec files).
- Run start/completion time and exit status: started 2026-10-22T08:30:21Z, finished about 08:31:05Z (test duration 33.48 s); exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 b87f5a5de078a832066a573a0d9f6ba95800f9cb6305c599bbd05668505a31b6; `web/coverage/web/coverage-summary.json` sha256 1044dffd6889bb3e5a67c71f087aa2f0c30c9477c06cf5fd4a0ffe42cb977743. Per-file counters came from the provider's summary (`.tmp/dash-cov.mjs`).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Vitest rewrote `coverage/` on this run: the hashes differ from the earlier run (bf3ecdc2...) and the test count went from 3329 to 3333. The input hashes match before and after.
- Final run after latest test additions and production fixes: yes, after the last test edits and formatting. There were no production fixes.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/dashboard/dashboard-data.ts` | 76/76 (100%) | 34/34 (100%) | 26/26 (100%) | 63/63 (100%) |
| `web/src/app/features/dashboard/dashboard-layout.ts` | 57/57 (100%) | 27/27 (100%) | 21/21 (100%) | 42/42 (100%) |
| `web/src/app/features/dashboard/dashboard-page.ts` | 37/38 (97.36%) | 16/19 (84.21%) | 17/18 (94.44%) | 29/30 (96.66%) |
| `web/src/app/features/dashboard/dashboard-page.html` | 330/330 (100%) | 75/87 (86.2%) | 7/7 (100%) | 202/202 (100%) |
| `web/src/app/features/dashboard/dashboard.routes.ts` | 1/1 (100%) | 0/0 (N/A) | 0/0 (N/A) | 1/1 (100%) |
| **Aggregate (summed counters, these 5 web files)** | 501/502 (99.80%) | 152/167 (91.02%) | 71/72 (98.61%) | 337/338 (99.70%) |

The aggregate excludes the server file `DashboardSettings.kt` (not measured here, needs Gradle/JaCoCo) and `dashboard-page.scss` (no behavior).

Before this run's tests, dashboard-data.ts was already at 100% only through the page tests. The page tests reached it indirectly, though, and did not check the dedup, habits, cancelled-filter, resync or stale-answer behavior. The new tests check that behavior directly. dashboard-page.html went from 307/330 statements and 70/87 branches to 330/330 and 75/87.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `dashboard-page.ts:110-111` (`eventStart`, both branches) | none: the method is called from nowhere | Dead code, see the note under Bugs found. No test added. |
| `dashboard-page.ts:51` (one branch) | Branch from compiler-generated component metadata (`providers`) | Instrumentation artifact, no behavior |
| `dashboard-page.html:114,174,200,230,257,266,280,314,338` (else branches of the inner `@if (x.status === 'ready')`) | Unreachable: the outer `@else` already guarantees `status === 'ready'`. The inner check only narrows the type. | Not reachable, no test |
| `dashboard-page.html:111` (the `@switch` default) | Unreachable: every WidgetId has a case | Not reachable |
| `dashboard-page.html:328` (Read-later "N+ more" when the page is cut) | Same logic as Pinned notes ("2+ more", tested) | Low risk, left out |
| `dashboard-page.html:350` (Pinned notes "more" without "+") | The opposite case is tested for Read-later ("1 more") | Low risk, left out |

## Unable to test

- The server `dashboardWidgets` setting (DashboardSettings.kt) and its JaCoCo coverage: Gradle can't run in this helper. The parent reports that DashboardSettingsWebTests and SettingsServiceTests pass. The parent can run `./gradlew test jacocoTestReport` if it needs server coverage numbers.
- `.taskcheck` as a whole: it includes Gradle. Its web part passed.
- Browser/E2E: neither criterion names one, so none was run.

## Summary

Tests added and all validation passed. Task-scoped web coverage is sufficient and no production defects were found. The only open point is a small dead-code note (`DashboardPage.eventStart`) for the parent or code-reviewer.
