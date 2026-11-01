# Test Report

Date: 2026-11-01

Task: and-mail-folder-manage - Android mail: create, rename and delete folders

Scope: `git diff b190d2a7570a77756217e46994ecdad61aa0ffde..43b25565fa59408be58d06af6a722dd68c7c557d -- android` (task commits c20609e88, 0b44533c5, b3f94298a, a1e658b9c, 43b25565f; HEAD = 43b25565f, branch main). No uncommitted changes under `android/` (the only working-tree change is `reviews/and-mail-folder-manage/review.md`).

Requirements:
- tasks/and-mail-folder-manage.md (6 acceptance criteria and the 2 recorded assumptions)
- SPEC.md §4.2 (ETag/If-Match), docs/ARCHITECTURE.md "Coverage", AGENTS.md project notes
- reviews/and-mail-folder-manage/review.md and reviews/and-mail-folder-manage/fixes.md
- Parent instruction for this session: the code is already reviewed, so do not add or change test files. Only inspect the existing tests against the criteria and record coverage.

## Test files changed

This session changed no test files, as the parent instructed. The task's test files at HEAD, all under `android/app/src/test/java/ch/sonensei/cockpit/mail/list/`, are:

- `MailFolderManageRepositoryTest.kt` (implementer, extended by the earlier test-writer session and the fixer)
- `MailFolderManageViewModelTest.kt` (implementer, extended by the fixer)
- `MailFolderManageScreenTest.kt` (implementer)
- `MailFolderManageModelsTest.kt` (earlier test-writer session, commit a1e658b9c)
- `MailFolderManageViewModelEdgeCasesTest.kt` (earlier test-writer session; one table row changed by the fixer)
- `MailFolderManageScreenEdgeCasesTest.kt` (earlier test-writer session)
- `MailFolderManageGermanTest.kt` (earlier test-writer session)
- `MailFolderManageReloadRaceTest.kt` (earlier test-writer session; reproduced TW-1)
- `MailRepositoryTest.kt` (constructor change only, commit c20609e88)

## Tests added or updated

Count: 0 in this session. The task has 71 folder-management tests in total: 32 by the implementer (0b44533c5), 36 by the earlier test-writer session (a1e658b9c), and 3 by the fixer (43b25565f). The fixer also changed two error-table rows from 404 to 403 because the 404 behavior changed (see fixes.md). All 71 pass at HEAD.

