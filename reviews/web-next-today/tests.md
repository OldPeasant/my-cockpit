# Test Report

Date: 2026-10-21

Task: web-next-today - Next Actions and Today views

Scope: the task commits ("web-next-today: ...") on base `bdbabd7cbc0620f6772e352e0dcba394e3043ef7`, measured at HEAD `724ac0da8aa3c1c996436d438b2b38d2ffb77b86` (`git diff bdbabd7c..724ac0da`).

While I was writing this report, the parent rebased again, to base `e035eaf772ac5e844d8a1ff78df444e59bc6d6ed` and HEAD `30eddb7b50024a66aa485f47af5bd4503c51837a`. `git diff --stat 724ac0da 30eddb7b` lists only `tasks/*.md`. There are no changes under `web/`, `server/` or `reviews/`, so the measurements below apply unchanged to `30eddb7b`.

How the scope got here:
- The tests were first written against `9aade7a7..75bd7412`. The parent then rebased several times.
- One rebase hit a conflict in `web/src/app/app.spec.ts` (BUILT list). The parent kept both sides: this task's `'/today'` and `'/next'`, and other tasks' `'/waiting'`, `'/someday'`, `'/scheduled'`, `'/logbook'`.
- Production code was not changed by the rebases. `git diff -U0 9aade7a7 75bd7412 -- web/src/app/features/gtd web/src/app/shell server/src/main ':!*.spec.ts' | git patch-id --stable` and the same command for `bdbabd7c 724ac0da` both give `f8852259581418d6f6e5f23eab784f5e34c4f370`.
- The two tests added by this test-writer are committed (in `724ac0da`, which became `30eddb7b`).

Everything in this report was re-run on `724ac0da`. At measurement time the only uncommitted change in the working tree was `reviews/web-next-today/review.md`. This test-writer did not make that change, and it is not a test or code input.

Requirements:
- `tasks/web-next-today.md` acceptance criteria and recorded assumptions (area filter on the client, max estimate excludes tasks without an estimate, presets in setting `nextActionPresets`, Today sections and extension slots)
- SPEC.md §5.3 (Next Actions view filters and presets, Today view)
- docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md
- `reviews/web-next-today/review.md` and `fixes.md` (the regression tests for CR-1 and CR-3 were already there)

## Test files changed

- `web/src/app/features/gtd/next/next-view.spec.ts`

## Tests added or updated

Count: 2 (added)

The task already had 135 tests in `next-filter.spec.ts`, `filter-presets.spec.ts`, `next-view.spec.ts` and `today-view.spec.ts`. I checked them against each criterion and the coverage report, and added tests only for the two gaps I found:

- `NextView > filters > removes the project with "Any project", keeping the area`: choosing "Any project" removes `project` from the URL and `projectId` from the server query. It keeps the area, and the list is narrowed to the area. This covers the only template branch that no test reached (`next-view.html:137`, `$event.value || undefined` for the project select).
- `NextView > filters > follows the URL when it changes while the view is open (a link, back and forward)`: covers the criterion "filters in the URL" for URL changes the view did not make itself (an in-app link, browser back, browser forward). After each change the view must reload the list with the new query and update which filter options show as chosen. The test simulates browser history with `provideLocationMocks()` and `router.initialNavigation()`, without real delays.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Next filters: context, energy, creativity, max estimate, area/project, due | next-filter.spec (`reads every filter from the URL query`, `asks the server for NEXT tasks with every filter but the area`, `keeps the tasks of the area's projects`, ...); next-view.spec `filters` block (each filter sent to the server, area applied on the client, `combines all seven filters...`, area and project selects incl. `removes the project with "Any project", keeping the area`) | `npm --prefix web run check` | Pass |
| Filters in the URL | next-filter.spec round-trip tests and ignoring unknown or invalid values; next-view.spec `writes a chosen filter into the URL...`, `removes a filter from the URL...`, `clears every filter`, `ignores invalid filter values of an old or edited link`, responses arriving out of order; `follows the URL when it changes while the view is open (a link, back and forward)` | `npm --prefix web run check` | Pass |
| Saved presets stored in settings (client) | filter-presets.spec (reading, skipping invalid entries, save/replace/remove, loading settings before save (CR-1), failures passed on); next-view.spec `presets` block (apply and toggle, save, name required, limit of 50, Escape and cancel, failed save and delete (CR-3), settings not loaded or failing, presets saved on another device) | `npm --prefix web run check` | Pass |
| Saved presets stored in settings (server setting `nextActionPresets`, GtdSettings.kt) | `GtdSettingsWebTests` (4 tests), `SettingsServiceTests` (6 tests) | `cd server && ./gradlew test --tests '*GtdSettings*' --tests '*SettingsService*' jacocoTestReport` | Pass (10 of 10, Testcontainers Postgres) |
| Today: overdue, due today, scheduled today | today-view.spec (loads the three queries, sections in order with counts, hides empty sections, a task in two sections shows only once, scheduled shows only dates up to today, the empty-day message, errors and retry, complete and rollback, open) | `npm --prefix web run check` | Pass |
| Today extension slots | today-view.spec `extension slots` block (sorted by `order`, shown while loading, on an empty day and on failure, `provideTodaySection` environment providers, nothing shown when none is registered) | `npm --prefix web run check` | Pass |
| Shell lists the built routes `/today` and `/next` (merged BUILT list) | `app.spec.ts` | `npm --prefix web run check` | Pass |
| Vitest tests cover filter combinations and presets; `npm run check` passes | all of the above | `npm --prefix web run check` | Pass (51 files, 901 tests; lint clean; build OK) |
| Playwright `e2e/gtd-next/next.spec.ts` | not run by me | none | Not run: the parent runs the e2e suite itself against the Docker e2e stack |

