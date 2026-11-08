# Test Report

Date: 2026-11-08

Task: and-mail-attachments - Android mail attachments: open, download, preview, save to Files

Scope: `git diff ee9f70ad150555013844607818b0ca328ec39d10..7fe87f8e2cc1b80b5c1bd7ec357b11020d8cb3e2` (base ee9f70ad, the claim commit; HEAD 7fe87f8e is the merge of `origin/wip/and-mail-attachments`). Task commits: 6e56f8ec (implementation), b81c2614, 90380b84 (tests, review), fa45dcdb (fixes CR-1 to CR-5), 58ef7b0b (more tests). The parent supplied the base and commit list. The diff contains only the paths in the task's `touches` plus `reviews/and-mail-attachments/`. Uncommitted at measurement: the two test files changed by this report (see Coverage, snapshot).

Requirements:
- `tasks/and-mail-attachments.md` acceptance criteria and recorded assumptions (preview without new library; Save to Files stores in /Attachments/Mail, then moves to the picked folder)
- `reviews/and-mail-attachments/fixes.md` (CR-1 to CR-5 fixed behavior)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailAttachmentsScreenTest.kt`

## Tests added or updated

Count: 2 (1 new test, 1 existing test extended with 2 assertions)

- New: `MailReadRepositoryTest.an attachment without a content type, or with a blank one, is listed without a type`. It covers the task's change to `MailReadRepository.kt:129`: a missing or blank `contentType` maps to `null`, so the icon, preview and open fall back to the file name.
- Extended: `MailAttachmentsScreenTest.the type icon follows the content type, then the file extension` now also asserts `video/mp4` gives the video icon and `application/x-7z-compressed` gives the archive icon. These are the content-type halves of `MailAttachments.kt:244-245` that were not covered before.

The existing tests in the task (about 120 in `MailAttachment*Test.kt`, plus updates in `MailRead*Test.kt`) already cover every criterion and every fix CR-1 to CR-5. I checked each one against the final code, see the table. I added nothing that would only duplicate them.

## Requirement coverage

Command for all rows: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'` (280 tests, 0 failures). The same tests then ran again in the full run used for coverage.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Attachment list with name, size and type icon | `MailAttachmentsScreenTest`: `each attachment is a row with its name and size`, `a mail without attachments has no attachment list`, `the type icon follows the content type, then the file extension`; `MailReadRepositoryTest`: `a message is read with ... listed attachments`, `an attachment without a content type, or with a blank one, is listed without a type` | see above | Pass |
| 2a. Open: download to cache, open with another app via FileProvider | `MailAttachmentsRouteTest`: `tapping an attachment downloads it and opens it in another app with a read grant`, `a generic content type is opened with the type its extension stands for`, `an attachment without a type and with an unknown extension is opened as generic data`, `an attachment no app opens says so`; `MailAttachmentsViewModelTest`: `open downloads into the cache file ...`, `a failed open says so and removes the partial cache file`, `an open without a connection is reported as offline`, `a cache file that cannot be created fails the open`, `showing another mail during an open removes the partial cache file and opens nothing` | see above | Pass |
| 2b. Save to device (system create-document) | `MailAttachmentsRouteTest`: `save to device asks where, writes the attachment there and says so`, `a failed save to device deletes the document the save-as picker created`, `a cancelled save-as picker downloads nothing`, `a pending save of an attachment without size or type survives a state restore`, `a save still arrives when the screen state was restored while the save-as picker was open`; `MailAttachmentsViewModelTest`: `save to device writes ...`, `a failed save to device discards the chosen document`, `a save to device without a connection is offline and discarded`; `MailAttachmentRepositoryTest` download tests (streaming, empty, unwritable target, full disk, refused, offline, connection lost) | see above | Pass |
| 3. In-app preview for images and PDFs | `MailAttachmentsScreenTest`: `only images and PDFs offer a preview`, `an image preview shows the image as its only page`, `an image larger than the preview limit is still shown, scaled down`, `an empty image file cannot be shown`, `a preview of a file that cannot be rendered says so, and can be closed or opened elsewhere`; `MailAttachmentsViewModelTest`: `a PDF preview downloads it and shows it in the app`, `an image preview is shown as an image`, `an attachment the app cannot show is not downloaded for a preview`, `a failed preview says so and shows nothing`, `preview kinds follow ...`, `type parameters, case and padding are ignored ...`; `MailAttachmentsRouteTest.the preview button downloads the attachment and opens the in-app preview` | see above | Pass. Exception: rendering the pages of a valid PDF is Not testable on the JVM (see Unable to test) |
| 4. "Save to Files" with folder picker | `MailAttachmentsRouteTest.save to Files picks a folder and stores the attachment there`; `MailAttachmentsViewModelTest` picker and save tests (root, walk into/back, listing fails, slow listing of a folder already left, close stops listing, root saves to default folder, picked folder path, failed move, too large 413, offline, refused, pressed twice, busy); `MailAttachmentRepositoryTest` save-to-files tests (no folder, move with version If-Match, same folder no move, refused move, move offline, 413, offline, unreadable answer); `MailAttachmentsScreenTest` picker tests | see above | Pass |
| 5. New strings in English and German | `MailAttachmentsGermanTest` (12 tests: list, preview button, menu, every notice, picker at root/inside folder/failed, dismiss), English texts in `MailAttachmentsScreenTest`. Every `values/` key also has a `values-de/` entry, which `lintDebug` (`MissingTranslation`) enforces | see above | Pass. I did not run `lintDebug` |
| 6. Unit/Compose tests cover the new behavior; `ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | all of the above | `ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'`; full `testDebugUnitTest` through `createDebugUnitTestCoverageReport` | ktlintCheck: Pass. Full Android unit suite: Pass (3548 tests, 0 failures). `lintDebug`: Not run (not in the scope the parent gave me; the parent runs it). `sh scripts/check.sh`: Not run (see .taskcheck) |
| CR-1 cancelled download stops streaming | `MailAttachmentRepositoryTest.a cancelled download stops streaming partway and closes target and body` | see above | Pass |
| CR-2 lazy per-page PDF rendering | none can run: Robolectric has no working `PdfRenderer` | n/a | Not testable on the JVM (see Unable to test). The unreadable-PDF fallback is covered |
| CR-3 cache file per mail | `MailAttachmentsViewModelTest.the cache file is chosen for the mail, so two mails never share one` | see above | Pass |
| CR-4 save answer before the mail reloads | `MailAttachmentsViewModelTest`: `a save answer that arrives before its mail is shown waits for that mail, then saves`, `a waiting save is discarded and says so when another mail is shown`, `a save for another mail than the one shown is discarded and says so`, `a second save answer before its mail is shown replaces the first ...`, `a save still waiting for its mail when the screen goes away discards its document`, `a save to device without a mail discards the document ...`; route test `a save still arrives when the screen state was restored ...` | see above | Pass |
| CR-5 open from the preview without downloading again | `MailAttachmentsViewModelTest`: `open from the preview hands over the file already downloaded, without downloading again`, `open from the preview without a preview does nothing`; `MailAttachmentsRouteTest.open from the preview hands the previewed file to another app without downloading it again`; screen test expects `open previewed` | see above | Pass |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'`

