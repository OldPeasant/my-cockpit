# Code Review

Date: 2026-11-01

Task: and-mail-folder-manage - Android mail: create, rename and delete folders

Scope: `git diff b190d2a7570a77756217e46994ecdad61aa0ffde..43b25565f -- android` (code commits c20609e88, 0b44533c5, b3f94298a, a1e658b9c, 43b25565f). This is a fresh review of the final code. It also checks whether commit 43b25565f (the fixer's change, described in reviews/and-mail-folder-manage/fixes.md) resolves CR-1 and CR-2 from the earlier review.

Requirements:
- tasks/and-mail-folder-manage.md (acceptance criteria and the two recorded assumptions)
- SPEC.md §4.2 (ETag/If-Match, Problem Details)
- docs/ARCHITECTURE.md and the AGENTS.md project notes
- reviews/and-mail-folder-manage/fixes.md and the earlier review of the same code

Git range:
- Branch: main
- HEAD: 43b25565fa59408be58d06af6a722dd68c7c557d
- Base/start: b190d2a7570a77756217e46994ecdad61aa0ffde
- Commits reviewed: 5
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Status of the earlier findings

- **Earlier CR-1 (an older folder snapshot could undo a folder change): resolved.** `MailListViewModel` now gives the folder list one owner through `folderGeneration` (MailListViewModel.kt:89). Each `load()` takes a generation number when its coroutine starts (:283). Each `reloadFolders()` takes one too (:269), and so does each applied success (:253). `load()` writes `accounts` only while its number is still the newest (:288-290). The messages, cursor, loading flag and message are still written in every case, so loading always ends. `reloadFolders()` writes only while its number is the newest (:273), and it cancels a reload that is still running (:268). I checked the possible orders. A load that started before an edit loses to the edit and to the reload that follows it. A load that starts after a reload wins, and that is right because it fetched its list later. Two quick edits leave only the last reload alive. A load whose accounts call fails leaves the list as it is, including the change applied locally. Everything runs on the main dispatcher, so the counter needs no synchronization. MailFolderManageReloadRaceTest (create and delete while a slow refresh is running) covers the reported case.
- **Earlier CR-2 (a 404 on rename or delete showed a generic error and kept a folder that was already gone): resolved.** `finishFolderEdit` (:248-250, :262) now handles a 404 on RENAME or DELETE like a 412: it closes the dialog, shows FOLDER_CHANGED and reloads the folders. A 404 on CREATE (the parent is gone) still shows FAILED in the dialog. New ViewModel tests cover all three cases. A repository test checks that a 404 reaches the ViewModel as `Failed(404)`. The two error-table rows that used 404 for FAILED now use 403. That change follows from the new behavior, and the generic FAILED path is still covered.

## Verified points (no finding)

- Criterion 1: the folder picker has a New-folder action for each account and an overflow menu for each folder. Rename is hidden for INBOX (`canRename`) and Delete is hidden for folders with a role (`canDelete`). The ViewModel checks both again in `startRenameFolder` and `startDeleteFolder`.
- Criterion 2: errors are mapped to dialog errors. 400 is an invalid name, 409 is a taken name, and Offline or 5xx means the server is unreachable. The dialog stays open on these errors. A success updates `accounts` at once (`withFolder`, `withoutFolder`) and then reloads. A blank name is rejected without calling the server. A busy dialog ignores a second submit. A result that arrives after the user cancelled does not reopen the dialog.
- Criterion 3: the confirmation text says that the folder's messages and subfolders are also deleted on the mail server, in both locales. Deleting the folder that is shown, or one of its parents, switches to `Mailbox.Unified` (:227-230).
- Criterion 4: rename and delete send `"{version}"` as If-Match, the strong ETag form the server uses. Create and delete use the generated `MailAccountsApi`. Rename goes through a hand-written `MailApi.renameFolder` that sends only `{name}`. The recorded assumption explains why: the generated request serializes `role: null`, and the server reads that as "remove the role". The repository test checks the exact keys of the request body.
- Criterion 5: all 21 strings exist in `values` and `values-de`, the only two locales. The overflow button has the content description "Folder actions: {folder}", and the New-folder button has "New folder in {account}".
- Criterion 6: there are repository, ViewModel, models and Robolectric Compose tests, including German strings, edge cases and the reload race. The fixer reports 144 tests passing in `ch.sonensei.cockpit.mail.list.*`. The parent is running the full `./gradlew ktlintCheck testDebugUnitTest lintDebug`. I did not run Gradle.
- Conventions: all changes stay inside the task's `touches` (mail/list, its tests, and new string files). Logs contain no secrets or mail content. `MailHttpFailure` carries only the status code.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | When the folder on screen turns out to be gone (404 or 412 on delete), the screen stays on that folder and its title falls back to the unified Inbox**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt:226-230`, `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailScreen.kt:460`
    - Category: Correctness
    - Problem: The switch to `Mailbox.Unified` runs only when `finishFolderEdit` returns true, which means the delete succeeded. Suppose the user deletes the folder on screen but the folder was already deleted elsewhere, so the server answers 404 (or 412). The reload then removes the folder from `accounts`, but `mailbox` stays `Mailbox.Folder(id)`. `state.folder` becomes null, so the title shows "Unified Inbox" while the list still shows the deleted folder's messages, and the next refresh of that mailbox fails.
    - Why it matters: The screen is inconsistent and the user has to switch mailboxes by hand. This only happens when the folder is changed on another device at the same time, so the priority is low.
    - Suggested fix: After a folder reload, if the shown `Mailbox.Folder` is no longer in `accounts`, call `select(Mailbox.Unified)`. Doing this in one place also covers a folder deleted elsewhere and picked up by a plain refresh. Add a ViewModel test where a 404 on deleting the shown folder ends on the unified Inbox.

- [ ] **CR-2 | The generation guard is only tested for "load started before the edit", not for overlapping folder reloads**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailFolderManageReloadRaceTest.kt:52-102`
    - Category: Tests
    - Problem: Both race tests delay `messages()` in a refresh that started earlier. No test covers two quick folder edits whose reloads finish in reverse order, or a reload that finishes after a newer `load()`. `folderJob` cancellation and the check at MailListViewModel.kt:273 are part of the fix, but no test would fail if someone removed them.
    - Why it matters: Future changes to the folder list could bring back the stale-overwrite defect without any test failing.
    - Suggested fix: Add one test with two successful edits where the first reload's `accounts()` call is held by a `CompletableDeferred` and completed last. The test should check that the list shows the result of the second reload.

## Review conclusion

No material issues found. The earlier CR-1 and CR-2 are resolved in 43b25565f, and every acceptance criterion is met in the code. The two remaining P3 findings are optional improvements. The parent decides whether to fix them or record why they stay.