## Focused test execution

Commands:

`npm --prefix web run test:ci -- --include src/app/features/gtd/next/next-view.spec.ts` (run while writing the tests, on `75bd7412` plus the new tests)

`cd server && ./gradlew test --tests '*GtdSettings*' --tests '*SettingsService*' jacocoTestReport` (on `724ac0da`)

Result:

Pass.
- Web: 44 of 44 tests passed. The new history test failed twice while I wrote it. The cause was the test setup, not the product: the router only reacts to popstate after `initialNavigation()`, which normally runs at bootstrap. The test now calls it.
- Server: `GtdSettingsWebTests` 4 tests and `SettingsServiceTests` 6 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESSFUL.

## Full test execution

Command:

`npm --prefix web run check` on `724ac0da` (api:check, eslint `--max-warnings=0`, e2e typecheck, Vitest, `ng build`)

Result:

Pass: 51 test files, 901 tests, exit code 0. The build warns that the initial bundle is over its budget (609 kB vs 500 kB). That bundle is not code from this task. I did not run the full server suite, only the settings tests above.

## .taskcheck

Result:

Not run by this test-writer. The parent runs `.taskcheck` (`sh scripts/check.sh`) before `done`.

## Bugs found

None. No test exposed a production defect.

## Coverage

Status: verified for every production file in scope (11 web files and `GtdSettings.kt`).

Metric: whole-file coverage of the production files the task changed.

Changed-line coverage: not measured. Almost every file in scope was created by this task, so whole-file coverage is close to changed-line coverage, but I did not compute the overlap.

Evidence:
- Task, base, measured HEAD and file scope:
  - Task web-next-today, base `bdbabd7cbc0620f6772e352e0dcba394e3043ef7` (as given by the parent; equal to `git merge-base HEAD origin/main` at the time). HEAD `724ac0da8aa3c1c996436d438b2b38d2ffb77b86`, which is code-identical to the later `30eddb7b50024a66aa485f47af5bd4503c51837a` (only `tasks/` differs).
  - The task's commits are the 9 commits in `bdbabd7c..724ac0da`: 8 with the "web-next-today:" prefix plus `8cebcf86`, which adds the follow-up task.
  - File scope: `git diff --name-only bdbabd7c..HEAD -- server/src/main web/src`, excluding `*.spec.ts`.
- Snapshot of sources and tests: HEAD `724ac0da`, with no uncommitted source or test changes. I took sha256 hashes of every task-changed file under `web/src` and `server/src` before and after the web coverage run; they were identical.
- Files included:
  - `web/src/app/features/gtd/`: `gtd.routes.ts`, `next/filter-presets.ts`, `next/next-filter.ts`, `next/next-view.ts`, `next/next-view.html`, `next/next.routes.ts`, `today/today-sections.ts`, `today/today-view.ts`, `today/today-view.html`, `today/today.routes.ts`
  - `web/src/app/shell/nav-sections.ts`
  - `server/src/main/kotlin/ch/sonensei/cockpit/gtd/infra/GtdSettings.kt`

  The coverage provider maps the templates back to their `.html` files, so they appear as files of their own.