Result:

Pass. BUILD SUCCESSFUL in 52s. 280 tests, 0 failures, 0 errors, 0 skipped, counted from `android/app/build/test-results/testDebugUnitTest/TEST-ch.sonensei.cockpit.mail.read.*.xml`. Both new or changed tests appear in the results.

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (runs the full `testDebugUnitTest`)

Result:

Pass. BUILD SUCCESSFUL in 3m 24s. 3548 Android unit tests, 0 failures, 0 errors, 0 skipped. Server and web suites: Not run. The task does not touch them.

## .taskcheck

Result:

Not run. `sh scripts/check.sh` lints and tests server, web and Android. The task notes say an earlier run did not finish within 10 min, and the parent worker owns this step before `done`. The Android part of the check (ktlint plus unit tests) passed above. `lintDebug` was not run by me.

## Bugs found

None. No test exposed a production defect.

## Coverage

Status: verified (fresh run, exit 0). The PDF page rendering is partially unmeasurable on the JVM (see gaps).

Metric: whole-file coverage of task-changed production files. It includes older code in `MailReadScreen.kt` and `MailReadRepository.kt` and counts hits from tests that existed before the task.
Changed-line coverage: not measured. JaCoCo XML gives per-line counters, but I did not intersect them with the diff. For the two pre-existing files, I checked by hand where uncovered lines fall in the task's hunks (see the gap table).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-mail-attachments`. Base ee9f70ad150555013844607818b0ca328ec39d10. HEAD 7fe87f8e2cc1b80b5c1bd7ec357b11020d8cb3e2. Commits 6e56f8ec, b81c2614, 90380b84, fa45dcdb, 58ef7b0b, as given by the parent and matching `git log ee9f70ad..HEAD`. Production files come from `git diff --name-only ee9f70ad..HEAD -- android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 7fe87f8e plus the uncommitted diff of the two test files listed above. `git diff -- android | sha256sum` = `042bad41bc8aeb70c6676b499e5c09b0235a0dfbf6ba3cefdb98657fdf38028c`. No production file is modified in the working tree.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/{MailAttachmentRepository.kt, MailAttachments.kt, MailAttachmentsViewModel.kt, MailReadRepository.kt, MailReadScreen.kt}`. Excluded: `res/values/strings_mail.xml` and `res/values-de/strings_mail.xml`, which are resources and not executable (their texts are checked by the English/German tests). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` and the Gradle `jacoco` plugin, with the version the build uses by default (not pinned in `android/app/build.gradle.kts`). Command `./gradlew createDebugUnitTestCoverageReport`, run from `android/`, on all debug unit tests.
- Run start/completion time and exit status: completed 2026-11-08 15:20:39 +0100 (report mtime), 3m 24s, BUILD SUCCESSFUL (exit 0). A baseline run before my test changes completed at 15:15:21 +0100 (report sha256 `26c7b0ca...`). Its numbers are superseded by the run below.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 `f8f32b50942f52d31a2e510b6e742b147c9defc671886f3db00c71f19db1bfab`. HTML: `.../index.html`. Both are Git-ignored.
- Evidence outputs are fresh and inputs stayed unchanged: Gradle reran `testDebugUnitTest` because the test inputs changed. The report was rewritten (new mtime and hash, and the counters changed in exactly the two places my tests target). No source or test file changed during the run.
- Final run after latest test additions and production fixes: yes. The fixes (fa45dcdb) and the last test commit (58ef7b0b) are in HEAD, and my test changes were in the snapshot.

