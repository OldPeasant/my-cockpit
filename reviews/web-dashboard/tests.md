# Test Report

Date: 2026-10-22

Task: web-dashboard - Dashboard (second pass, on the code after the fixes for CR-1..CR-6)

Scope: task commits bd941beaa, 8e2d6e60c, 47814add1, 5149158eb, f682b8125 (`git diff d83c9595c3450a47b1d5d3ba5ccb579a366cc077..f682b8125e23dd2a73257c9003c949c0c4d5894c`) on branch `main`, plus this run's uncommitted test changes (listed below). ef1409e0e touches only `tasks/` and is not part of the code. While this run was going on, the parent rebased the branch: f682b8125 is the rebased form of fix commit f4a9b4903, and its production patch is the same. This pass looked mainly at that fix commit: the pending-change queue in `DashboardLayout` (`dashboard-layout.ts`) and `DashboardData.show` (`dashboard-data.ts`). No production changes were left uncommitted. `reviews/web-dashboard/review.md` shows as modified in the working tree. This helper did not change it (the code-reviewer did).

Requirements:
- `tasks/web-dashboard.md` acceptance criteria 1 and 2, and the task's recorded assumptions
- `SPEC.md` §8.7 (dashboard widgets), §4.7 (user settings)
- `docs/ARCHITECTURE.md` "Coverage"
- `reviews/web-dashboard/review.md` (CR-1..CR-6) and `reviews/web-dashboard/fixes.md`
- Parent instructions (web only: this helper cannot run Gradle)

## Test files changed

- `web/src/app/features/dashboard/dashboard-layout.spec.ts` (extended)
- `web/src/app/features/dashboard/dashboard-data.spec.ts` (extended)

## Tests added or updated

Count: 7 added in this pass (4 in dashboard-layout.spec.ts, 3 in dashboard-data.spec.ts). No existing test was changed. The dashboard specs went from 77 to 84 tests.

dashboard-layout.spec.ts (`DashboardLayout`, the CR-1 queue):
- `drops a change when the settings cannot be loaded, and saves the next one once they load`: the change is rejected, it no longer shows, no PATCH is sent, and the queue still works afterwards.
- `applies every change made before the settings are loaded on the stored layout`: two quick changes before the GET answers both build on the stored layout, not on the default one.
- `builds a queued change on the layout another device saved when the earlier save hits a 412`: after a 412, `SettingsService` reloads the settings. The next queued change is computed from the reloaded layout and sent with the new ETag. The refused change drops out.
- `brings the stored layout back when a reset is refused`.

