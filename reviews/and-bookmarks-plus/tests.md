# Test Report

Date: 2026-11-08

Task: and-bookmarks-plus - Android bookmarks: add, read-later toggle, reader, to inbox

Scope: task base `6fbabadfbafe865bbdba739d8dc5de77cb446065` .. `4bb0f38f2183e273c9309345b5f461174609bdb6` (task commits `2f0bc106` implementation, `4bb0f38f` tests from an earlier test-writer run that stopped before writing its report), plus this run's uncommitted test changes (listed below). Branch `main`; no uncommitted production changes.

Requirements:
- `tasks/and-bookmarks-plus.md` acceptance criteria (#1-#6)
- `SPEC.md` §8.4 (bookmarks, read-later, snapshot), §4.5 (captures from bookmarks create Inbox items linked to the origin)
- `docs/PARITY.md` row for and-bookmarks-plus, `docs/ARCHITECTURE.md` "Coverage" and conventions (EN + DE strings)
- `api/openapi.yaml` (`CreateTaskRequest`, `CreateLinkRequest`, `POST /api/v1/bookmarks/{id}/snapshot`) for request shapes

## Test files changed

Committed in task commit `4bb0f38f` (reviewed in this run, kept unchanged except as noted):
- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksAddReaderScreenTest.kt` (new in 4bb0f38f; extended in this run)
- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksAddReaderViewModelTest.kt` (new in 4bb0f38f; extended in this run)
- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksModelsTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksRouteTest.kt`

Added in this run (uncommitted):
- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksStringsTest.kt` (new)

## Tests added or updated

Count: 7 added in this run (on top of the 4bb0f38f tests); no existing test weakened, removed or skipped.

- `BookmarksAddReaderViewModelTest`
  - `a saved bookmark joins a tag-filtered list only when it has the tag, regardless of case`
  - `a saved bookmark the list already shows is replaced, not shown twice`
  - `a copy saved after the reader was left does not reopen it or change the reader of another bookmark`
  - `a new copy that cannot be loaded after saving says why and keeps the saved bookmark`
- `BookmarksAddReaderScreenTest`
  - `the row menu, the add button and the Inbox confirmations are translated to German` (Robolectric `de`)
- `BookmarksStringsTest` (new, same pattern as `DashboardStringsTest`)
  - `every bookmarks string exists in German with the same placeholders`
  - `the strings of add, read later, the reader and to Inbox are in both languages`

Review of the earlier run's tests: they map well onto the criteria (view model, repository against MockWebServer, Compose screen, route with a real view model and Robolectric intents), control ordering with `CompletableDeferred` / `StandardTestDispatcher` rather than delays, and use fictitious data (`example.org`). Gaps filled above: add with a tag filter / duplicate id, closing the reader during a snapshot save, a saved copy that then fails to load, German texts of the row menu and Inbox snackbars, and a file-level EN/DE key-parity check.

## Requirement coverage

Focused command: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*'` (136 tests, 0 failures).
Full command: `cd android && ./gradlew ktlintCheck lintDebug createDebugUnitTestCoverageReport` (runs the full `testDebugUnitTest`: 3929 tests, 0 failures).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| #1 "Add bookmark" in the screen (URL, optional title/tags, read later) | VM: `the add dialog presets read later in the queue but not in all bookmarks, and a second open keeps it`, `an address that is no web address is refused without asking the server and keeps the input`, `adding without an open dialog does nothing`, `a saved bookmark closes the dialog, joins the list in order and reloads the tags`, `a saved bookmark that is not read later stays out of the queue`, `a bookmark saved before the list was loaded is not shown as the whole list`, `a bookmark saved during a load restarts the load so it is not lost`, `a failed save keeps the dialog open with the input and the reason`, `a retry after a failed save clears the old reason while it saves`, `a saved bookmark joins a tag-filtered list only when it has the tag, regardless of case`, `a saved bookmark the list already shows is replaced, not shown twice`; Repo: `a new bookmark sends the trimmed address, title, parsed tags and read later`, `a new bookmark without title and tags leaves them out for the server to fill`, `a refused new bookmark is a failure with its status, no connection is offline`; Screen: `the add button asks to open the dialog`, `the add dialog saves the address, title, tags and read later as typed`, `the add dialog cannot save a blank address and can be cancelled`, `the add dialog opened again after a failure shows the input and the reason`, `while it saves the add dialog cannot be changed, saved again or cancelled`; Route: `a bookmark added in the dialog is saved and shown in the queue`; Models: `a web address is an absolute http or https address with a host` | focused + full (above) | Pass |
| #2 Toggle read-later on an existing bookmark | VM: `taking a bookmark out of read later drops it from the queue, putting one in keeps it in all bookmarks`, `a failed read-later toggle keeps the bookmark and says why, a conflict reloads it`, `changes in the reader use its version, also after the bookmark left the queue`; Repo: `toggling read later sends only the read-later flag with If-Match`; Screen: `the row menu toggles read later, opens the reader and makes an Inbox task`, `the reader's buttons go back, open the original, change the bookmark and save a copy`, `while a change of the bookmark runs, the reader's actions are disabled` | focused + full | Pass |
| #3 Snapshot reader view (server snapshot) | VM: `the reader loads the latest bookmark and its readable copy`, `a bookmark without a copy opens the reader empty without asking for one`, `a reader that cannot load says why, and retry loads it again`, `a copy that cannot be loaded says why, offline or failed`, `a bookmark gone meanwhile closes the reader and leaves the list`, `retry does nothing without a reader or while it loads`, `saving a copy does nothing without a reader, and retry waits while a copy is saved`, `a reader closed while it loads is not opened again by the late answer`, `the copy of a bookmark read earlier does not show up in the reader of another one`, `saving a copy stores it and shows it`, `a copy saved after the reader was left does not reopen it or change the reader of another bookmark`, `a new copy that cannot be loaded after saving says why and keeps the saved bookmark`, `a page the server cannot read says so and keeps the earlier copy`, `a refused copy says why in a snackbar, a conflict reloads the bookmark of the reader`, `saving a copy of a bookmark gone meanwhile closes the reader and leaves the list`; Repo: `the readable copy is the file preview, and says when it was cut short`, `a readable copy that is gone or unreachable is a failure or offline`, `saving a copy asks the server with If-Match and returns the bookmark with its copy`, `a page the server cannot read is ok without a bookmark, other refusals stay failures`; Screen: `the reader replaces the list and shows the readable copy as text`, `the device's back closes the reader`, `a bookmark without a copy offers to save one, and a read one to mark it unread`, `while the reader loads or saves it shows progress and no empty text`, `a reader that could not load says why and offers to retry`, `an unreadable page says so, keeps the earlier copy and offers no retry`; Route: `a relative link in the readable copy opens in the browser, resolved against the page`, `a script link in the readable copy opens nothing`; Models: `a link in the readable copy is resolved against the page, and only web addresses are opened` | focused + full | Pass |
| #4 "To Inbox task" action | VM: `to Inbox says it was added and whether it was linked, each time as a new note`, `a second to Inbox while the first runs makes no second task`, `a failed to Inbox says why`; Repo: `to Inbox creates an Inbox task with the title and address and links the bookmark as its origin`, `a task title longer than the server allows is cut to 500 characters`, `a task whose link fails is still ok but reported unlinked`, `a task whose link has no connection is still ok but reported unlinked`, `a refused or unreadable task makes no link`; Screen: `the row menu toggles read later, opens the reader and makes an Inbox task`, `an Inbox task is confirmed in a snackbar, also when it could not be linked` | focused + full | Pass |
| #5 New strings in English and German | `BookmarksStringsTest` (both tests); Screen (`de`): `the add dialog and the reader are translated to German`, `the row menu, the add button and the Inbox confirmations are translated to German`; `lintDebug` (`MissingTranslation`) | focused + full (incl. `lintDebug`) | Pass |
| #6 Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | all of the above | `./gradlew ktlintCheck lintDebug createDebugUnitTestCoverageReport` (includes `testDebugUnitTest`); `sh scripts/check.sh android` | Android gradle part: Pass. Full `sh scripts/check.sh`: Not run to completion here (its web step needs a newer Node.js than this shell has, see ".taskcheck"); the parent must run it |

Instrumented tests on a device/emulator were not run; the criteria only name JVM unit/Compose (Robolectric) tests.

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*' --console=plain`

Result:

Pass - BUILD SUCCESSFUL; 8 suites, 136 tests, 0 failures, 0 errors, 0 skipped (`BookmarksAddReaderViewModelTest` 32, `BookmarksAddReaderScreenTest` 17, `BookmarksRouteTest` 6, `BookmarksRepositoryTest` 19, `BookmarksViewModelTest` 30, `BookmarksModelsTest` 9, `BookmarksScreenTest` 21, `BookmarksStringsTest` 2). ktlint passed on the test source set.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck lintDebug createDebugUnitTestCoverageReport --console=plain`

Result:

Pass - BUILD SUCCESSFUL in 4m 41s; full `testDebugUnitTest`: 338 suites, 3929 tests, 0 failures, 0 errors, 0 skipped; `ktlintCheck` and `lintDebug` passed.

## .taskcheck

Result:

Partial: Fail overall in this environment, for a reason outside the task; Pass for the parts the task touches.

- `sh scripts/check.sh` (all parts): `tfp` passed (22 tests OK), `server` (`./gradlew check`) passed, `web` failed before running any test: "Node.js version v20.9.0 detected. The Angular CLI requires a minimum Node.js version of v22.22.3 ..." (exit 3). This is the shell's Node.js runtime, not the code; the task changes no web files. The `android` step did not run because the script stops at the first failure.
- `sh scripts/check.sh android` (`./gradlew --quiet ktlintCheck testDebugUnitTest lintDebug`): Pass ("all checks passed (android)", exit 0).
- The parent must run the full `.taskcheck` with the project's required Node.js version (as `tasks.py done` does) before completing the task.

## Bugs found

None. No test exposed behavior that violates the acceptance criteria or SPEC.md.

Observation (not a defect against a stated requirement; for the parent to decide): after "Save a new copy" fails with `ReaderFailure.UNREADABLE`, the reader shows the error and the earlier copy but neither a retry nor the save button (`BookmarkReader.kt` `ReaderBody`, the `UNREADABLE` branch), so the user has to close and reopen the reader to try again. This is asserted as intended by the existing test `an unreadable page says so, keeps the earlier copy and offers no retry`; SPEC.md does not define this.

## Coverage

Status: verified (fresh run, exit 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately - executable lines (JaCoCo per-line entries of the XML report, source-mapped) intersected with lines added/modified in `git diff -U0 6fbabadf..4bb0f38f` per file; a line counts as covered when `ci > 0`. Result: 469/472 (99.4%). Changed-branch coverage was not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-bookmarks-plus`; base `6fbabadfbafe865bbdba739d8dc5de77cb446065` (given by the parent, = the task's claim commit); measured HEAD `4bb0f38f2183e273c9309345b5f461174609bdb6`; task commits `2f0bc106`, `4bb0f38f` (parent-supplied, consistent with `git log 6fbabadf..HEAD` and the task's `touches`).
- Source/test snapshot identifier (including uncommitted inputs): HEAD `4bb0f38f` + uncommitted tests, sha256: `BookmarksAddReaderViewModelTest.kt` 8d945916e46ea8eb2985d799654ee488945fb352ab4e9d2213766bab0c276e3e, `BookmarksAddReaderScreenTest.kt` a53021966171806d86944c9ed0bc6e40b1e11cbf28103722c23f026c2fb2d86b, `BookmarksStringsTest.kt` 397bf32a3487e0dccfd04a5c91f32220ee1655d8142dea5d5aa615d88d0b7d6f. Hashes identical before and after the run. No uncommitted production changes.
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/{BookmarkAdder,BookmarkReader,BookmarksModels,BookmarksRepository,BookmarksScreen,BookmarksViewModel}.kt`. Excluded: `android/app/src/main/res/values/strings_bookmarks.xml`, `android/app/src/main/res/values-de/strings_bookmarks.xml` (resources, not instrumentable; covered by `BookmarksStringsTest` and `lintDebug`). No renames or deletions. All 6 files were found in the report (none missing).
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (Gradle 9.8.1); `./gradlew ktlintCheck lintDebug createDebugUnitTestCoverageReport --console=plain` in `android/`; full `testDebugUnitTest` (no filter).
- Run start/completion time and exit status: started after 2026-11-08T16:42:22Z, report written 2026-11-08T16:47:10Z, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` sha256 6128f0f138a1c7b1541fdb97100ee54442176e3821935ecfe0ad9eaceb5a7bf4; `.../index.html`.
- Evidence outputs are fresh and inputs stayed unchanged: yes - the report predating the run had mtime 16:37:32Z; the run rewrote it at 16:47:10Z (after the run start); input hashes unchanged.
- Final run after latest test additions and production fixes: yes for the test additions; no production fixes were made. If the fixer changes production code later, this measurement is stale.

Values below use covered/total (percentage); JaCoCo has no separate statement counter, so the Statements column shows the INSTRUCTION counter.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarkAdder.kt` | 1190/1194 (99.7%) | 31/35 (88.6%) | 30/30 (100.0%) | 70/70 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarkReader.kt` | 1237/1240 (99.8%) | 44/49 (89.8%) | 18/18 (100.0%) | 101/101 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksModels.kt` | 638/646 (98.8%) | 51/54 (94.4%) | 41/43 (95.3%) | 75/75 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksRepository.kt` | 910/971 (93.7%) | 40/44 (90.9%) | 38/39 (97.4%) | 116/122 (95.1%) |
| `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksScreen.kt` | 5668/5896 (96.1%) | 145/170 (85.3%) | 117/147 (79.6%) | 426/436 (97.7%) |
| `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModel.kt` | 3003/3043 (98.7%) | 188/206 (91.3%) | 109/113 (96.5%) | 288/292 (98.6%) |
| **Aggregate (summed counters, all 6 files; 2 resource files excluded as above, none unmeasured)** | 12646/12990 (97.4%) | 499/558 (89.4%) | 353/390 (90.5%) | 1076/1096 (98.2%) |

Changed-line coverage per file: `BookmarkAdder.kt` 70/70, `BookmarkReader.kt` 101/101, `BookmarksModels.kt` 16/16, `BookmarksRepository.kt` 50/52, `BookmarksScreen.kt` 101/101, `BookmarksViewModel.kt` 131/132; total 469/472 (99.4%).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `BookmarksViewModel.kt:49` (changed) | default `id = 0` of `AddedToInbox` (the view model always passes an id) | Non-material; default parameter accessor only. |
| `BookmarksViewModel.kt:53, 131, 150` (pre-existing) | `NoBrowser.id`, Hilt `@Inject` constructor, the init collector's closing line | Not task code / DI glue. |
| `BookmarksViewModel.kt:322, 360, 439-442, 246` (partial branches) | reader fallbacks and failure mapping | Main outcomes covered (404 closes reader, other failures, updates guarded by bookmark id, reader-only bookmark, 400 -> invalid URL); remaining branches are null-safety combinations. Tests added for closing the reader during a save and for a failed load of a new copy. |
| `BookmarksRepository.kt:237-238` (changed) | `CancellationException` rethrow in `link()` | Defensive cancellation path, not reachable deterministically in the existing MockWebServer setup; non-material. |
| `BookmarksRepository.kt:86, 284-285, 294` (pre-existing) | Hilt `@Provides`, `load()`'s cancellation / `DateTimeException` arms | Not changed by the task. |
| `BookmarkReader.kt:147` (1/2), `:190` (3/6) | retry button disabled while a failed reader is loading; a non-URL link annotation | UI states that the view model does not produce together (failure is cleared on retry); non-URL `LinkAnnotation` is not produced by `fromHtml` for HTML anchor links. Non-material. |
| `BookmarkAdder.kt:49-52` (1/2 each) | `rememberSaveable` restore branch | Compose state restoration internals; non-material. |
| `BookmarksScreen.kt:88, 97, 381, 492-498` and listed partial branches (pre-existing lines) | Coil image loader setup, favicon rendering, pre-existing route/snackbar code | Not task code; every changed line in this file is covered (101/101). |

## Unable to test

- Running on a real device/emulator (instrumented tests) and against a real server: not done; the criteria name JVM unit/Compose tests only. The server contracts were checked against `api/openapi.yaml` instead. No follow-up needed for this task.
- `CancellationException` rethrow in `BookmarksRepository.link()`: see coverage table; no follow-up needed.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.

The Android checks (ktlint, all 3929 unit tests, lintDebug) pass. The full `sh scripts/check.sh` couldn't finish in this shell because its web step needs a newer Node.js. The parent still has to run `.taskcheck` with the correct runtime.
