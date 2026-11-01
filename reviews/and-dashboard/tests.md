# Test Report

Date: 2026-11-01

Task: and-dashboard - Android: Dashboard as start screen

Scope: task commits c7a70f0d..d70cf47b on `main` (base `bcad1b6186ab29ee2385f6f10c880b2d8b04ca28`, HEAD
`d70cf47bf019099d74f2f6426eee33c1ca3cb2b3`), plus one uncommitted test change from this run
(`DashboardScreenTest.kt`). Android only; server and web unchanged by the task. The focus of this run were the
latest production changes (90ef1455..d70cf47b): scroll-to-top on a new widget order, locale-aware day
format of upcoming events, event rows keyed by `DashboardEvent.key`.

Requirements:
- `tasks/and-dashboard.md` acceptance criteria #1-#6 and its ASSUMPTION notes
- `SPEC.md` §8.7 (dashboard widgets), `docs/ARCHITECTURE.md` "Coverage"
- `AGENTS.md` (Android check command, conventions)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/dashboard/DashboardScreenTest.kt`

## Tests added or updated

Count: 3 added (none of the existing 55 dashboard tests changed). The dashboard package now has 58 tests.

- `an upcoming all-day event reads its day in English order`: asserts the literal text `Fri, Oct 23 · All day`.
  The existing upcoming test computes its expected day with the same `getBestDateTimePattern` call as the
  production code, so it cannot catch a wrong skeleton or locale; this test pins the observable result.
- `in German an upcoming day reads in German order with German names` (`@Config(qualifiers = "de")`): asserts
  `Fr., 23. Okt. · Ganztägig`, i.e. the German order, German abbreviations and German all-day label. The
  previous fixed pattern `EEE d MMM` would not produce this.
- `a reload with the same order keeps the scroll position`: the new scroll-to-top must only fire when the
  widget order changes; a refresh with the same layout (loading, then answered) must not jump the list back
  to the top while the user is scrolled down.

## Requirement coverage

All rows were run by the full command `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`
(2026-11-01 21:57-22:01, BUILD SUCCESSFUL).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: widgets of §8.7 shown, each card opens its screen | `DashboardScreenTest`: `every widget shows its data`, `each card opens the matching screen`, upcoming/overdue/long-list tests, the 2 new day-format tests; `DashboardRepositoryTest` (endpoints and mapping of each widget); `DashboardInAppTest`: Inbox card / Today card navigation | full Android command above | Pass |
| AC2: web layout decides widgets and order, else default order | `DashboardLayoutTest` (4), `DashboardViewModelTest` layout tests, `DashboardScreenTest`: `only the configured widgets show, in their order`, `when the stored layout arrives its first card is at the top`, new `a reload with the same order keeps the scroll position`; `DashboardInAppTest`: `the first configured card is at the top when the app opens`, `only the configured widgets show...` | full Android command above | Pass |
| AC3: app opens on Dashboard after login and on launch; bottom bar includes it | `DashboardInAppTest`: `with stored tokens the app opens on the real dashboard, selected in the bottom bar`; shell tests updated by the task (`AppStartRoutingTest`, `InAppLoginTest`, `CockpitShellNavigationTest`, `CockpitShellTest`, `CockpitDestinationTest`, `ReminderUiTest`) | full Android command above | Pass |
| AC4: one failing widget shows its error; pull to refresh reloads all | `DashboardViewModelTest`: failing widget, pull to refresh, slow widget, newer refresh drops older; `DashboardRepositoryTest`: failing call per widget, offline vs failed; `DashboardScreenTest`: error/offline/failed cards, `pulling down refreshes`; `DashboardInAppTest`: `pulling down reloads the layout and every shown widget` | full Android command above | Pass |
| AC5: all new strings in English and German | `DashboardStringsTest` (2), `DashboardScreenTest`: `in German the cards and messages are German`, new German day-format test; lint `MissingTranslation` in `lintDebug` | full Android command above | Pass |
| AC6: unit + Compose UI tests; `ktlintCheck testDebugUnitTest lintDebug` passes | whole suite: 257 suites, 2725 tests, 0 failures, 0 errors, 0 skipped | full Android command above | Pass |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.DashboardScreenTest'`

Result:

Pass (19 tests, 0 failures; 16 before this run).

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Pass: BUILD SUCCESSFUL in 4m 17s; JUnit XML totals 257 suites, 2725 tests, 0 failures, 0 errors, 0 skipped;
dashboard package 58 tests. ktlint and lint passed.

## .taskcheck

Result:

TASKCHECK_RESULT

## Bugs found

None. No production defect was found in the reviewed scope.

## Coverage

