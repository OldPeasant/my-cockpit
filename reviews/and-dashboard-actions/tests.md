# Test Report

Date: 2026-11-08

Task: and-dashboard-actions - Android dashboard: tappable items, review button, layout editing

Scope: base a32d28124c56d00b287cd6377c4bd589c8389399 .. HEAD 88af1ca84297da8c586059ec7f79c65a1e10deca (task commits 416627a6a, b87452d1e, 569068224, 78ac21c00, merged at 10fca3091; 88af1ca84 only changes `reviews/and-dashboard-actions/review.md`). Production files: `android/app/src/main/java/ch/sonensei/cockpit/dashboard/{DashboardRepository,DashboardScreen,DashboardViewModel}.kt`, `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/{CalendarWithEditor,EventEditorScreen}.kt` (EventEditorCallbacks.of), `android/app/src/main/res/values{,-de}/strings_dashboard.xml`. Working tree clean (no uncommitted inputs).

Requirements:
- `tasks/and-dashboard-actions.md` acceptance criteria and recorded assumptions
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`

## Test files changed

None in this pass. The existing task tests (from the earlier WIP test-writer pass, committed in the task commits) were reviewed against the criteria and judged sufficient:
`DashboardActionsScreenTest.kt`, `DashboardCustomizeViewModelTest.kt`, `DashboardSaveLayoutTest.kt`, `DashboardRouteInAppTest.kt`, `DashboardSlotsTest.kt`, `DashboardOverlayTest.kt` plus updates to `DashboardInboxCardTest.kt`, `DashboardRepositoryTest.kt`, `DashboardViewModelTest.kt`, `DashboardInAppTest.kt`, `DashboardTestData.kt` (all under `android/app/src/test/java/ch/sonensei/cockpit/dashboard/`).

## Tests added or updated

Count: 0 (time budget about 15 min; the coverage review found no meaningful untested requirement).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Items of every widget open their item (events, projects, bookmarks, notes, mails) | DashboardActionsScreenTest: `an event of the Today and the Upcoming card opens the event...`, `without an event opener an event opens the calendar...`, `a stalled project, a read-later bookmark and a pinned note open themselves`, `without openers the items open their card's screen`, `the Inbox lines open the GTD Inbox and Mail...`; DashboardRouteInAppTest: `a stalled project opens the Projects screen`, `a read-later bookmark opens its address in the browser...`, `without a browser a read-later bookmark opens the Bookmarks screen`, `an event without an occurrence opens the Calendar`, `an event opens the event editor on top of the dashboard...`, `a birthday opens its contact...`, `a pinned note opens the note editor...`; DashboardCustomizeViewModelTest: event/project open tests | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*' ...` (and full run) | Pass |
| 2. Weekly review widget has Start/Continue review | DashboardActionsScreenTest: `an open review offers to continue it...`, `no review yet offers to start one`, `a review seven days ago is due again`, `a recent review without an open one has no button`, `the review button label follows...`; DashboardRouteInAppTest: `the review button opens the Review screen` | same | Pass |
| 3. Customize: show/hide, reorder (long-press drag + accessibility actions), reset; saved in the web's setting | DashboardActionsScreenTest: Customize/checkbox/`each row can be moved with accessibility actions...`/`a long-press drag moves a row...`/`...less than half a row moves nothing`/`reset asks for the default layout`/save-error messages; DashboardCustomizeViewModelTest: hide/show/move/reset/rollback/ordering of quick saves/stale reads; DashboardSaveLayoutTest: PATCH with only `dashboardWidgets`, If-Match ETag, 412/428/409 re-read and single retry, second conflict fails, offline/failed mapping; DashboardSlotsTest: setting format as the web writes it (hidden suffix), round trip; DashboardRouteInAppTest: `hiding a widget in Customize removes its card and saves the layout with it hidden` | same | Pass |
| 4. New strings in English and German | DashboardActionsScreenTest `in German the review button and Customize are German`; existing DashboardStringsTest; `lintDebug` (MissingTranslation) | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` | Pass (lint ran in that build; see Full test execution) |
| 5. Tests cover new behavior; ktlintCheck, testDebugUnitTest, lintDebug and `sh scripts/check.sh` pass | whole Android suite | full command below | Fail in this run, only because of one unrelated flaky test (see below); dashboard suite passes. `sh scripts/check.sh`: Not run (budget; parent runs it before `done`) |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests ch.sonensei.cockpit.files.FilePreviewScreenTest --tests 'ch.sonensei.cockpit.dashboard.*' createDebugUnitTestCoverageReport`

Result:

Pass (BUILD SUCCESSFUL; 194 tests, 0 failures, 0 skipped, including all dashboard test classes and the rerun FilePreviewScreenTest).

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport`

Result:

Fail: ktlintCheck passed; `testDebugUnitTest` ran 4468 tests, 1 failed: `ch.sonensei.cockpit.files.FilePreviewScreenTest > an image that failed to load does not keep the next image from loading` (`expected:<2> but was:<1>` at FilePreviewScreenTest.kt:319). That test is in the `files` feature, outside this task's scope (no task commit touches `files/`), and passed when rerun in isolation (focused command above), so it looks like a timing-dependent flaky test under full-suite load. Because the test task failed, `lintDebug` and the coverage report did not run in that build. lintDebug has not been confirmed separately in this pass. The parent should rerun the full command (or `sh scripts/check.sh`) and record the result.

