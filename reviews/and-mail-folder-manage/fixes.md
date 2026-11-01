# Fix Report

Date: 2026-11-01

Task: and-mail-folder-manage - Android: create, rename and delete mail folders

Scope: current task; findings CR-1 and CR-2 from reviews/and-mail-folder-manage/review.md (CR-1 is the same defect as test-writer TW-1, reproduced by MailFolderManageReloadRaceTest)

## Fixed findings

### CR-1 — Folder reloads and the mailbox load write `accounts` without a shared request identity

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt`

Problem:
`load()` took its accounts snapshot and then waited for the first messages page. When it finished, it wrote that snapshot over a folder create, rename or delete that had been applied in the meantime. Overlapping `reloadFolders()` calls could also overwrite each other in any order.

Fix:
The folder list now has a single owner, a `folderGeneration` counter. Each `load()` (when its coroutine starts), each `reloadFolders()` and each applied folder change increments it. `load()` and `reloadFolders()` write `accounts` only if the generation they started with is still the newest. `load()` still writes the messages, cursor, loading flags and message as before. A new `folderJob` (like `loadJob`) cancels a reload that is still running when a newer one starts.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.*'` — Pass. MailFolderManageReloadRaceTest (2 tests, unchanged) now passes.

### CR-2 — A 404 on rename or delete showed the generic error and kept the stale folder

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailFolderManageViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailFolderManageViewModelEdgeCasesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailFolderManageRepositoryTest.kt`

Problem:
When a folder had already been deleted elsewhere, rename or delete got a 404. The dialog stayed open with FolderEditError.FAILED, and the folder stayed in the list.

Fix:
`finishFolderEdit` now treats a 404 on RENAME or DELETE like a 412: it closes the dialog, reloads the folders and shows the existing FOLDER_CHANGED message. No new strings were added. A 404 on CREATE is unchanged and still shows FAILED in the dialog. The repository already passes the HTTP status through unchanged, so no production change was needed there.

Test changes:
- New ViewModel tests: a 404 on rename closes the dialog, reloads and says so; the same for delete; a 404 on create stays in the dialog with FAILED.
- In two error tables, the rows "404 on rename -> FAILED" (MailFolderManageViewModelTest) and "404 on delete -> FAILED" (MailFolderManageViewModelEdgeCasesTest) now use 403 instead. They encoded the old behavior, which the approved CR-2 decision replaces. Using 403 still covers the generic FAILED path.
- Repository test: rename and delete keep a 404 as `Failed(404)`. The ViewModel's mapping depends on this.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.*'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.*'` — Pass: 144 tests, 0 failures, 0 errors, 0 skipped, according to the JUnit XML results from this run. ktlint reported nothing.

Full test suite:
- Not run by the fixer. The parent runs the full Android suite and `.taskcheck`.

Lint/type-check/build:
- ktlintCheck — Pass. Kotlin compiled as part of the test run. lintDebug not run.

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
