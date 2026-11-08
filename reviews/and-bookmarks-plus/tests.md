# Test Report

Date: 2026-11-08

Task: and-bookmarks-plus - Android bookmarks: add, read-later toggle, reader, to inbox

Scope: `git diff 3e8f6408c2b032d9501fb91c336923da20fdb185..cc64e32d4bd16e73afd31b9fedd34eda39c720b6` (base = `git merge-base HEAD origin/main`, given by the parent; HEAD = `cc64e32d4`, "fix review P3 CR-1, CR-2"). Task commits: `83196000`, `8c296159`, `cc46ef25`, `4b46f381`, `90e31e3c`, `8a553ef7`, `d5db8896`, `cc64e32d`. Every changed file is inside the task's `touches`, plus its own `reviews/` files. Branch `main`. This run's uncommitted changes are test files only (listed below). There are no uncommitted production changes.

Requirements:
- `tasks/and-bookmarks-plus.md` acceptance criteria #1-#6
- `SPEC.md` §8.4 (bookmarks, read later, snapshot), §4.5 (a capture from a bookmark makes an Inbox item linked to its origin)
- `docs/ARCHITECTURE.md` "Coverage" and conventions (EN + DE strings)
- `reviews/and-bookmarks-plus/review.md` / `fixes.md` (P3 CR-1, CR-2: what the latest commit changed)

## Test files changed

In this run (uncommitted):
- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksAddReaderViewModelTest.kt` (2 tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksRepositoryTest.kt` (1 test added)

Reviewed and left unchanged (committed by the task): `BookmarksAddReaderScreenTest.kt`, `BookmarksModelsTest.kt`, `BookmarksRouteTest.kt`, `BookmarksStringsTest.kt`, and the rest of the two files above.

## Tests added or updated

Count: 3 added. No existing test was weakened, removed or skipped.

The latest commit (`cc64e32d`) changed two things. `BookmarksRepository.toInbox` renamed a local variable, so the link uses the id the server returned for the task. `BookmarksViewModel` added `snapshotSaves`, so a reader reopened while a save is running shows `saving`, and the `finally` clears it. That commit already added tests for a reopen during a save that succeeds and one that ends Ok(null). This run covers the cases those tests leave open:

- `BookmarksAddReaderViewModelTest`
  - `a reader reopened while its copy is saved shows the new copy, not the older version its own load answers late`: the reopened reader's own load is still pending (`loading` and `saving` both true) when the save finishes. The reader then shows the saved copy. When the cancelled, older `get` answers later, it does not replace the saved version in the reader or in the list, and `busy` ends up empty.
  - `a reader reopened while its copy is saved retries its failed load only after the save ended`: the reopened reader's load is offline while the save runs. It shows `saving = true` and the `OFFLINE` failure, and retry sends no request. When the save ends Offline, the snackbar says so, `saving` is cleared, the failure stays, and retry then loads again.
- `BookmarksRepositoryTest`
  - `to Inbox links the task the server answered with`: pins the CR-1 code path. The create sends the client id. The link names the task id in the server's answer.

## Requirement coverage

Focused command: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*'`. Result: 8 suites, 161 tests, 0 failures.
Full command: `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport`. Result: 360 suites, 4387 tests, 0 failures, 0 skipped; ktlint and lint passed.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| #1 "Add bookmark" in the screen (URL, optional title/tags, read later) | VM (`BookmarksAddReaderViewModelTest`, section "Add bookmark", 15 tests): read-later preset per view, invalid address and invalid tags refused locally, save closes the dialog and joins the list in order (tag filter, duplicate id, before/during load), failures keep input + reason (400 field mapping, offline reload), retry clears the reason. Repo: `a new bookmark sends the trimmed address, title, parsed tags and read later`, `a new bookmark without title and tags leaves them out for the server to fill`, `a refused new bookmark is a failure with its status, no connection is offline`, `a refused new bookmark names the invalid fields of the Problem Details`, `only a 400 reports invalid fields, and errors without a field name none`. Screen (`BookmarksAddReaderScreenTest`): add button, dialog input/save/cancel/saving/failure. Route: `a bookmark added in the dialog is saved and shown in the queue`. Models: web address validation | focused + full | Pass |
| #2 Toggle read-later on an existing bookmark | VM: `taking a bookmark out of read later drops it from the queue, putting one in keeps it in all bookmarks`, `a failed read-later toggle keeps the bookmark and says why, a conflict reloads it`, `changes in the reader use its version, also after the bookmark left the queue`. Repo: `toggling read later sends only the read-later flag with If-Match`. Screen: row menu and reader buttons, actions disabled while a change runs | focused + full | Pass |
| #3 Snapshot reader view (server snapshot) | VM, reader section (25 tests): load latest + copy, no copy, load/copy failures + retry, gone bookmark, ordering (closed while loading, other bookmark's late copy), save copy (stores and shows, never together with other changes, leaving the reader during a save, reopened during a save: success / Ok(null) / **late older load (new)** / **offline load + retry blocked until the save ends (new)**, save refused / outdated / gone / unreadable). Repo: preview + truncation, gone/offline copy, save with If-Match, 502 as unreadable. Screen: reader replaces list, back closes it, progress, failure + retry, unreadable. Route: relative links resolved, script links ignored | focused + full | Pass |
| #4 "To Inbox task" action | VM: `to Inbox says it was added and whether it was linked, each time as a new note`, `a second to Inbox while the first runs makes no second task`, `to Inbox retried after a lost answer sends the same task id, a later one a new UUIDv7`, `to Inbox keeps the task id only while the answer is unknown, any server answer ends it`, `a failed to Inbox says why`. Repo: `to Inbox creates an Inbox task with the title and address and links the bookmark as its origin`, **`to Inbox links the task the server answered with` (new)**, `a repeated Inbox task the server already has is ok and linked`, title cut to 500, link failure/offline reported unlinked, refused/unreadable task makes no link. Screen: snackbar confirmations | focused + full | Pass |
| #5 New strings in English and German | `BookmarksStringsTest` (2 tests: key and placeholder parity, presence of the new keys); Screen (`de` locale): German texts of the dialog, reader, row menu, Inbox snackbars; `lintDebug` (`MissingTranslation`) | focused + full (incl. `lintDebug`) | Pass |
| #6 Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | all of the above | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport`; `sh scripts/check.sh` (repo root) | Pass (both) |