Values below use covered/total (percentage).

| Changed file | Instructions (statements) | Branches | Methods (functions) | Lines |
|---|---:|---:|---:|---:|
| `MailAttachmentRepository.kt` | 375/378 (99.2%) | 16/16 (100.0%) | 16/17 (94.1%) | 46/46 (100.0%) |
| `MailAttachments.kt` | 3701/4450 (83.2%) | 123/164 (75.0%) | 77/99 (77.8%) | 309/372 (83.1%) |
| `MailAttachmentsViewModel.kt` | 1871/1928 (97.0%) | 126/138 (91.3%) | 87/88 (98.9%) | 205/205 (100.0%) |
| `MailReadRepository.kt` (pre-existing, 2 hunks changed) | 910/919 (99.0%) | 81/82 (98.8%) | 40/40 (100.0%) | 79/79 (100.0%) |
| `MailReadScreen.kt` (pre-existing, about 100 lines changed) | 5299/5441 (97.4%) | 254/289 (87.9%) | 115/128 (89.8%) | 394/399 (98.7%) |
| Aggregate of the 5 files (summed counters; strings XML excluded as non-executable; no unmeasured files) | 12156/13116 (92.7%) | 600/689 (87.1%) | 335/372 (90.1%) | 1033/1101 (93.8%) |