Per class (JUnit XML of the parent's run at HEAD, 2026-11-01T07:09Z): Repository 9, ViewModel 19, ViewModelEdgeCases 9, Screen 9, ScreenEdgeCases 6, Models 12, German 5, ReloadRace 2. All 0 failures, 0 errors, 0 skipped.

## Requirement coverage

Command C1 (run by the parent at HEAD 43b25565f): `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` -> BUILD SUCCESSFUL, 2246 tests, 0 failures, 0 errors, 0 skipped (log `.tmp/android-check.log`; totals confirmed from `android/app/build/test-results/testDebugUnitTest/TEST-*.xml`). The Compose UI tests run under Robolectric inside `testDebugUnitTest`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: the picker offers "New folder" for each account, and "New subfolder", "Rename" and "Delete" for each folder | Screen: `New folder per account creates a folder that shows in the picker at once`, `New subfolder creates the folder inside the chosen one`; ScreenEdgeCases: `a tap on a folder row still opens the folder, the actions sit beside it` | C1 | Pass |
| AC1: no Rename or Delete for INBOX, no Delete for folders with a role | Screen: `INBOX offers only a new subfolder, a folder with a role no delete, others everything`; ViewModel: `INBOX cannot be renamed or deleted, folders with a role cannot be deleted`; Models: `only INBOX cannot be renamed, only folders without a role can be deleted` | C1 | Pass |
| AC2: New folder and Rename open a dialog with the name (Rename starts with the current name) | Screen: `rename starts with the current name, a taken name is shown in the dialog which stays open`, `New subfolder creates ...` (title "New folder in Projects") | C1 | Pass |
| AC2: server errors (name taken, invalid name, unreachable) show in the dialog, which stays open | Screen: `rename starts with the current name, a taken name ...`, `invalid names and an unreachable server are shown in the dialog`; ViewModel: `server errors stay in the open dialog`, `a 404 when creating stays in the open dialog`; ViewModelEdgeCases: `create and delete map server errors to their dialog messages`, `a retry after an error clears the error while the server works on it`; German: `a taken name is explained in German`, `any other failure asks in German to try again` | C1 | Pass |
| AC2: blank name and double submit are not sent | ViewModel: `an empty name is not sent`, `a second submit while busy is ignored`; ViewModelEdgeCases: `a renamed name is trimmed, an empty one is not sent`, `submit without an open dialog does nothing`; ScreenEdgeCases: `Done on the keyboard with an empty name sends nothing`, `while the server works the name and the confirm button are disabled, then the dialog closes` | C1 | Pass |
| AC2: after success, the folder list shows the change at once | ViewModel: `a new folder appears in the list at once and the dialog closes`, `a new subfolder is created in its parent`, `a renamed folder shows its new name at once, then the list is reloaded for its subfolders`, `a success after the dialog was cancelled still shows in the list`; Screen: the two create tests (the reload is made to fail, so only the immediate change can make the folder show); Models: the `withFolder` and `withoutFolder` tests; ReloadRace: `a new folder stays in the list when a refresh that started before it finishes later`, `a deleted folder does not come back when a refresh that started before it finishes later` | C1 | Pass |
| AC3: Delete asks for confirmation and says that messages and subfolders are deleted on the mail server too | Screen: `delete asks for confirmation, says messages and subfolders go too, and switches to the unified inbox`, `cancelling the delete keeps the folder`; ScreenEdgeCases: `the delete confirm button is disabled while the folder is deleted`, `a folder that cannot be deleted says so in the dialog, which stays open`; German: `the delete dialog warns in German that messages and subfolders go on the mail server too` | C1 | Pass |
| AC3: deleting the folder on screen switches to the unified Inbox | Screen: `delete asks for confirmation ... switches to the unified inbox`; ViewModel: `deleting the folder shown switches to the unified inbox`, `deleting the parent of the folder shown also switches to the unified inbox`, `deleting another folder keeps the folder shown`, `deleting removes the folder with its subfolders`; ViewModelEdgeCases: `a failed delete of the folder shown keeps it shown`, `deleting a folder leaves the folder with the same path in another account, and the shown one there`, `renaming the folder shown keeps it shown under its new name` | C1 | Pass |
| AC4: rename and delete send the folder's ETag as If-Match, through MailApi/MailRepository and the generated client | Repository: `renaming sends only the name, with the folder's version as If-Match`, `deleting sends the folder's version as If-Match and accepts 204`, `a new subfolder is created under its parent ...`, `a top-level folder is created without a parent`, `a folder listed without a version still sends a quoted If-Match ...`, `the folder list carries each folder's version and delimiter`; Models: `the generated folder answer becomes a folder with its depth, version, delimiter and role` | C1 | Pass |
| AC4: a 412 reloads the folders and shows a short message (a 404 on rename or delete is handled the same way, after fix CR-2) | ViewModel: `a stale ETag closes the dialog, reloads the folders and says so`, `a stale ETag when renaming does the same`, `a folder already gone when renaming ...`, `a folder already gone when deleting ...`; ViewModelEdgeCases: `a stale ETag on delete keeps the folder shown and leaves the mails alone`; Screen: `a stale folder closes the dialog and shows a short message`; German: `a changed folder is reported in German`; Repository: `refusals keep their status, a mail server that cannot be reached is offline` | C1 | Pass |
| AC5: strings exist in every app locale (en, de) | Models: `every folder string exists in every locale of the app, with the same placeholders`; German tests (5) | C1 | Pass |
| AC5: TalkBack labels on the new actions | Screen: `the folder actions have TalkBack labels`; German: `the new folder and folder action buttons have German TalkBack labels` | C1 | Pass |
| AC6: ViewModel, repository and Compose UI tests; `./gradlew ktlintCheck testDebugUnitTest lintDebug` passes | all of the above | C1 | Pass |

## Focused test execution

This session ran no tests. It changed no test or production code, and the parent had already run fresh results at HEAD.

Earlier runs (from fixes.md and the parent):
- Before the fix: `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.MailFolderManage*'` -> 68 tests, 2 failures (both in `MailFolderManageReloadRaceTest`, reproducing TW-1).
- After the fix (fixer): `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.*'` -> Pass, 144 tests, 0 failures.

Result:

Pass at HEAD. The 71 folder tests are part of the full run below, with 0 failures.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` (run by the parent at HEAD 43b25565f, finished 2026-11-01 08:10 +0100)

Result:

Pass: BUILD SUCCESSFUL, 2246 tests, 0 failures, 0 errors, 0 skipped. ktlintCheck and lintDebug passed.

## .taskcheck

Result:

Not run by the test-writer. `sh scripts/check.sh` covers all parts, and the parent runs it before `tasks.py done`. Its Android part is the passing command above.

## Bugs found

### An older folder snapshot could undo a folder change (resolved)

Finding ID:

`TW-1`

Test:

`MailFolderManageReloadRaceTest` - `a new folder stays in the list when a refresh that started before it finishes later`, `a deleted folder does not come back when a refresh that started before it finishes later`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt` (`load()` and `reloadFolders()`)

Requirement:

AC2: "After success the folder list shows the change at once."

Expected behavior:

A folder create, rename or delete stays in the list. A refresh that started earlier and finishes later must not overwrite it.

Actual behavior:

Before 43b25565f, `load()` wrote its older accounts snapshot over the applied change, so the new folder disappeared or the deleted folder came back.

Severity:

Medium

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.` Status: fixed in 43b25565f (`folderGeneration` guard, see fixes.md CR-1). Both tests now pass at HEAD, and they were not changed.

No other production defects were found. Inspecting the tests found no test that fails or encodes wrong behavior.

## Coverage

Status: verified. The data is fresh JaCoCo output from the parent's run at HEAD 43b25565f.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately. Method: intersect the added lines of `git diff -U0 b190d2a75..43b25565f -- android/app/src/main/java` with the executable `line` entries of report.xml for the same source files. A line counts as covered when ci is greater than 0. Branches are counted only on executable changed lines. Result: 276/277 changed executable lines (99.6%). Branches on changed lines: 195/219 (89.0%).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-folder-manage. Base b190d2a7570a77756217e46994ecdad61aa0ffde (supplied by the parent). HEAD 43b25565fa59408be58d06af6a722dd68c7c557d. Task commits c20609e88, 0b44533c5, b3f94298a, a1e658b9c, 43b25565f (parent-supplied, confirmed with `git log` on the range). The scope matches the task's `touches` (mail/list and its tests) plus the new string files.
- Source/test snapshot identifier (including uncommitted inputs): commit 43b25565f. No uncommitted or untracked changes under `android/`.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/mail/list/{MailApi,MailListViewModel,MailModels,MailRepository,MailScreen}.kt`. Excluded: `android/app/src/main/res/values/strings_mail_folders.xml` and `android/app/src/main/res/values-de/strings_mail_folders.xml`, because they are resource XML with no bytecode (covered behaviorally by the locale and German tests). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo through AGP `enableUnitTestCoverage` and the Gradle `jacoco` plugin (report DTD "JACOCO//DTD Report 1.1"). Command `./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport`, run in `android/`, full `testDebugUnitTest` selection (2246 tests).
- Run start/completion time and exit status: JaCoCo session dell26-23cc592b, start 2026-11-01 08:07:30 +0100, dump 08:09:56 +0100. BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 6e74a50cf9bb5e1f162ef203d24a95285f3e585077c231ae8ba31c844992b909, written 08:09:59 +0100. Log `.tmp/android-check.log`, written 08:10:00 +0100.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report session falls within the parent's run, and the JUnit XML timestamps (07:09Z) match it. HEAD is still 43b25565f, and this session changed nothing under `android/`.
- Final run after latest test additions and production fixes: yes. 43b25565f contains the last production fix and the last test changes.

Values below use covered/total (percentage). JaCoCo instruction counters are listed under Statements.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailApi.kt` | 575/915 (62.8%) | N/A (0/0) | 69/95 (72.6%) | 77/103 (74.8%) |
| `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt` | 2034/2058 (98.8%) | 162/171 (94.7%) | 56/56 (100.0%) | 196/196 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailModels.kt` | 744/746 (99.7%) | 75/76 (98.7%) | 53/53 (100.0%) | 88/88 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailRepository.kt` | 519/519 (100.0%) | 30/30 (100.0%) | 22/22 (100.0%) | 70/70 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailScreen.kt` | 5741/5888 (97.5%) | 235/300 (78.3%) | 115/133 (86.5%) | 434/440 (98.6%) |
| Aggregate (summed counters, 5 files; the 2 string XML files are excluded as non-code; no unmeasured files) | 9613/10126 (94.9%) | 502/577 (87.0%) | 315/359 (87.7%) | 865/897 (96.4%) |

Changed-line coverage per file: MailApi 6/6 lines; MailListViewModel 87/87 lines, 93/99 branches; MailModels 18/18 lines, 14/14 branches; MailRepository 14/14 lines, 4/4 branches; MailScreen 151/152 lines, 84/102 branches.

The whole-file numbers include code that existed before the task (most of MailScreen.kt and MailApi.kt), and tests from before the task also contribute hits.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailListViewModel.kt:268` (`folderJob?.cancel()` with a running job) and `:273` (`generation == folderGeneration` false branch in `reloadFolders`) | Part of the TW-1 fix: two overlapping folder reloads finishing in reverse order, or a reload overtaken by a newer `load()`. If someone removed the cancel or the guard, no test would fail. | Known gap, review P3 CR-2. No test was added because the parent forbade test changes after the review. The ReloadRace tests cover the reported case (a refresh started before the edit). Follow-up: one ViewModel test with two successful edits where the first reload's `accounts()` is held by a `CompletableDeferred` and completed last. It should check that the list keeps the second reload's result. |
| `MailListViewModel.kt:215`, `:219` (failure side of `checkNotNull(edit.folder)` for RENAME/DELETE) | Defensive check. `startRenameFolder` and `startDeleteFolder` always set the folder, so it cannot be reached through the public API. | Non-material, no test. |
| `MailListViewModel.kt:340-341` (one branch each in `folderError`) | The "result is not Failed" side after the Offline check. Only `MailResult.Ok` could take it, and an Ok result never reaches `folderError`. | Non-material, unreachable. |
| `MailListViewModel.kt:52`, `:320` | Code from before the task (the `folder` getter and `mergeFirstPage`). | Outside the task scope. |
| `MailModels.kt:111` (1 of 4 branches) | Line from before the task (`path ?: name.orEmpty()` with both null). | Outside the task scope. |
| `MailScreen.kt:534`, `:581-582`, `:586`, `:595-597` (FolderMenu `pick` lambda, `rememberSaveable` key and `folder?.name` null sides, exhaustive `when` default) | Compiler-generated null and `when` default branches. A RENAME or DELETE dialog always has a folder. Every dialog kind and title is exercised (Screen and German tests). | Non-material, no test. |
| `MailScreen.kt:543` (`onDismissRequest = { open = false }` of the folder overflow menu) | Closing the overflow menu by tapping outside it. Not part of any criterion. | Non-material, no test. |
| `MailScreen.kt:138-142` (FolderCallbacks wiring in the Hilt `MailRoute`) | Wiring of the real route. The folder tests build `MailListContent` with the same callbacks, wired to a real ViewModel. | Small gap: no test drives the folder actions through `MailRoute` itself. A wrong callback here would compile only if the signatures matched, and they are method references to the tested ViewModel functions. |
| `MailScreen.kt` other missed lines and branches (98-179, 214-362, 470, 663-671) | Code from before the task (route, list rows, swipe actions, `folderLabel`). | Outside the task scope. |
| `MailApi.kt:85-202` (missed instructions) | kotlinx.serialization code generated for the DTOs (mostly DTOs from before the task; for `RenameMailFolderDto` only the deserialization side, which the app never uses). The new `renameFolder` declaration is an interface annotation without bytecode. Its path, method, If-Match header and body keys are checked against MockWebServer in the repository tests. | Non-material. All 6 changed executable lines are covered. |

## Unable to test

- Overlapping folder reloads finishing in reverse order (review P3 CR-2, `MailListViewModel.kt:268`, `:273`). This is testable with a `CompletableDeferred`, but this session was not allowed to add tests after the review. Follow-up: optional, the parent decides (see the coverage gap table).
- Review P3 CR-1: a 404 or 412 when deleting the folder on screen leaves `mailbox` on the gone folder, while the title falls back to the unified Inbox. No test covers this path, and the current code does not switch. AC3 names only a successful delete ("Deleting the folder that is currently shown switches to the unified Inbox"), which is tested, so this is not filed as a TW defect. The parent decides on it as part of review finding CR-1.
- Instrumented (on-device) Compose tests: none. The Compose UI tests run on the JVM under Robolectric, as in the rest of the Android test suite. No follow-up needed for this task.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The earlier defect TW-1 was fixed in 43b25565f and is verified by the unchanged ReloadRace tests. Every acceptance criterion has passing tests at HEAD (71 folder tests, full Android run 2246 tests, 0 failures). The task's changed executable lines are 99.6% covered (276/277). The one meaningful known gap is the missing test for two overlapping folder reloads finishing in reverse order (review P3 CR-2). It is left open because test changes were not allowed after the review.