dashboard-data.spec.ts (`DashboardData.show`, CR-5):
- `keeps the old data when the quiet reload after showing a widget again fails`.
- `loads a widget visibly when it is shown again after its first load failed while hidden`.
- `drops the answer of a quiet re-show reload that a newer change-event reload overtook`: the answers come back in reverse order, controlled with deferred promises.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Today widget (overdue + due-today tasks, deduplicated; today's events without cancelled ones; habits due/done) | DashboardData > Today (4 tests); DashboardPage: `fills the Today widget with tasks, events and habits`, `says when nothing is due today...` | `cd web && npx ng test --watch=false --include 'src/app/features/dashboard/**/*.spec.ts'` | Pass |
| AC1 Inbox count (tasks + unread mail; "N+" only when a cut page has unread mail, CR-2) | DashboardData: `counts the Inbox tasks and the unread mail, flagging cut pages`, `counts no unread mail when the mail page has no items`, `flags the unread mail count as cut only when the cut page has unread mail`; DashboardPage: `fills the widgets with counts and lists` | same | Pass |
| AC1 Stalled projects, Waiting For overdue, Upcoming events, Read-later, Pinned notes, Last weekly review (red figure and Start from 7 days on, CR-3) | DashboardData: `asks for the upcoming events...`, `loads overdue Waiting For tasks, stalled projects, the review status, read-later and pinned notes`; DashboardPage: list/empty-state tests, `shows the weekly review status` x5, `marks the weekly review as due after %i days` x2 | same | Pass |
| AC1 Widgets load on their own, without toasts; live refresh (change events, resync, stale answers dropped; no WEEKLY_REVIEW event, CR-4) | DashboardData > loading and reloading (incl. `does not reload the weekly review on change events`), DashboardPage `shows a failed widget with a retry...` | same | Pass |
| AC1 A widget shown again after being hidden reloads quietly; a running first load is not repeated (CR-5) | DashboardData: `reloads a loaded widget quietly when it is shown again after being hidden`, `does not request a widget again while its first load is running`, **new:** `keeps the old data when the quiet reload after showing a widget again fails`, `loads a widget visibly when it is shown again after its first load failed while hidden`, `drops the answer of a quiet re-show reload that a newer change-event reload overtook`; DashboardPage: `keeps a widget's data on screen while it reloads after being shown again` | same | Pass |
| AC1 show/hide and order saved in settings, including quick changes during an unanswered save (CR-1) | DashboardLayout: `saves a hidden widget...`, `saves a new order...`, `builds quick changes on each other while a save is unanswered`, `drops a refused change but keeps the quick changes made after it`, `builds a change made right after a reset on the default layout`, `loads the settings before a change...`, `rolls a refused change back`, `resets the layout to the setting default`, **new:** the 4 queue tests listed above; DashboardPage customize-panel tests (hide, move up/down, all hidden, reset, refused change, settings failure) | same | Pass |
| AC2 Vitest component/service tests cover widget configuration persistence | DashboardLayout (service) and DashboardPage (component) persistence tests above | same | Pass |
| AC2 `cd web && npm run check` passes | api:check, lint, e2e:typecheck, test:ci, ng build | `cd web && npm run check` | Pass (164 files / 3350 tests; the build shows the existing initial-bundle budget warning, 676.62 kB of 500 kB, a warning only) |
| Server `dashboardWidgets` setting (`DashboardSettings.kt`) | DashboardSettingsWebTests, SettingsServiceTests | Gradle | Not run: this helper cannot run Gradle. The parent runs them. |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include 'src/app/features/dashboard/**/*.spec.ts'`

Result:

Pass: 3 files, 84 tests. `npx prettier --check src/app/features/dashboard` also passes.

## Full test execution

Command:

`cd web && npm run coverage` (the full Vitest suite with coverage), then `cd web && npm run check`

Result:

Pass: 164 files, 3350 tests, both times. Every step of `npm run check` passed ("All files pass linting", the build wrote `dist/web`).

## .taskcheck

Result:

Not run. `sh scripts/check.sh` also runs the Gradle server and Android checks, which this helper cannot run. Its web part (`npm run check`) passed, see above. The parent runs `.taskcheck`.

## Bugs found

None. No new or existing test exposed a production defect.

Two observations. Neither is a requirement violation, and no test was written for them:
- `DashboardData.show` deliberately leaves alone a widget whose first load is still running (the doc comment says so). If that widget is hidden while the load runs, a change event that arrives in that window is ignored, and showing the widget again does not reload it. Its data can then predate that change until the next event or resync. The window is narrow (a first request still unanswered), so this is low impact.
- `DashboardLayout.change` takes a change out of `pending` right before it calls `SettingsService.update`. That service queues its own saves. If a save of another setting were still unanswered at that moment, the layout change would be hidden until that save answers. Nothing in the app does this today (no other settings writer can run while the dashboard is open), so no test was written.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured as a separate metric. Every web production file in scope is new in this task, so for these files whole-file coverage equals changed-line coverage.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-dashboard`; base d83c9595c3450a47b1d5d3ba5ccb579a366cc077 (`git merge-base HEAD origin/main`); measured HEAD f682b8125e23dd2a73257c9003c949c0c4d5894c; task commits from `git log origin/main..HEAD` (as the parent instructed), checked against `git diff --stat` base..HEAD and the task's `touches` (`web/src/app/features/dashboard`, plus the recorded assumption for the server setting).
- Source/test snapshot identifier (including uncommitted inputs): HEAD f682b8125 plus the two uncommitted spec files. sha256 (first 16 hex digits): dashboard-data.ts 4937f5c549b19cd1, dashboard-layout.ts 0c37c881e9d545b3, dashboard-page.ts 0f0985f34e1899a6, dashboard-page.html 876063361c319f61, dashboard.routes.ts d3eb978edd027d6c, dashboard-data.spec.ts 7b6ee93228221f96, dashboard-layout.spec.ts 4437f60fa7450605, dashboard-page.spec.ts 2066d95741568021. The hashes were identical before and after the coverage run (`diff` against `.tmp/dash-hashes-before.txt`).
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/dashboard/dashboard-data.ts`, `dashboard-layout.ts`, `dashboard-page.ts`, `dashboard-page.html` (v8 instruments the template), `dashboard.routes.ts`. Excluded: `dashboard-page.scss` (styles, no behavior). Also excluded: `server/src/main/kotlin/ch/sonensei/cockpit/core/settings/infra/DashboardSettings.kt`. It is server code, JaCoCo needs Gradle, and this helper cannot run Gradle, so this file is unmeasured, not covered. Spec files are excluded as well. No file was renamed or deleted.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with @vitest/coverage-v8; `npm run coverage` in `web/`; the full web suite (164 spec files).
- Run start/completion time and exit status: started 2026-10-22T08:40:21Z (local 10:40:21), report written 08:40:53Z, test duration 31.76 s; exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 20f30c45e298c383556fbe20885e6d92accce4fb049281f71e68ff0410e6ddaf; `web/coverage/web/coverage-summary.json` sha256 c428de9f09be23157e9c84e6329195b6470c848a0407c9468c820abff50e9b8c. Per-file counters were read from the provider's report with `.tmp/dash-cov.mjs`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report has the time of this run, its hashes differ from the previous run's (b87f5a5d...), and the test count went from 3343 to 3350. The input hashes did not change during the run.
- Final run after latest test additions and production fixes: yes. The run came after the last test edit, on the code after the fixes (f682b8125).

Values below use covered/total (percentage); zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/dashboard/dashboard-data.ts` | 82/82 (100%) | 42/42 (100%) | 26/26 (100%) | 68/68 (100%) |
| `web/src/app/features/dashboard/dashboard-layout.ts` | 80/80 (100%) | 32/32 (100%) | 33/33 (100%) | 58/58 (100%) |
| `web/src/app/features/dashboard/dashboard-page.ts` | 37/37 (100%) | 16/17 (94.11%) | 17/17 (100%) | 29/29 (100%) |
| `web/src/app/features/dashboard/dashboard-page.html` | 330/330 (100%) | 75/87 (86.2%) | 7/7 (100%) | 202/202 (100%) |
| `web/src/app/features/dashboard/dashboard.routes.ts` | 1/1 (100%) | 0/0 (N/A) | 0/0 (N/A) | 1/1 (100%) |
| **Aggregate (summed counters, these 5 web files)** | 530/530 (100%) | 165/178 (92.70%) | 83/83 (100%) | 358/358 (100%) |

The aggregate excludes the server file `DashboardSettings.kt` (unmeasured: it needs Gradle/JaCoCo) and `dashboard-page.scss` (no behavior).

Before this pass, the fixed queue and `show()` code already showed 100% statements and branches. The new tests check behavior those counters do not show: a load failure inside the queue, changes made before the settings load, rebuilding on the server state after a 412, a refused reset, and the failure and reverse-order paths of the quiet reload when a widget is shown again.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `dashboard-page.ts:50` (one branch) | Branch from compiler-generated component metadata (`providers: [DashboardData]`) | Instrumentation artifact, no behavior |
| `dashboard-page.html:114,174,200,230,257,266,280,314,338` (else branches of the inner `@if (x.status === 'ready')`) | Unreachable: the outer `@else` already guarantees `status === 'ready'`. The inner check only narrows the type. | Not reachable, no test |
| `dashboard-page.html:111` (the `@switch` default) | Unreachable: every WidgetId has a case | Not reachable |
| `dashboard-page.html:328` (Read-later "N+ more" when the page is cut) | Same template logic as Pinned notes ("2+ more", tested) | Low risk, left out |
| `dashboard-page.html:350` (Pinned notes "more" without "+") | The opposite case is tested for Read-later ("1 more") | Low risk, left out |

## Unable to test

- The server `dashboardWidgets` setting (`DashboardSettings.kt`) and its JaCoCo coverage: this helper cannot run Gradle. The parent runs DashboardSettingsWebTests / SettingsServiceTests (or `./gradlew test jacocoTestReport` if it needs server coverage). Follow-up: parent.
- `.taskcheck` as a whole: it includes Gradle. Its web part passed.
- Browser/E2E: neither criterion names one, so none was run.

## Summary

Tests added and all validation passed. Task-scoped web coverage is sufficient, and no production defects were found in the fixed code. Server coverage and `.taskcheck` remain for the parent, because they need Gradle.
