# Test Report

Date: 2026-10-20

Task: web-next-today - Next Actions and Today views

Scope: the task commits ("web-next-today: ..."), first measured as `git diff 9aade7a7..75bd7412`. During this run the parent rebased onto newer main: the new base is `17a37709` and HEAD is `59d07ef2073e64adcb55f834b29ee48a4bc5d47b`. The task's code change is identical after the rebase: `git patch-id --stable` of the `web/` and `server/` diff is `903640bd...` for both `9aade7a7..75bd7412` and `17a37709..59d07ef2`. The commits that came with the rebase touch only `tasks.py`, `agents.py`, `tests/`, other tasks' `reviews/` and `tasks/`. There were no uncommitted changes when I started. The only uncommitted change now is the test file this report adds to (below).

Requirements:
- `tasks/web-next-today.md` acceptance criteria and recorded assumptions (area filter on the client, max estimate excludes tasks without an estimate, presets in setting `nextActionPresets`, Today sections and extension slots)
- SPEC.md §5.3 (Next Actions view filters and presets, Today view)
- docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md
- `reviews/web-next-today/review.md` and `fixes.md` (CR-1 and CR-3 regression tests are already there)

## Test files changed

- `web/src/app/features/gtd/next/next-view.spec.ts`

## Tests added or updated

Count: 2 (added)

The task already had 135 tests across `next-filter.spec.ts`, `filter-presets.spec.ts`, `next-view.spec.ts` and `today-view.spec.ts`. I checked them against each criterion and against the coverage report, then added tests only for the two gaps I found:

- `NextView > filters > removes the project with "Any project", keeping the area`: choosing "Any project" removes `project` from the URL and `projectId` from the server query, keeps the area, and narrows the list to the area. This was the only template branch with no hits (`next-view.html:137`, `$event.value || undefined` for the project select).
- `NextView > filters > follows the URL when it changes while the view is open (a link, back and forward)`: the criterion says "filters in the URL". This test checks that the open view follows a URL change that it did not make itself (in-app link, browser back, browser forward). On each change it must reload the list with the new query and update which options show as chosen. The test uses `provideLocationMocks()` and `router.initialNavigation()` so popstate is simulated with no real delays.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Next filters: context, energy, creativity, max estimate, area/project, due | next-filter.spec (`reads every filter from the URL query`, `asks the server for NEXT tasks with every filter but the area`, `keeps the tasks of the area's projects`, ...); next-view.spec `filters` block (each filter sent to the server, area narrowed on the client, `combines all seven filters...`, project/area selects incl. new `removes the project with "Any project", keeping the area`) | `npm --prefix web run check` | Pass |
| Filters in the URL | next-filter.spec round-trip tests, invalid/unknown values ignored; next-view.spec `writes a chosen filter into the URL...`, `removes a filter from the URL...`, `clears every filter`, `ignores invalid filter values of an old or edited link`, out-of-order responses; new `follows the URL when it changes while the view is open (a link, back and forward)` | `npm --prefix web run check` | Pass |
| Saved presets stored in settings | filter-presets.spec (read/skip invalid, save/replace/remove, CR-1 load-before-save, failure passed on); next-view.spec `presets` block (apply/toggle, save, name required, max 50, Escape/cancel, save and delete failures (CR-3), settings unloaded or failing, presets from another device) | `npm --prefix web run check` | Pass |
| Server setting `nextActionPresets` (GtdSettings.kt) | `GtdSettingsWebTests`, `SettingsServiceTests` (existing, Testcontainers) | none | Not run: needs Docker (Testcontainers Postgres) and the parent asked me to avoid Docker |
| Today: overdue, due today, scheduled today | today-view.spec (loads the three queries, sections in order with counts, hides empty sections, a task in two sections shows once, scheduled dated today or earlier only, nothing-today state, errors and retry, complete/rollback, open) | `npm --prefix web run check` | Pass |
| Today extension slots | today-view.spec `extension slots` block (ordered by `order`, shown while loading, on an empty day and on failure, `provideTodaySection` environment providers, none registered) | `npm --prefix web run check` | Pass |
| Vitest tests cover filter combinations and presets; `npm run check` passes | all of the above | `npm --prefix web run check` | Pass (49 files, 846 tests; lint clean; build OK) |
| Playwright `e2e/gtd-next/next.spec.ts` | not run by me | none | Not run: the parent runs the e2e suite against the Docker e2e stack itself |