Status: verified (fresh run on the snapshot below).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by intersecting the task diff (`git diff -U0 bcad1b61..HEAD`) with JaCoCo
`<line>` entries. The three dashboard files are new in this task, so their changed-line coverage equals their
whole-file line coverage (372/375). The executable changed lines in the shell files (CockpitShell.kt 151, 235;
CockpitDestination.kt 39, 46, 65) are all covered (5/5). Total changed lines: 377/380 (99.2 %). Changed-branch
coverage was not measured separately.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-dashboard`; base
  `bcad1b6186ab29ee2385f6f10c880b2d8b04ca28` (given by the parent); HEAD `d70cf47bf019099d74f2f6426eee33c1ca3cb2b3`;
  task commits c7a70f0d, a12b694a, eefdb4d9, 90ef1455, 65ec59c8, d70cf47b (all prefixed `and-dashboard:`); the
  production paths match the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD d70cf47b plus uncommitted
  `DashboardScreenTest.kt` sha256 `b9b195eff283f7035aa08d1d9ba197ad6422c2b4bd9786eaae88a23c1d918168`;
  production sha256: `DashboardScreen.kt` `dd662bf0dc0ffd577ef67cc2cb08215a208b181a6516c9c0e06fe2512103c963`,
  `DashboardRepository.kt` `bac095567140783faf8e4587e594dd2f0ffa002c989aabdd9c5d0171aba212ae`,
  `DashboardViewModel.kt` `503b0651ac480031c0bb5d25968d42b5f96b013e34ff63b2abae03d007c99eb0`.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/dashboard/{DashboardRepository,DashboardScreen,DashboardViewModel}.kt`,
  `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt`,
  `android/app/src/main/java/ch/sonensei/cockpit/ui/navigation/CockpitDestination.kt`. Excluded:
  `res/values/strings_dashboard.xml`, `res/values-de/strings_dashboard.xml` (resources, not instrumentable; covered by
  `DashboardStringsTest` and lint), test files and `reviews/`. No renamed or deleted production files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`;
  `./gradlew ktlintCheck testDebugUnitTest lintDebug` (writes `testDebugUnitTest.exec`) followed by
  `./gradlew createDebugUnitTestCoverageReport`, working directory `android/`, full unit suite (no filter).
- Run start/completion time and exit status: test run 2026-11-01T21:57:10+01:00 to 22:01:28 (exec file written
  22:01:27), exit 0; report task 22:01:36 to 22:01:42, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`
  (sha256 `b5c189e23421fc2b4d4171e2acb64c66c565182756322e6c9889643dc1ea15b4`, 22:01:42),
  `android/app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec`.
- Evidence outputs are fresh and inputs stayed unchanged: yes; the exec file was rewritten by this run's test task
  and the hashes above were identical before and after the run.
- Final run after latest test additions and production fixes: yes (no production change during this run).

Values below use covered/total (percentage) from JaCoCo counters (INSTRUCTION shown as statements).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `dashboard/DashboardRepository.kt` | 1398/1414 (98.9 %) | 66/72 (91.7 %) | 66/67 (98.5 %) | 151/153 (98.7 %) |
| `dashboard/DashboardScreen.kt` | 1948/1964 (99.2 %) | 91/98 (92.9 %) | 28/28 (100 %) | 175/176 (99.4 %) |
| `dashboard/DashboardViewModel.kt` | 442/442 (100 %) | 16/16 (100 %) | 19/19 (100 %) | 46/46 (100 %) |
| **dashboard package (sum of the 3 files)** | **3788/3820 (99.2 %)** | **173/186 (93.0 %)** | **113/114 (99.1 %)** | **372/375 (99.2 %)** |
| `ui/CockpitShell.kt` (whole file, mostly pre-existing) | 1599/1634 (97.9 %) | 81/94 (86.2 %) | 42/42 (100 %) | 133/135 (98.5 %) |
| `ui/navigation/CockpitDestination.kt` | 374/374 (100 %) | 9/9 (100 %) | 13/13 (100 %) | 37/37 (100 %) |

No scoped file is missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `DashboardScreen.kt:60,61,72,73` | default `modifier` parameters of `DashboardScreen`/`DashboardRoute` (Compose-generated default branches) | Not material: generated code |
| `DashboardScreen.kt:133` (3/6 branches) | `LaunchedEffect(state.widgets)` in the scroll-to-top effect; the missed branches are Compose's generated `changed` bookkeeping. Both real paths (new order scrolls to top, same order does not) are tested | Covered by `when the stored layout arrives its first card is at the top` and the new `a reload with the same order keeps the scroll position` |
| `DashboardScreen.kt:202,234,235` | exhaustive `when` over the sealed `WidgetData` (generated else) | Not material: unreachable |
| `DashboardScreen.kt:265` (1/4) | a task without a due date in a task list: no overdue label | Not material: Today and Waiting-overdue tasks always have a due date from their queries |
| `DashboardRepository.kt:214,218` (2/4 each) | `nextCursor != null` is tested only as true for Read later and only as false for Pinned notes | Low risk: the same one-line mapping in both widgets, each value exercised once across them; no test added |
| `DashboardRepository.kt:246` (1/4) | unread mail > 0 on the last page (`more = false`) | Low risk; the other combinations are tested (`1+`, `0` on a cut page) |
| `DashboardRepository.kt:278,279` | `IllegalArgumentException` mapped to Failed (defensive; an unknown review step already fails in deserialization, `an unknown review step is a failed widget, not a crash`) | Not material: defensive catch |
| `DashboardRepository.kt:290` (1/4) | timed (non-all-day) occurrence without a `start` | Not material: defensive; an occurrence without any start is tested to fail its widget |
| `CockpitShell.kt:72,75,93,163,164,188-190,234,256` | pre-existing shell code not changed by this task (the `when` at 234 has 22 branches over all destinations) | Outside task scope; the task's changed lines 151 and 235 are covered |

## Unable to test

- Real device rendering and a live server: the dashboard is tested with Robolectric, Compose test rules and
  MockWebServer, not against a running server or device. The acceptance criteria name only the unit/Compose
  suite, so no follow-up is required for this task.
- The locale-aware day format is checked for English and German only (the two languages the app ships).

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
