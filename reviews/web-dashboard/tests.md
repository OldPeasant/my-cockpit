# Test Report

Date: 2026-10-22

Task: web-dashboard - Dashboard (third pass, on the final code after the CR-7 fix)

Scope: `git diff d54b8b3c0186aa51a8db33aa61e2f7ef7fad5c3a..9c2e59fce1eb6b70fd38c800ece61a9d6e2c0bbc` on branch `main` (base = `git merge-base HEAD origin/main`, as given by the parent). Task commits: 754456300, fe13b3300, e3f6ce790, 9d27831c5, 6a54f71c5, adda0e2ca, 9c2e59fce. Plus this pass's uncommitted test change in `dashboard-layout.spec.ts`. This pass looked mainly at 9c2e59fce (CR-7): in `DashboardLayout.change()` (`web/src/app/features/dashboard/dashboard-layout.ts:151-172`), a reset now stays in `pending` until its save settles, so it shows while the PATCH runs. No production change is uncommitted. `reviews/web-dashboard/review.md` shows as modified in the working tree; this helper did not change it.

Requirements:
- `tasks/web-dashboard.md` acceptance criteria 1 and 2, and the task's recorded assumptions
- `SPEC.md` §8.7 (dashboard widgets), §4.7 (user settings)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`
- `reviews/web-dashboard/review.md` (CR-7) and `reviews/web-dashboard/fixes.md`
- Parent instructions (web only: this helper cannot run Gradle)

## Test files changed

- `web/src/app/features/dashboard/dashboard-layout.spec.ts` (2 tests added in this pass)

## Tests added or updated

Count: 2 added in this pass. No existing test was changed. The dashboard specs went from 85 to 87 tests (the fixer's CR-7 spec, `shows a reset, and a change made after it, while the reset save is unanswered`, was already there).

The fixer's spec covers the main CR-7 path: a reset with a change after it shows while the reset PATCH is unanswered, and the change is saved on the default layout after the reset succeeds. Two paths of the new code were not covered by any spec:

- `drops a refused reset it showed, and saves the change queued behind it on the stored layout`: reset, then hide Today. While the reset PATCH is unanswered, the default layout with Today hidden shows. The reset gets a 400: the reset drops out of `pending`, the stored layout comes back with Today still hidden, and the queued change is saved on the stored layout (exact PATCH body).
- `shows a reset queued behind an unanswered change until the reset itself is saved`: hide Inbox, then reset. The default layout shows while the hide PATCH is unanswered, after it is answered (the hidden Inbox is stored by then, and only the pending reset hides it), and while the reset PATCH is unanswered. The reset PATCH sends `null`.

Both tests would fail on the code before 9c2e59fce: there the reset dropped out of `pending` before its PATCH was sent, so `slots()` showed the old stored layout during the reset save.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Today widget (overdue + due-today tasks, deduplicated; today's events without cancelled ones; habits due/done) | DashboardData > Today (4 tests); DashboardPage: `fills the Today widget with tasks, events and habits`, `says when nothing is due today...` | `cd web && npx ng test --watch=false --include 'src/app/features/dashboard/**/*.spec.ts'` | Pass |
| AC1 Inbox count (tasks + unread mail; "N+" only when a cut page has unread mail) | DashboardData: `counts the Inbox tasks and the unread mail, flagging cut pages`, `counts no unread mail when the mail page has no items`, `flags the unread mail count as cut only when the cut page has unread mail`; DashboardPage: `fills the widgets with counts and lists` | same | Pass |
| AC1 Stalled projects, Waiting For overdue, Upcoming events, Read-later, Pinned notes, Last weekly review | DashboardData loading tests; DashboardPage list/empty-state tests, weekly review status and 7-day boundary tests | same | Pass |
| AC1 Widgets load on their own, live refresh, quiet reload when a widget is shown again | DashboardData > loading and reloading, show() tests; DashboardPage retry and re-show tests | same | Pass |
| AC1 show/hide and order saved in settings, including quick changes during an unanswered save (CR-1) | DashboardLayout queue tests (hide, order, quick changes, refused change, 412 rebuild, settings load failure, changes before load); DashboardPage customize-panel tests | same | Pass |
| AC1 Reset shown at once and while its save runs (CR-7) | DashboardLayout: `shows a reset, and a change made after it, while the reset save is unanswered` (fixer), **new:** `drops a refused reset it showed, and saves the change queued behind it on the stored layout`, `shows a reset queued behind an unanswered change until the reset itself is saved`; existing `brings the stored layout back when a reset is refused`, `resets the layout to the setting default`, `builds a change made right after a reset on the default layout`; DashboardPage reset test | same | Pass |
| AC2 Vitest component/service tests cover widget configuration persistence | DashboardLayout (service) and DashboardPage (component) persistence tests above | same | Pass |
| AC2 `cd web && npm run check` passes | api:check, lint, e2e:typecheck, test:ci, ng build | `cd web && npm run check` | Pass (exit 0; 164 files / 3353 tests; the build shows the existing initial-bundle budget warning, 676.62 kB of 500 kB, a warning only) |
| Server `dashboardWidgets` setting (`DashboardSettings.kt`) | DashboardSettingsWebTests, SettingsServiceTests | Gradle | Not run in this pass: this helper cannot run Gradle. The server code did not change since the parent ran them (they passed). |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include 'src/app/features/dashboard/**/*.spec.ts'`