## Focused test execution

Command:

`npm --prefix web run test:ci -- --include src/app/features/gtd/next/next-view.spec.ts`

Result:

Pass: 44 of 44. The first two attempts of the new history test failed because of how the test was set up, not because of a product bug. The router follows popstate only after `initialNavigation()`, which bootstrap normally calls. I fixed the test by calling it.

## Full test execution

Command:

`npm --prefix web run check` (api:check, eslint `--max-warnings=0`, e2e typecheck, Vitest, `ng build`)

Result:

Pass: 49 test files, 846 tests, exit 0. The build warns that the initial bundle is over budget (609 kB against 500 kB). That warning comes from code outside this task.

## .taskcheck

Result:

Not run. `scripts/check.sh` runs `./gradlew check`, which starts Testcontainers (Docker), and the parent asked me to avoid Docker. The parent runs `.taskcheck` before `done`.

## Bugs found

None. No test exposed a production defect.

## Coverage

Status: verified for the web files in scope. Not measured for `server/src/main/kotlin/ch/sonensei/cockpit/gtd/infra/GtdSettings.kt`: the server tests that load it extend `PostgresTestBase` (Testcontainers), and Docker was ruled out for this run.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. Nearly all scoped files are new in this task, so whole-file coverage is close to changed-line coverage, but I did not intersect the two.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-next-today. Base given by the parent: `9aade7a7356c7a4ba18f312bac8667de29f555af`, code at `75bd741253d4b07e86875f5347ffbdbc736b51c4`. The parent rebased during the run, giving base `17a37709ff9dd7ad02b94e107688845e8969f812` and HEAD `59d07ef2073e64adcb55f834b29ee48a4bc5d47b`, with the same task patch-id `903640bde7b1885fc0a3590f20a2bda6d82109da`. Task commits are those with the "web-next-today:" prefix. The file scope comes from `git diff --name-only 17a37709..HEAD -- server/src/main web/src` (same list as `9aade7a7..75bd7412`), excluding `*.spec.ts`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `59d07ef2` (task code identical to `75bd7412`) plus uncommitted `web/src/app/features/gtd/next/next-view.spec.ts` with sha256 `5d869f2704dd4feb480c12b12f3f39ea7ed9061abfffb3d0e8ba421a388ffcdf`. I hashed all task-changed `web/src` and `server/src` files before and after the final run; the hashes matched.
- Exact included files: `web/src/app/features/gtd/gtd.routes.ts`, `next/filter-presets.ts`, `next/next-filter.ts`, `next/next-view.ts`, `next/next-view.html`, `next/next.routes.ts`, `today/today-sections.ts`, `today/today-view.ts`, `today/today-view.html`, `today/today.routes.ts`, `web/src/app/shell/nav-sections.ts`. The provider maps the templates to their `.html` sources, so I report them as separate files. Excluded: `next-view.scss` and `today-view.scss` (styles, no executable behavior). Unmeasured: `server/.../gtd/infra/GtdSettings.kt` (Docker, see above). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8` 5.0.5. Command `npm --prefix web run coverage` (that is, `ng test --watch=false --coverage ...`), run from the repository root. It ran the full web suite (49 files, 846 tests).
- Run start/completion time and exit status: 2026-10-20T23:57:51+02:00 to 23:58:13+02:00. All tests passed and the summary was printed. The same suite passed in `npm --prefix web run check`, which exited 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 `e68c32f2a65c3b119f7f1226baf0f41e0c57799cdeff7e644f07666462df136a`) and `web/coverage/web/coverage-final.json` (sha256 `0137fdd25162b841cb82ec05067ca9dafda2cd3b4a40fe4787075b9b2af1684a`). Both files are dated 23:58:13 and were rewritten by this run.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report files changed against the earlier run at 23:53, and the input hashes were the same before and after the run.
- Final run after latest test additions and production fixes: yes. The run came after both new tests and after the CR-1/CR-3 fixes (`75bd7412`, rebased as `59d07ef2`).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/gtd.routes.ts` | 8/8 (100%) | N/A (0/0) | 7/7 (100%) | 8/8 (100%) |
| `web/src/app/features/gtd/next/filter-presets.ts` | 37/37 (100%) | 21/21 (100%) | 10/10 (100%) | 28/28 (100%) |
| `web/src/app/features/gtd/next/next-filter.ts` | 27/27 (100%) | 16/16 (100%) | 16/16 (100%) | 22/22 (100%) |
| `web/src/app/features/gtd/next/next-view.ts` | 89/89 (100%) | 35/38 (92.1%) | 32/32 (100%) | 77/77 (100%) |
| `web/src/app/features/gtd/next/next-view.html` | 218/218 (100%) | 28/28 (100%) | 19/19 (100%) | 113/113 (100%) |
| `web/src/app/features/gtd/next/next.routes.ts` | 2/2 (100%) | N/A (0/0) | 1/1 (100%) | 2/2 (100%) |
| `web/src/app/features/gtd/today/today-sections.ts` | 3/3 (100%) | N/A (0/0) | 2/2 (100%) | 3/3 (100%) |
| `web/src/app/features/gtd/today/today-view.ts` | 45/45 (100%) | 21/23 (91.3%) | 17/17 (100%) | 36/36 (100%) |
| `web/src/app/features/gtd/today/today-view.html` | 56/56 (100%) | 4/4 (100%) | 3/3 (100%) | 31/31 (100%) |
| `web/src/app/features/gtd/today/today.routes.ts` | 2/2 (100%) | N/A (0/0) | 1/1 (100%) | 2/2 (100%) |
| `web/src/app/shell/nav-sections.ts` | 3/3 (100%) | N/A (0/0) | 1/1 (100%) | 2/2 (100%) |
| **Aggregate (summed counters, 11 measured web files)** | 490/490 (100%) | 125/130 (96.2%) | 109/109 (100%) | 324/324 (100%) |
| `server/.../gtd/infra/GtdSettings.kt` | not measured | not measured | not measured | not measured |

