# Test Report

Date: 2026-11-01

Task: and-dashboard - Android: Dashboard as start screen

Scope: `git diff 5a0b432aab72f5493a2947849d8e91b513866327..ea2c268532aec034131e08cce1024a6d4deb4845 -- android/`
(task commits 04cd32ebb..ea2c26853 on `main`, all prefixed `and-dashboard:`, plus the `reviews:` commit 66a3ecd04
that touches only `reviews/`). Working tree clean at the start of this run. Android only; server and web unchanged by
the task. This is the final round. It looks at the two commits made after the previous report:
- c371dc0fc (review finding CR-1, see `reviews/and-dashboard/fixes.md`): `DashboardRoute` now loads in
  `LifecycleResumeEffect(viewModel)` instead of `LaunchedEffect(Unit)`, so the dashboard also reloads when the app
  comes back to the foreground. It also adds the test `the dashboard loads again when the app comes back to it`.
- ea2c26853: adds the test `after the app was in the background the cards show what changed meanwhile`.

Earlier rounds measured against base `bcad1b61` and HEAD `d70cf47b`. Main was rebased since then, so the commit
hashes differ. `DashboardRepository.kt` and `DashboardViewModel.kt` have the same sha256 as in the earlier
coverage run. `DashboardScreen.kt` changed only in c371dc0fc.

Requirements:
- `tasks/and-dashboard.md` acceptance criteria #1-#6 and its ASSUMPTION notes
- `reviews/and-dashboard/review.md` CR-1 and `reviews/and-dashboard/fixes.md`
- `SPEC.md` §8.7 (dashboard widgets), `docs/ARCHITECTURE.md` "Coverage"
- `AGENTS.md` (Android check command, conventions)

## Test files changed

This round: none. The test-writer changed no files except this report.

Earlier rounds of this task: `android/app/src/test/java/ch/sonensei/cockpit/dashboard/DashboardScreenTest.kt`.
After the review, the parent added two tests to
`android/app/src/test/java/ch/sonensei/cockpit/dashboard/DashboardInAppTest.kt` (commits c371dc0fc, ea2c26853).

## Tests added or updated

Count: 0 this round. The dashboard package has 60 tests: Screen 19, Repository 17, ViewModel 9, InApp 9, Layout 4,
Strings 2.

### Is the resume reload (CR-1) tested well enough?

Yes. The in-app tests run the real `DashboardRoute` in the Hilt activity with a fake repository. They drive the
lifecycle through `ActivityScenario.moveToState`. Together they pin the observable behavior of
`LifecycleResumeEffect`:

- Opening the app loads once, not twice (the first resume is the first load):
  `with stored tokens the app opens on the real dashboard, selected in the bottom bar` checks
  `coVerify(exactly = 1) { repo.layout() }`.
- A pause alone (RESUMED to STARTED) does not load. Coming back to RESUMED reloads the layout and every shown
  widget: `the dashboard loads again when the app comes back to it`.
- When the app is fully in the background (stopped, CREATED), nothing loads while it is stopped. Coming back
  replaces the old data with the data that changed meanwhile, and the old text is gone:
  `after the app was in the background the cards show what changed meanwhile`. With the old
  `LaunchedEffect(Unit)` this test fails (no second `layout()` call, "3 tasks to process" stays), so it is a real
  regression test for CR-1.
- Leaving the Dashboard tab and coming back still reloads (the composition is disposed and then resumes again):
  `the dashboard loads again when it is shown again`.
- Pull to refresh is not affected: `pulling down reloads the layout and every shown widget`.

I checked these other cases and found no gap that needs a new test:
- A reload on a later day so that "today" moves on: the repository derives the day on every `load`. The tests above
  prove that `load` runs again on resume.
- Overlapping loads when the user resumes during a pull: the ViewModel drops older answers, which
  `DashboardViewModelTest` covers ("newer refresh drops older").
- Configuration change (rotation): the activity resumes again and reloads once. That costs only an extra request,
  and no acceptance criterion names it.


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