Before my additions, `MailReadRepository.kt` branches were 78/82 and `MailAttachments.kt` branches 121/164.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailAttachments.kt:368-369, 377-408, 411-412, 469-491, 522-533` (PdfPage, PdfPageImage, `PdfDocument.render`, the success path of `PdfDocument.open`) | Criterion 3 (PDF preview); CR-2 lazy per-page rendering, bitmap recycling, close while rendering, OOM per page | Left uncovered: Robolectric has no working `PdfRenderer` (`NoSuchMethodError` in `ParcelFileDescriptor.detachFd`, recorded in fixes.md), so a valid PDF never opens on the JVM. The unreadable-PDF fallback is covered. Needs an instrumented or manual device check. **Significant gap.** |
| `MailAttachments.kt:500, 504-505, 545-546` (branches of `closeIfIdle`, `closeQuietly`) | Closing a document that is rendering, or one that never opened | Same limitation as above: only `UNREADABLE` (null renderer) is reachable. |
| `MailAttachments.kt:290, 299, 334` and when-branches 283/285/331 | none: Kotlin's synthetic `else` for exhaustive `when` on enums or sealed types | Not reachable; no test. |
| `MailAttachments.kt:165, 189, 209, 357, 364, 380-399, 446, 450` (remaining branches) | Compose-generated lambda and remember branches; `decodeImage` bounds of 0 (a 0-byte file is covered) and a null decode | Non-material; the image decode paths that matter (valid, oversized, empty) are covered. |
| `MailAttachmentsViewModel.kt:141` (`takeIf { id != null }` false) | A waiting save when `show(null)` is called | Not reachable: a waiting save only exists while no mail is shown, and `show(null)` then returns early (`id == messageId`). |
| `MailAttachmentsViewModel.kt:267, 276, 279, 305, 313, 344, 346, 248` (single branches) | Null-job and no-picker guards; `saveToFiles` without a mail (the picker cannot exist without one); a listing that answers after the picker closed (`closePicker` cancels it first); null-safe `cache?.delete()` when the target lambda was never called; NonCancellable cleanup variants | Defensive guards; the observable behaviors (picker closed, busy, cancelled downloads, discards) are covered by existing tests. No test added. |
| `MailReadScreen.kt:626` (closing brace of the `opens` collector in `rememberAttachmentCallbacks`) | none: the coroutine-suspend continuation after an endless `collect` | Not reachable. |
| `MailReadScreen.kt:634, 680, 686` (branches) | `uri != null && pending == null` (a picker answer with no pending save); the Saver for `null` and for a mail-less save; restoring the older 4-field saved state | The cancelled picker (`uri == null`) and restoring the 5-field state are covered by route tests. The 4-field restore (CR-4 backward compatibility) cannot be fed in without saved state from the earlier build: `PendingSaveSaver` is private. Low risk, not tested. |
| `MailReadScreen.kt:407, 412, 416, 437` and the other branches outside the task's hunks (90, 239-249, 310-601) | Older notices, menus and navigation, not part of this task | Out of task scope (the task changed only lines 101, 128-133, 243-260, 485-505 and 612-699). |
| `MailReadRepository.kt:135` | Parsing `folderId`, not part of this task | Out of task scope. |
| `MailAttachmentRepository.kt` (1 method, 3 instructions) | `MailAttachmentModule.mailAttachmentApi` Hilt provider | Non-material DI glue. |

## Unable to test

- **Rendering a valid PDF in the in-app preview (criterion 3 for PDFs, CR-2).** Robolectric's `PdfRenderer` does not work, so lazy per-page rendering, recycling, the per-page OOM note and closing during a render cannot run in a JVM unit test. Follow-up: an instrumented test or a manual check on a device or emulator. The parent should record this with `tasks.py verify --skip` or as an assumption for that part of criterion 3, unless it runs a device check.
- **Opening in a real third-party app and the real system create-document UI.** These are simulated with Robolectric shadows (intent, FileProvider URI, read grant, `receiveResult`). That is reasonable unit-level evidence, but not a device run.
- **`lintDebug` and `sh scripts/check.sh`** (part of criterion 6) were not run by me. The parent must run them.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The existing tests already covered every acceptance criterion and fixes CR-1, CR-3, CR-4 and CR-5 on the final code. I added one repository mapping test and two icon assertions for task branches that were not covered. The remaining significant gap is the rendering of valid PDF pages (CR-2), which cannot be tested on the JVM and needs a device or manual check.