The aggregate leaves out `GtdSettings.kt` (unmeasured, Docker) and the two `.scss` files (excluded, styles only). The earlier run, before my tests, had the same numbers except branches: 124/130, with `next-view.html:137` not hit.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `next-view.html:137` (project select `$event.value \|\| undefined`, "Any project") | Area/project filter: removing the project filter | Added `removes the project with "Any project", keeping the area`. Now covered. |
| `next-view.ts:187` (`this.presetName() ?? ''`, null side) | None: `savePreset` can only be triggered from the form, which is shown only while `presetName()` is not null | Left as is. Defensive code that can't be reached. |
| `next-view.ts:222`, `today-view.ts:114` (`task.completedAt ? uncomplete : complete`, uncomplete side) | Reopening a done task from these lists | Left as is. `TaskStore` removes a completed task from an open-task query immediately (`completed` is false), so a done row never shows in these views and can't be toggled back. Completing and rolling back are tested. |
| `next-view.ts:64`, `today-view.ts:41` (`if` branch the provider reports at the `@Component` decorator / `providers`) | None: compiler-generated code | Left as is. Not product code. |
| `server/.../gtd/infra/GtdSettings.kt` (whole file) | Setting `nextActionPresets` registered (TextList, max 50 entries of 2000 characters, default empty) | Not measured in this run (Testcontainers needs Docker). Existing tests `GtdSettingsWebTests` and `SettingsServiceTests` cover it. The parent's `.taskcheck` (`./gradlew check`) runs them. Follow-up for the parent: confirm they pass in `.taskcheck`, or run `cd server && ./gradlew test jacocoTestReport` where Docker is available. |

## Unable to test

- Server setting `GtdSettings.kt`: tests exist but I did not run them, because they need Docker (Testcontainers) and the parent ruled Docker out. Follow-up: the parent's `.taskcheck` run.
- Playwright `e2e/gtd-next/next.spec.ts` (criterion 4): not run, as the parent instructed. The parent runs it against the e2e stack.
- Known limitation, not a test gap: "Scheduled for today" is usually empty because the server moves scheduled tasks to Next when they are read (CR-2, follow-up task `gtd-scheduled-marker`). The client behavior (scheduled date today or earlier, not later, not undated) is tested.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. I added two Next view tests ("Any project" and following the URL through link, back and forward). `npm --prefix web run check` passes with 846 tests. Web coverage of the task files is 100% of statements, functions and lines and 125/130 branches. The 5 uncovered branches are defensive or compiler-generated code. `GtdSettings.kt` coverage, `.taskcheck` and the e2e spec were not run here (Docker), and the parent is responsible for running them.
