# Test Report

Date: 2026-11-01

Task: and-bookmarks - Android: Bookmarks screen (list, read later, open, edit)

Scope: base 126a16a6b6e4fabc79733a53fd22976a920e83c8 .. HEAD 69efcda30c4515a8690e2bd5b51c8d2be537be62 on `main`. Task commits: 55a1f3dbd (implementation), e22139971 and ec6be222a (tests and reports), 443d2373a (fixes for review findings CR-1 to CR-4), 69efcda30 (this run's tests, committed by the parent, plus the re-review report). This is a re-run on the final code after the fixes. The production code at 69efcda30 is the same as at 443d2373a: `git diff --stat 443d2373a 69efcda30` lists only `BookmarksViewModelTest.kt` and `reviews/and-bookmarks/review.md`. The parent supplied the task commits. `git diff --name-only 126a16a6b..69efcda30` lists only the task's bookmark sources, its tests, its string files and its reports. The working tree was clean when I measured.

Requirements:
- `tasks/and-bookmarks.md` acceptance criteria and recorded assumptions: client-side search, notes = description, favicons only, ACTION_VIEW instead of Custom Tabs, reload on resume and when the capture queue's pending count drops
- SPEC.md §8.4 / §9.2 item 8 as referenced by the task
- docs/ARCHITECTURE.md "Coverage" and code conventions (EN/DE strings, ktlint, lint)
- Fixed behavior from `reviews/and-bookmarks/review.md` and `fixes.md`: CR-1 numbered snackbars, CR-2 draft kept on a failed save, CR-3 stale load cancelled on a local change, CR-4 `bookmarks_offline_empty` text

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModelTest.kt`: 5 tests added in this run, committed by the parent in 69efcda30

Earlier in the task: `BookmarksModelsTest.kt`, `BookmarksRouteTest.kt` (new), `BookmarksScreenTest.kt`, `BookmarksRepositoryTest.kt`. The fixer's commit 443d2373a added 3 view model tests and 4 screen tests. In that commit, five view model assertions changed from comparing the whole `Failed(kind)` snackbar to comparing its failure kind, because snackbars now carry an id. They still assert the same failure kind, so none was weakened. I reviewed these changes. In this run, no existing test was changed, weakened, skipped or removed.

## Tests added or updated

Count: 5 added in this run (62 before, now 67 in `ch.sonensei.cockpit.bookmarks`: Models 7, Repository 6, Route 3, Screen 21, ViewModel 30)

I reviewed the fixer's tests for CR-1 to CR-4 and found them meaningful:
- CR-1: two equal failures are unequal snackbars, and dismissing the first keeps the second. The screen shows `Failed(OFFLINE, id = 2)` again after `id = 1` and reports dismiss of id 2.
- CR-2: the editor reopens with the draft after an offline save, and after a 412 conflict it reopens on the reloaded version. The next save sends the reloaded version. A 404 save does not reopen. The screen shows the draft fields and sends them.
- CR-3: a held load answering after a delete does not bring the deleted bookmark back. This test would fail without the fix.
- CR-4: the short offline text shows when nothing is loaded and the long text once a list is loaded, in EN and DE.

The added tests cover the remaining paths of the fixed code:
- `a save conflict whose reload is offline reopens the sent version, one whose bookmark is gone does not reopen`: CR-2 for the two conflict outcomes not tested before. An offline reload reopens on the sent version with the draft, and `cancelEdit` clears both. A 404 reload removes the bookmark and does not reopen.
- `a failed save does not take over the editor of another bookmark opened meanwhile`: CR-2 when the user opened another bookmark's editor while the save was running. That editor stays, with no stale draft.
- `marking a bookmark read during a pulled refresh restarts the load, still marked as pulled`: CR-3 through `put` (read toggle); the existing test only covers `remove` (delete). The read bookmark leaves the queue at once. A new load starts and keeps `refreshing = true`. The old load's late answer is ignored, and the new load's list is applied.
- `a change without a running load does not start one`: CR-3 starts no extra load when nothing is loading.
- `trashing the same bookmark twice gives two snackbars`: CR-1 for `Trashed`. Delete, undo and delete again give two unequal snackbars for the same bookmark, and dismissing the first keeps the second.

## Requirement coverage

All rows were run by the parent with the commands below, at HEAD 69efcda30.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 list with title, site, favicon/initial, tags; views Read later (default) and All; tag filter; search | ScreenTest `rows show title, site, notes, tags...`, `the tag filter marks the chosen tag`, `empty states follow the view and tag`, `the search field reports...`; VMTest `starts with the read-later queue...`, `the search filters...`; ModelsTest search/host/tag tests; RepositoryTest queue and all-bookmarks list requests | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*' -q` | Pass (loading of remote favicon images is not tested, see the gaps) |
| AC2 tap opens URL in browser; mark read/unread, edit title/tags/notes, delete to Trash with undo | RouteTest `tapping a bookmark opens its address in the browser`, `without a browser on the device...`; ScreenTest menu/editor/undo tests; VMTest read/edit/delete/undo/conflict/busy tests; RepositoryTest PATCH/DELETE/restore tests | same | Pass |
| AC3 pull to refresh; loading, empty, error states | ScreenTest `pulling the list down asks to refresh...`, `empty states follow the view and tag`, `a load problem is shown with a retry`, `a failed load says so, and retry waits...`; VMTest `a failed load keeps the list...`, `a first load that fails...` | same | Pass |
| AC4 shared bookmark appears without restart | RouteTest `the list loads once when shown and again when the app comes back to it`; VMTest `a share sent by the capture queue reloads the list...`, `a share sent before the first load does not load the list` | same | Pass |
| AC5 strings in EN and DE | ScreenTest `the screen is translated to German`, `offline with nothing loaded is translated to German`; `MissingTranslation` enforced by `lintDebug` | same; `cd android && ./gradlew ktlintCheck createDebugUnitTestCoverageReport lintDebug -q` | Pass (lintDebug 0 errors, 0 warnings) |
| AC6 unit + Compose tests; `ktlintCheck testDebugUnitTest lintDebug` pass | all of the above | both commands above | Pass |
| CR-1 each snackbar shown, the end of one never clears another | VMTest `two equal failures in a row are two snackbars...`, `trashing the same bookmark twice gives two snackbars`, `dismissing an older snackbar keeps a newer one`; ScreenTest `an equal failure after another is shown again and reports its own end` | focused command | Pass |
| CR-2 a failed save keeps the draft | VMTest `a failed save opens the editor again with the draft...`, `a save conflict whose reload is offline reopens the sent version...`, `a failed save does not take over the editor of another bookmark...`; ScreenTest `the editor opened again after a failed save shows the unsaved draft` | focused command | Pass |
| CR-3 a load started before a local change does not restore the old state | VMTest `a load that started before a change does not bring the old bookmark back`, `marking a bookmark read during a pulled refresh restarts the load...`, `a change without a running load does not start one` | focused command | Pass |
| CR-4 offline with nothing loaded does not claim to show the last bookmarks | ScreenTest `offline with nothing loaded for the view does not claim...`, `offline with nothing loaded is translated to German` | focused command | Pass |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*' -q`

Result:

Pass: exit 0, 67 tests in 5 suites (Models, Repository, Route, Screen, ViewModel), 0 failures; ktlintCheck clean. The parent ran this, because the test-writer is not allowed to run Gradle in this environment; the results above are the parent's.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck createDebugUnitTestCoverageReport lintDebug -q` (runs the whole Android debug unit suite with coverage)

Result:

Pass: exit 0, finished 2026-11-01 about 14:40 CET. 2313 tests, 0 failures, 0 errors, 0 skipped. lintDebug: 0 errors, 0 warnings, 2 hints, both in journal/notes, outside this task. The parent ran this command.

## .taskcheck

Result:

Not run by the test-writer. `sh scripts/check.sh` covers server, web and android and is the parent's final validation step before `done`.

## Bugs found

None. No production defect was found. All 5 added tests passed against the fixed code at 443d2373a (unchanged at 69efcda30).

## Coverage

Status: verified (whole-file, Android JaCoCo). The parent ran a fresh measurement after the latest test additions, on unchanged production code.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. Three of the four files are new in the task. `BookmarksScreen.kt` was a placeholder that the task replaced almost entirely. So whole-file numbers are close to changed-line numbers, but no intersection with the diff was computed.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-bookmarks; base 126a16a6b6e4fabc79733a53fd22976a920e83c8; HEAD 69efcda30c4515a8690e2bd5b51c8d2be537be62; commits 55a1f3dbd, e22139971, ec6be222a, 443d2373a, 69efcda30 given by the parent, matching `git diff --name-only 126a16a6b..69efcda30`.
- Source/test snapshot identifier (including uncommitted inputs): commit 69efcda30. The working tree was clean (`git status --short` empty), so there were no uncommitted inputs.
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksModels.kt`, `BookmarksRepository.kt`, `BookmarksViewModel.kt`, `BookmarksScreen.kt`. Excluded: `res/values/strings_bookmarks.xml` and `res/values-de/strings_bookmarks.xml`, because resources are not instrumented; they are covered by the German UI tests and `lintDebug`. Hilt-generated classes are not task sources; they are listed below for information. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (Gradle 9.8.1, JDK 21); `./gradlew ktlintCheck createDebugUnitTestCoverageReport lintDebug -q` in `android/`; the full debug unit suite.
- Run start/completion time and exit status: completed 2026-11-01 about 14:40 CET, exit 0. The parent reported the completion time; the start time was not reported.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, written 14:40:03. sha256 ec92528586fadced91cf70dc9312fd28fd5f1ae6c8925c663abb93213b8f2971: I checked this hash myself, and it matches the parent's value. The test-writer read the uncovered lines and branches from the `sourcefile` elements of package `ch/sonensei/cockpit/bookmarks` in this file.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report was written after the commit with the new tests, its hash matches the parent's run, and the production code is unchanged since 443d2373a. Earlier reports (14:21:36, sha256 cc13be9a...) describe the pre-fix revision and were not used.
- Final run after latest test additions and production fixes: yes.

Values below use covered/total (percentage).

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `BookmarksModels.kt` | 413/424 (97.4%) | 30/32 (93.8%) | 27/30 (90.0%) | 58/58 (100%) |
| `BookmarksRepository.kt` | 476/537 (88.6%) | 20/24 (83.3%) | 27/28 (96.4%) | 64/70 (91.4%) |
| `BookmarksViewModel.kt` | 1654/1675 (98.7%) | 103/112 (92.0%) | 66/69 (95.7%) | 159/162 (98.1%) |
| `BookmarksScreen.kt` | 4552/4839 (94.1%) | 142/178 (79.8%) | 96/117 (82.1%) | 357/368 (97.0%) |
| Total (summed counters; all 4 Kotlin files measured; 2 string resource files excluded) | 7095/7475 (94.9%) | 295/346 (85.3%) | 216/244 (88.5%) | 638/658 (97.0%) |

For information, the Hilt-generated classes: `BookmarksViewModel_HiltModules` 1/1 line, `BookmarksViewModel_HiltModules_KeyModule_ProvideFactory` 0/1 line (its line 40).

Compared with the pre-fix report: ViewModel branches went from 95/104 to 103/112, and lines from 141/143 to 159/162. The fix added 8 branches, and all 8 are covered. The three new uncovered ViewModel lines are listed below.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `BookmarksViewModel.kt:47` | `NoBrowser.id` (constant 0) is never read | Non-material: a constant. Nothing reads it, because a NoBrowser snackbar is only compared by equality. |
| `BookmarksViewModel.kt:37` (partial) | Default `id = 0` of `Trashed` (synthetic default constructor) | Non-material: the view model always passes an id. The default exists for previews and screen tests. |
| `BookmarksViewModel.kt:90, 108` | `@Inject` constructor mapping `CaptureQueue.status`; end of the never-ending collect | Hilt glue / unreachable. The internal constructor with a pending-count flow is tested. |
| `BookmarksViewModel.kt` branches 238, 269, 290, 301, 312, 320 | Change of a bookmark not in the list (238); a local change before any load ever started, so `loadJob` is null (269); a conflict reload failing with another status (non-404 `Failed`) returns the sent version (290); the null side of `as? Failed` in `failure()` (301); the tag reload after a change failing (312); case-insensitive tag match in `belongs` (320) | Low risk. The main paths are tested, and the new tests cover the 404 and offline reload outcomes beside them. Branch 290 behaves like the tested offline case, because both return the sent version. |
| `BookmarksScreen.kt:433-447` (lines 437-443, branches 435-436) | Favicon loading through Coil when the API provides an https favicon (AC1) | Justified gap: exercising it would fetch from a real external site. The tests use no ImageLoader and assert the initial-letter fallback. Follow-up only if a fake Coil fetcher is wanted. |
| `BookmarksScreen.kt:84-96` (lines 86, 95) | Hilt entry (`BookmarksScreen` in a Hilt activity, `hiltViewModel()` default) | Not reachable without a Hilt test activity. The route itself is tested with an explicit view model. |
| `BookmarksScreen.kt:128-129` (partial) | Route lambdas for pull-to-refresh and retry, which call the view model | Low risk. Both callbacks are tested on `BookmarksContent`, and `refresh(pulled)` is tested on the view model. Only the one-line wiring in the route is not executed. |
| `BookmarksScreen.kt:144-160` (partial) | Default arguments of `BookmarksCallbacks` (synthetic default constructor) | Non-material. |
| `BookmarksScreen.kt:321, 326`, branches 350, 381, 405, 455-458, 514-516, 540, 574, 599 | Synthetic branches: `when` on an enum (no-match), Compose-generated recomposition and remember branches, and the `?: "?"` initial for a site without letters or digits | Non-material. The behavior behind them is tested: both offline texts and the failed text, the draft and no-draft editor, read and unread rows, all snackbar kinds, dismiss on timeout and undo. |
| `BookmarksRepository.kt:53` | Hilt `@Provides` for `BookmarksWriteApi` | Dependency-injection glue; non-material. |
| `BookmarksRepository.kt:161-162, 169-172`, branches 97, 143, 150, 193; partial 115, 117 | Rethrowing cancellation; IllegalArgument/DateTime parse failures; the 50-page cap; a null body on a 2xx answer | Defensive paths and unchanged since the first report. The unreadable-answer path and the offline and server-error mapping are tested. Low risk. |
| `BookmarksModels.kt` branch 80; partial 22, 24, 41 | `hostOf` with an empty host string; synthetic data-class code | Low risk. The null-host and unparseable cases are tested. |

## Unable to test

- Loading real favicon images from remote sites would call external services from unit tests. The fallback initial is tested.
- Custom Tab vs default browser: the recorded assumption uses ACTION_VIEW. The intent is tested; the real browser app is not.
- Pull-to-refresh is tested by a Robolectric swipe gesture on the list, not on a device.
- The test-writer was not allowed to run Gradle. The parent ran every command in this report, and the results come from that run.
- `.taskcheck` is left to the parent's final validation.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found. The fixed behavior (CR-1 to CR-4) is covered by focused tests, including the conflict, offline and concurrent-editor paths. The remaining gaps are justified above: remote favicon loading, Hilt glue, synthetic Compose branches and defensive repository catches.