- Excluded: `next-view.scss` and `today-view.scss` (styles, no executable behavior). No files were renamed or deleted.
- Providers, commands, working directory and test selection:
  - Web: Vitest 5.0.5 with `@vitest/coverage-v8` 5.0.5. `npm --prefix web run coverage` from the repository root, full web suite (51 files, 901 tests).
  - Server: JaCoCo via the Gradle `jacoco` plugin (project default version). `cd server && ./gradlew test --tests '*GtdSettings*' --tests '*SettingsService*' jacocoTestReport`, running `GtdSettingsWebTests` and `SettingsServiceTests` (10 tests).
- Run times and exit status:
  - Web: 2026-10-21T00:02:56+02:00 to 00:03:19+02:00. All tests passed and the coverage summary was printed. The same suite passed in `npm --prefix web run check` (exit 0).
  - Server: test results timestamped 2026-10-21T00:04:08+02:00 to 00:04:47+02:00, report written 00:04:49+02:00. BUILD SUCCESSFUL, and the `test` task actually ran (it was not UP-TO-DATE).
- Generated reports and sha256 hashes:
  - `web/coverage/web/coverage-summary.json`: `82bfa61f15a55be870ac09e0edaaa0b8c4d819d4f97a3989832796bf26988024`
  - `web/coverage/web/coverage-final.json`: `72d129b7541dd1537d25d147866411a461e64aa68fc098b45afbe6fe9283f658`
  - `server/build/reports/jacoco/test/jacocoTestReport.xml`: `1105cf3cb343627e74ab86822db1e16f9931bb0d916e85b1ac38f037e3e94b19`
- The reports are fresh and the inputs did not change: yes. The web report is timestamped 00:03:19 and its hashes differ from the earlier run's. The JaCoCo XML is timestamped 00:04:49, after this run's tests. The input hashes were the same before and after.
- The final run includes the latest tests and production fixes: yes. It ran on `724ac0da`, which has the CR-1 and CR-3 fixes, both new tests and the merged `app.spec.ts`. `30eddb7b` has the same code.

Values below use covered/total (percentage); N/A means a zero denominator. Web values are v8's statements, branches, functions and lines. For `GtdSettings.kt`, the JaCoCo counts are instructions in the Statements column and methods in the Functions column.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/gtd.routes.ts` | 9/9 (100%) | N/A (0/0) | 8/8 (100%) | 9/9 (100%) |
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
| **Web total (v8 counters summed over 11 files)** | 491/491 (100%) | 125/130 (96.2%) | 110/110 (100%) | 325/325 (100%) |
| `server/.../gtd/infra/GtdSettings.kt` (JaCoCo) | instructions 21/21 (100%) | N/A (0/0) | methods 4/4 (100%) | 8/8 (100%) |

The web total leaves out the two `.scss` files (excluded, styles only). The JaCoCo numbers are kept separate rather than added to the v8 counters, because the two tools count different units. `gtd.routes.ts` grew from 8 to 9 statements in the rebase because another task added a route; this task's route lines are covered.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `next-view.html:137` (project select `$event.value \|\| undefined`, "Any project") | Area/project filter: removing the project filter | Added `removes the project with "Any project", keeping the area`; now covered. |
| `next-view.ts:187` (`this.presetName() ?? ''`, null side) | None: `savePreset` can only be triggered from the save form, which only exists while `presetName()` is not null | Left uncovered: defensive code that cannot be reached. |
| `next-view.ts:222`, `today-view.ts:114` (`task.completedAt ? uncomplete : complete`, uncomplete side) | Reopening a done task from these lists | Left uncovered: the query only asks for open tasks, so `TaskStore` drops a task from these lists as soon as it is completed. A done task never shows here, so it can't be reopened from these views. Completing and the rollback are tested. |
| `next-view.ts:64`, `today-view.ts:41` (`if` branch reported at the `@Component` decorator / `providers`) | None: code generated by the compiler | Left uncovered: not product code. |

## Unable to test

- Playwright `e2e/gtd-next/next.spec.ts` (criterion 4): not run, as the parent instructed. The parent runs it against the e2e stack.
- Known limitation, not a test gap: "Scheduled for today" is usually empty, because the server moves scheduled tasks to Next when they are read (CR-2, follow-up task `gtd-scheduled-marker`). The client side is tested: only tasks with a scheduled date up to today appear, not later or undated ones.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
- `npm --prefix web run check` passes on `724ac0da` (code-identical to `30eddb7b`): 51 files, 901 tests.
- The server settings tests pass, 10 of 10.
- Web coverage of the task's files: 100% of statements, functions and lines, and 125 of 130 branches. The 5 uncovered branches are defensive or compiler-generated code.
- `GtdSettings.kt` is fully covered: 21/21 instructions, 8/8 lines.

The parent still has to run `.taskcheck` and the e2e spec.