Result:

Pass: 3 files, 87 tests. `npx prettier --check src/app/features/dashboard` also passes.

## Full test execution

Command:

`cd web && npm run coverage` (the full Vitest suite with coverage), then `cd web && npm run check`

Result:

Pass: 164 files, 3353 tests, both times. `npm run check` exited 0: "All files pass linting", all tests passed, the build wrote `dist/web`.

## .taskcheck

Result:

Not run. `sh scripts/check.sh` also runs the Gradle server and Android checks, which this helper cannot run. Its web part (`npm run check`) passed, see above. The parent runs `.taskcheck`.

## Bugs found

None. No new or existing test exposed a production defect. The CR-7 fix behaves as described in the review's suggested fix, also on the refused-reset and queued-reset paths.

Observations carried over from the second pass (no requirement violation, no test written): a widget hidden while its first load is still running is not reloaded when shown again, so it can miss a change event in that narrow window; and a layout change handed to `SettingsService` behind an unanswered save of another setting would be hidden until that save answers (nothing in the app does this today).

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured as a separate metric. Every web production file in scope is new in this task, so for these files whole-file coverage equals changed-line coverage.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-dashboard`; base d54b8b3c0186aa51a8db33aa61e2f7ef7fad5c3a; measured HEAD 9c2e59fce1eb6b70fd38c800ece61a9d6e2c0bbc; task commits as given by the parent, checked against `git diff --stat` base..HEAD and the task's `touches` (`web/src/app/features/dashboard`, plus the recorded assumption for the server setting).
- Source/test snapshot identifier (including uncommitted inputs): HEAD 9c2e59fce plus the uncommitted `dashboard-layout.spec.ts`. sha256 (first 16 hex digits): dashboard-data.ts 4937f5c549b19cd1, dashboard-layout.ts cc06235c32d0fdfa, dashboard-page.ts 0f0985f34e1899a6, dashboard-page.html 876063361c319f61, dashboard.routes.ts d3eb978edd027d6c, dashboard-data.spec.ts 7b6ee93228221f96, dashboard-layout.spec.ts b4ae18d96e240747, dashboard-page.spec.ts 2066d95741568021. Hashes were taken before the coverage run and checked again after it: unchanged.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/dashboard/dashboard-data.ts`, `dashboard-layout.ts`, `dashboard-page.ts`, `dashboard-page.html` (v8 instruments the template), `dashboard.routes.ts`. Excluded: `dashboard-page.scss` (styles, no behavior); `server/src/main/kotlin/ch/sonensei/cockpit/core/settings/infra/DashboardSettings.kt` (server code, JaCoCo needs Gradle, which this helper cannot run: unmeasured, not covered); spec files; `web/src/app/app.spec.ts` and `app.i18n.spec.ts` (tests). No file was renamed or deleted.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with @vitest/coverage-v8; `npm run coverage` in `web/`; the full web suite (164 spec files). Per-file counters read from the provider's `coverage-summary.json` / `coverage-final.json` with `.tmp/dash-cov.mjs`.
- Run start/completion time and exit status: started 2026-10-22T09:18:21Z (local 11:18:21), report written 09:19:03Z, test duration 41.98 s; exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 0d29a3a5b95ad5885318dff8f11983fc7a32c7c13bccaea5f9741d62f08d278e; `web/coverage/web/coverage-summary.json` sha256 f5ef2221cfbd04d6d42cad76e6e37525445489fa1c5be54ef6aa93df296dde1f.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report files carry this run's time (11:19:03 local), their hashes differ from the second pass (20f30c45..., c428de9f...), the test count went from 3350 to 3353, and `dashboard-layout.ts` now has 60 lines instead of 58 (the CR-7 code). The input hashes did not change during the run.
- Final run after latest test additions and production fixes: yes. The run came after the last test edit, on the code with the CR-7 fix (9c2e59fce).