Instrumented tests on a device or emulator were not run. The criteria name only JVM unit/Compose (Robolectric) tests.

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*' --console=plain -q`

Result:

Pass: 8 suites, 161 tests, 0 failures, 0 errors, 0 skipped. Per suite: `BookmarksAddReaderViewModelTest` 50, `BookmarksAddReaderScreenTest` 18, `BookmarksRepositoryTest` 23, `BookmarksViewModelTest` 30, `BookmarksScreenTest` 21, `BookmarksModelsTest` 11, `BookmarksRouteTest` 6, `BookmarksStringsTest` 2. The first attempt failed in ktlint (`chain-method-continuation` in the new repository test). The test was reformatted and the rerun passed.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --console=plain -q`

Result:

Pass: no failure output. Full `testDebugUnitTest`: 360 suites, 4387 tests, 0 failures, 0 errors, 0 skipped (counted from `app/build/test-results/testDebugUnitTest/*.xml`). `ktlintCheck` and `lintDebug` passed.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` at the repo root, run after the new tests were added: tfp, server, web (Node v24.21.0 in this shell; the bundle-budget warning is only a warning) and android ktlint + unit tests + lint all ran, and it ended with "== all checks passed". The Node v20.9.0 problem the task notes mention did not occur in this run.

## Bugs found

None. No test exposed behavior that violates the acceptance criteria or SPEC.md.

Observation, not a defect against a stated requirement: suppose a reader is reopened during a save, its own load is still running, and the save then ends Ok(null) (`UNREADABLE`). If that load later gets a copy, `loadSnapshot` clears `failure`, so the "page could not be read" message disappears in favour of the earlier copy. This is a rare ordering, and the message is informational. No test asserts either way.

## Coverage

Status: verified. Fresh run, exit 0 (BUILD SUCCESSFUL, no failure output); report regenerated during this run.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately. Method: take the executable lines from the JaCoCo XML per-line entries (source-mapped). Intersect them with the added or modified lines of `git diff -U0 3e8f6408c..HEAD`, per file. A line counts as covered when `ci > 0`. Result: 521/525 (99.2%). Changed-branch coverage was not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-bookmarks-plus`. Base `3e8f6408c2b032d9501fb91c336923da20fdb185` (`git merge-base HEAD origin/main`, given by the parent). Measured HEAD `cc64e32d4bd16e73afd31b9fedd34eda39c720b6`. Task commits come from `git log 3e8f6408c..HEAD` (all titled `and-bookmarks-plus:`); the file scope matches the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `cc64e32d4` plus 2 uncommitted test files. sha256: `BookmarksAddReaderViewModelTest.kt` 4414a2d953bed41ea600ccbd953d06658cb5fa71c1342b0a1c21dca40509b235, `BookmarksRepositoryTest.kt` 5d21e90ad09ee6ec3df019427be2a8b47b353d78629372b92b69fe4f9563a5e2. Production file hashes: `BookmarksViewModel.kt` d3dba635..., `BookmarksRepository.kt` afd3fc3e..., `BookmarksScreen.kt` 4c60bc6c..., `BookmarkReader.kt` 1b21f605..., `BookmarkAdder.kt` 5fe2e61e..., `BookmarksModels.kt` 3501d344.... All hashes were identical before and after the coverage run and the `.taskcheck` run.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/{BookmarkAdder,BookmarkReader,BookmarksModels,BookmarksRepository,BookmarksScreen,BookmarksViewModel}.kt`. All 6 appear in the report; none is missing. Excluded: `android/app/src/main/res/values/strings_bookmarks.xml` and `values-de/strings_bookmarks.xml`, because resources cannot be instrumented. They are covered by `BookmarksStringsTest` and `lintDebug`. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (project Gradle wrapper). Command `./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --console=plain -q`, run in `android/`, with the full `testDebugUnitTest` (no filter).
- Run start/completion time and exit status: started 2026-11-08T18:00:01Z. Report written 2026-11-08T18:04:50Z. BUILD SUCCESSFUL, no failure output.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 966ce0266c38402ced869829d70a2efebd1c701634533e70a16701d6cb1f8c00. HTML at `.../index.html`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Before the run the report had mtime 17:23:34Z; this run rewrote it at 18:04:50Z. Input hashes were unchanged.
- Final run after latest test additions and production fixes: yes. It ran on HEAD `cc64e32d4` (which includes the P3 fixes) plus this run's tests. If production code changes later, these numbers are stale.

Values below use covered/total (percentage). JaCoCo has no statement counter, so the Statements column shows the INSTRUCTION counter, and the Functions column shows METHOD.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarkAdder.kt` | 1200/1204 (99.7%) | 34/38 (89.5%) | 30/30 (100.0%) | 72/72 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarkReader.kt` | 1275/1278 (99.8%) | 46/51 (90.2%) | 18/18 (100.0%) | 102/102 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksModels.kt` | 681/689 (98.8%) | 60/62 (96.8%) | 42/44 (95.5%) | 81/81 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksRepository.kt` | 1058/1146 (92.3%) | 54/60 (90.0%) | 44/46 (95.7%) | 128/134 (95.5%) |
| `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksScreen.kt` | 5691/5921 (96.1%) | 147/172 (85.5%) | 117/147 (79.6%) | 428/438 (97.7%) |
| `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModel.kt` | 3328/3376 (98.6%) | 226/240 (94.2%) | 113/119 (95.0%) | 316/321 (98.4%) |
| **Aggregate (summed counters over all 6 files; 2 resource files excluded as above; none unmeasured)** | 13233/13614 (97.2%) | 567/623 (91.0%) | 364/404 (90.1%) | 1127/1148 (98.2%) |

Changed-line coverage per file: `BookmarkAdder.kt` 72/72, `BookmarkReader.kt` 102/102, `BookmarksModels.kt` 22/22, `BookmarksRepository.kt` 63/65, `BookmarksScreen.kt` 103/103, `BookmarksViewModel.kt` 159/161. Total: 521/525 (99.2%). Every line changed by `cc64e32d` (the `snapshotSaves` add/remove, `openReader`'s `saving`, the `finally`'s `updateReader`, `createdId`) is covered.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `BookmarksViewModel.kt:50, 55` (changed lines) | default `id = 0` parameter of `AddedToInbox` / `InboxOffline` | The view model always passes an id. These are only default-argument accessors, so the gap is non-material. |
| `BookmarksViewModel.kt:59, 144, 169` (pre-existing) | `NoBrowser.id`, the Hilt `@Inject` constructor, the closing line of the init collector | Not task code, or DI glue. |
| `BookmarksViewModel.kt:315, 355` (1/2) | `readerJob?.cancel()` with a null job | The job is always set once a reader was opened. Non-material null-safety branch. |
| `BookmarksViewModel.kt:362` (4/6), `378` (3/4), `495-498`, `553`, `564`, `579` (partial) | reader-close and failure-mapping fallbacks, `run()`'s lookup of the latest version | All main outcomes are tested: 404 closes the reader, other failures, conflicts reload, updates are guarded by bookmark id, a reader-only bookmark. The remaining branches are null-safety or condition combinations. |
| `BookmarksRepository.kt:251-252` (changed) | `CancellationException` rethrow in `link()` | Defensive cancellation path. It cannot be reached deterministically with MockWebServer. Non-material. |
| `BookmarksRepository.kt:90, 298-299, 308` and partials `138, 280, 287, 335, 349` (pre-existing) | Hilt `@Provides`, `load()`'s cancellation / `DateTimeException` arms, paging/body-null guards | Not changed by the task. |
| `BookmarkReader.kt:66` (1/2), `150` (1/2), `195` (3/6) | default parameter; retry button disabled while a failed reader is loading; a non-URL link annotation | The view model never produces a failure while loading, because retry clears it. `fromHtml` produces only URL annotations for anchors. Non-material. |
| `BookmarkAdder.kt:49-52` (1/2 each) | `rememberSaveable` restore branch | Compose state-restoration internals. Non-material. |
| `BookmarksModels.kt:100` (4/6) | display-title fallback | Elvis combinations. The main fallback is tested. Non-material. |
| `BookmarksScreen.kt:88, 97, 381, 492-498` and partial branches (pre-existing lines) | Coil image loader, favicon, pre-existing route/snackbar code | Not task code. Every changed line in this file is covered (103/103). |

## Unable to test

- Instrumented tests on a real device or emulator, and runs against a real server, were not done. The criteria name only JVM unit/Compose tests. No follow-up is needed for this task.
- The `CancellationException` rethrow in `BookmarksRepository.link()`: see the coverage table. No follow-up is needed.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.

This run added 3 tests for the latest P3 fixes (CR-2: a reader reopened during a save; CR-1: the link uses the task id the server answered with). `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` (4387 tests) and the full `sh scripts/check.sh` both pass on HEAD `cc64e32d4` plus these tests.