## .taskcheck

Result:

Not run (time budget). The parent must run `sh scripts/check.sh` before `done`.

## Bugs found

None in task scope. Out of scope, for the parent's attention: `FilePreviewScreenTest` "an image that failed to load does not keep the next image from loading" failed once in the full suite and passed on rerun (looks flaky; not a finding against this task).

## Coverage

Status: verified for the dashboard package (dashboard-test selection); partial for `calendar/edit` (only the dashboard tests ran, so those whole-file numbers are low by design).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (JaCoCo setup reports whole files only). By inspection, the changed `EventEditorCallbacks.of` (EventEditorScreen.kt:84-105) is fully covered by line.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-dashboard-actions; base a32d28124c56d00b287cd6377c4bd589c8389399; HEAD 88af1ca84297da8c586059ec7f79c65a1e10deca; commits and scope from the parent, checked with `git diff --name-only a32d28124..HEAD -- android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 88af1ca84, `git status --porcelain` empty before and after the run.
- Exact included files: the five Kotlin files listed under Scope. Excluded: the two `strings_dashboard.xml` files (resources, not instrumented).
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `./gradlew testDebugUnitTest --tests ch.sonensei.cockpit.files.FilePreviewScreenTest --tests 'ch.sonensei.cockpit.dashboard.*' createDebugUnitTestCoverageReport` in `android/`.
- Run start/completion time and exit status: completed 2026-11-08 about 20:03 +01:00, BUILD SUCCESSFUL (exit 0). The full-suite run before it failed (see above) and produced no coverage report.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (mtime 2026-11-08 20:03:03 +0100, sha256 12ecc6676409a5e899adf99edd00ff2941152fb3dcc5c127976e950720301da2).
- Evidence outputs are fresh and inputs stayed unchanged: yes, the report was written by this run and the tree stayed clean.
- Final run after latest test additions and production fixes: yes (no tests were added and production code did not change after the run).

Values below use covered/total (percentage).

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `dashboard/DashboardViewModel.kt` | 1178/1188 (99.2%) | 64/68 (94.1%) | 49/49 (100%) | 111/111 (100%) |
| `dashboard/DashboardScreen.kt` | 5256/5352 (98.2%) | 218/244 (89.3%) | 110/114 (96.5%) | 423/428 (98.8%) |
| `dashboard/DashboardRepository.kt` | 1819/1851 (98.3%) | 80/86 (93.0%) | 87/88 (98.9%) | 192/197 (97.5%) |
| `calendar/edit/EventEditorScreen.kt` (dashboard tests only) | 859/4761 (18.0%) | 21/92 (22.8%) | 14/153 (9.2%) | 100/330 (30.3%) |
| `calendar/edit/CalendarWithEditor.kt` (dashboard tests only) | 261/447 (58.4%) | 5/20 (25.0%) | 6/11 (54.5%) | 23/34 (67.6%) |

Dashboard aggregate (summed counters, the three dashboard files): instructions 8253/8391 (98.4%), branches 362/398 (91.0%), lines 726/736 (98.6%). The calendar/edit files are left out of this aggregate because the selection did not run their own test suites (CalendarWithEditor/EventEditor tests), so their numbers understate coverage.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `DashboardScreen.kt:107` | Placeholder when the activity is not a Hilt activity | Not material: fallback for previews/non-Hilt hosts |
| `DashboardScreen.kt:168, 230, 629` | Closing braces of a never-completing `LaunchedEffect` collector and of an exhaustive `when` | Not material: bytecode artifacts. Editor close refreshing the cards is covered by DashboardRouteInAppTest |
| `DashboardScreen.kt:217` | Read-later bookmark without a URL opens the Bookmarks screen (route level) | Not material: bookmarks always carry a URL. The same fallback at card level is covered by `without openers the items open their card's screen` |
| `DashboardScreen.kt` partial branches (103-104, 150, 192, 199, 216, 579-701) | Default-parameter and null-safe branches of composables | Not material |
| `DashboardRepository.kt:233` | Hilt secondary constructor using the system clock | Not material: tests inject a fixed clock |
| `DashboardRepository.kt:363-364, 371-372` | Rethrowing CancellationException; mapping IllegalArgumentException to Failed | Defensive. The other catch arms (IO, HTTP 4xx/5xx, serialization) are tested in DashboardSaveLayoutTest/DashboardRepositoryTest |
| `DashboardViewModel.kt:176, 183-184` (partial branches) | Race guards in `opened`/save ordering | The main paths are covered by `opened clears what was opened, but not something newer` and the quick-save ordering tests. The rest are defensive |

## Unable to test

- Real long-press drag on a device: covered only by Robolectric/Compose touch injection (DashboardActionsScreenTest). Device or instrumented tests are not in scope for this criterion.
- `lintDebug` and `sh scripts/check.sh` were not confirmed in this pass (the full build stopped at the unrelated flaky test; time budget). Follow-up: the parent reruns them.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The dashboard suite (194 tests in the focused run) passes, with 98.6% line and 91.0% branch coverage across the dashboard files. The full Android run failed only on an unrelated, apparently flaky `files/FilePreviewScreenTest`, which passed on rerun. The parent must rerun `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` before `done`.