Values below use covered/total (percentage); zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/dashboard/dashboard-data.ts` | 82/82 (100%) | 42/42 (100%) | 26/26 (100%) | 68/68 (100%) |
| `web/src/app/features/dashboard/dashboard-layout.ts` | 82/82 (100%) | 32/32 (100%) | 33/33 (100%) | 60/60 (100%) |
| `web/src/app/features/dashboard/dashboard-page.ts` | 37/37 (100%) | 16/17 (94.11%) | 17/17 (100%) | 29/29 (100%) |
| `web/src/app/features/dashboard/dashboard-page.html` | 330/330 (100%) | 75/87 (86.2%) | 7/7 (100%) | 202/202 (100%) |
| `web/src/app/features/dashboard/dashboard.routes.ts` | 1/1 (100%) | 0/0 (N/A) | 0/0 (N/A) | 1/1 (100%) |
| **Aggregate (summed counters, these 5 web files)** | 532/532 (100%) | 165/178 (92.70%) | 83/83 (100%) | 360/360 (100%) |

The aggregate excludes the server file `DashboardSettings.kt` (unmeasured: it needs Gradle/JaCoCo) and `dashboard-page.scss` (no behavior).

The CR-7 change (`dashboard-layout.ts:159-167`, both sides of `if (reset)`) was already at 100% statements and branches with the fixer's spec. The two new tests check behavior the counters do not show: what `slots()` displays while a refused reset runs and after it, and a reset queued behind another save.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `dashboard-page.ts:50` (one branch) | Branch from compiler-generated component metadata (`providers: [DashboardData]`) | Instrumentation artifact, no behavior |
| `dashboard-page.html:114,174,200,230,257,266,280,314,338` (else branches of the inner `@if (x.status === 'ready')`) | Unreachable: the outer `@else` already guarantees `status === 'ready'`; the inner check only narrows the type | Not reachable, no test |
| `dashboard-page.html:111` (the `@switch` default) | Unreachable: every WidgetId has a case | Not reachable |
| `dashboard-page.html:328` (Read-later "N+ more" when the page is cut) | Same template logic as Pinned notes ("2+ more", tested) | Low risk, left out |
| `dashboard-page.html:350` (Pinned notes "more" without "+") | The opposite case is tested for Read-later ("1 more") | Low risk, left out |

## Unable to test

- The server `dashboardWidgets` setting (`DashboardSettings.kt`) and its JaCoCo coverage: this helper cannot run Gradle. The parent ran DashboardSettingsWebTests / SettingsServiceTests earlier (passed); the server code is unchanged since. Follow-up: parent, only if it wants server coverage numbers.
- `.taskcheck` as a whole: it includes Gradle. Its web part passed.
- Browser/E2E: neither criterion names one, so none was run.

## Summary

Tests added and all validation passed. The CR-7 fix is covered, including the refused-reset and queued-reset paths. Task-scoped web coverage is sufficient, and no production defects were found. Server coverage and `.taskcheck` remain for the parent, because they need Gradle.