Earlier round: all rows were run by the full command `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`
(2026-11-01 21:57-22:01, on d70cf47b, BUILD SUCCESSFUL). Final code (ea2c26853): the parent ran
`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*'`, with BUILD SUCCESSFUL
and all 6 dashboard test classes passing (DashboardInAppTest 9 tests, 0 failures). The test-writer cannot run Gradle in
its environment.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: widgets of §8.7 shown, each card opens its screen | `DashboardScreenTest`: `every widget shows its data`, `each card opens the matching screen`, upcoming/overdue/long-list tests, the 2 new day-format tests; `DashboardRepositoryTest` (endpoints and mapping of each widget); `DashboardInAppTest`: Inbox card / Today card navigation | full Android command above | Pass |
| AC2: web layout decides widgets and order, else default order | `DashboardLayoutTest` (4), `DashboardViewModelTest` layout tests, `DashboardScreenTest`: `only the configured widgets show, in their order`, `when the stored layout arrives its first card is at the top`, new `a reload with the same order keeps the scroll position`; `DashboardInAppTest`: `the first configured card is at the top when the app opens`, `only the configured widgets show...` | full Android command above | Pass |
| AC3: app opens on Dashboard after login and on launch; bottom bar includes it | `DashboardInAppTest`: `with stored tokens the app opens on the real dashboard, selected in the bottom bar`; shell tests updated by the task (`AppStartRoutingTest`, `InAppLoginTest`, `CockpitShellNavigationTest`, `CockpitShellTest`, `CockpitDestinationTest`, `ReminderUiTest`) | full Android command above | Pass |
| AC4: one failing widget shows its error; pull to refresh reloads all | `DashboardViewModelTest`: failing widget, pull to refresh, slow widget, newer refresh drops older; `DashboardRepositoryTest`: failing call per widget, offline vs failed; `DashboardScreenTest`: error/offline/failed cards, `pulling down refreshes`; `DashboardInAppTest`: `pulling down reloads the layout and every shown widget`, `the dashboard loads again when it is shown again`, `the dashboard loads again when the app comes back to it`, `after the app was in the background the cards show what changed meanwhile` (CR-1) | dashboard-filtered command on ea2c26853 (parent) | Pass |
| AC5: all new strings in English and German | `DashboardStringsTest` (2), `DashboardScreenTest`: `in German the cards and messages are German`, new German day-format test; lint `MissingTranslation` in `lintDebug` | full Android command above | Pass |
| AC6: unit + Compose UI tests; `ktlintCheck testDebugUnitTest lintDebug` passes | whole suite on d70cf47b: 257 suites, 2725 tests, 0 failures, 0 errors, 0 skipped. On ea2c26853 only `ktlintCheck` plus the dashboard tests have been run | full command on d70cf47b; dashboard-filtered command on ea2c26853 | Pass on d70cf47b. For ea2c26853: Not run here: the parent must run the full `ktlintCheck testDebugUnitTest lintDebug` (or `.taskcheck`) on the final code |

## Focused test execution

Command (run by the parent on ea2c26853, which is the code reviewed here):

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*'`

Result:

Pass: BUILD SUCCESSFUL. All 6 dashboard test classes passed; DashboardInAppTest 9 tests, 0 failures.

No tests were added this round, so the parent does not need to run any additional test classes.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Pass on d70cf47b in the earlier round: BUILD SUCCESSFUL in 4m 17s; JUnit XML totals 257 suites, 2725 tests,
0 failures, 0 errors, 0 skipped. Not run on ea2c26853 by the test-writer (no Gradle in this environment).
The parent's `.taskcheck` (`sh scripts/check.sh`) on the final code covers this.

## .taskcheck

Result:

Not run by the test-writer (Gradle cannot run in this environment). The parent runs `sh scripts/check.sh` on the final
code before `done`.

## Bugs found

None. No production defect was found in the reviewed scope.

## Coverage

Status: partial. These are the verified numbers from the earlier fresh run on d70cf47b. They were not re-measured on
ea2c26853, because the test-writer cannot run Gradle or JaCoCo here. The numbers for `DashboardRepository.kt`,
`DashboardViewModel.kt`, `CockpitShell.kt` and `CockpitDestination.kt` still apply: the files are unchanged (the two
dashboard files have the same sha256 as the earlier run, and the shell files were not touched by c371dc0fc or
ea2c26853). The `DashboardScreen.kt` row describes the earlier version, before c371dc0fc. Expected effect of c371dc0fc:
the one-line `LaunchedEffect(Unit)` is replaced by the 4-line `LifecycleResumeEffect` block (current lines 79-82),
and every in-app test runs it. Its `onPauseOrDispose {}` body runs in the two new pause/stop tests and when the tab
is left. No new uncovered behavior is expected, but this is not measured. If the parent needs measured final
numbers, run `cd android && ./gradlew testDebugUnitTest createDebugUnitTestCoverageReport` and read
`app/build/reports/coverage/test/debug/report.xml`.

Final code snapshot (sha256 at ea2c26853, clean tree): `DashboardScreen.kt`
`37a7896114739078bddfc95b85504650ebca9566163783729c31c463523e5bf9`, `DashboardRepository.kt`
`bac095567140783faf8e4587e594dd2f0ffa002c989aabdd9c5d0171aba212ae`, `DashboardViewModel.kt`
`503b0651ac480031c0bb5d25968d42b5f96b013e34ff63b2abae03d007c99eb0`, `DashboardInAppTest.kt`
`f682caf0f6d09573c6f9c6d4b2a6803edeae9eff0e9db13dcad68c65ae9558d3`.

Earlier measured run (details below):

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by intersecting the task diff (`git diff -U0 bcad1b61..HEAD`) with JaCoCo
XML line entries. The three dashboard files are new in this task, so their changed-line coverage equals their
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
- Final run after latest test additions and production fixes: no. The run was on d70cf47b, before c371dc0fc (production change in `DashboardScreen.kt`) and ea2c26853 (tests only). It was not re-measured because Gradle cannot run in the test-writer environment.

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
| `DashboardScreen.kt:60,61,72,73` (line numbers of d70cf47b; in the current file add 1 for lines before the old line 76 and 5 from there on, e.g. old 133 is now 138) | default `modifier` parameters of `DashboardScreen`/`DashboardRoute` (Compose-generated default branches) | Not material: generated code |
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

No additional tests were necessary for the reviewed scope. The CR-1 resume reload is covered by in-app tests for
the first load (exactly once), pause-then-resume, a full background stop with changed data, and tab re-entry. All
dashboard tests passed on the final code (parent's run). No production defects were found. Coverage was not
re-measured on the final code; the earlier verified numbers and gaps are kept above, with the reason.
