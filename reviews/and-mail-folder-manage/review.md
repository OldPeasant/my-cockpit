# Code Review

Date: 2026-11-01

Task: and-mail-folder-manage - Android mail: create, rename and delete folders

Scope: `git diff 26368fcf1..HEAD -- android` (task commits 314df543d, 3cba7f0e5, 039c38745; the parent named them ca95d1da3, 1f0c3fa0a, ac5d85bbd before a rebase, and the android diff is the same)

Requirements:
- tasks/and-mail-folder-manage.md (acceptance criteria and the two recorded assumptions)
- SPEC.md §4.2 (ETag/If-Match, Problem Details), §7 (mail)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- Server contract: api/openapi.yaml and server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailAccountController.kt (createMailFolder, updateMailFolder, deleteMailFolder), core/web/ETags.kt

Git range:
- Branch: main
- HEAD: 039c38745a91f28247f6554c7fabdc471e485d8e
- Base/start: 26368fcf17accdeadc633264504b0d65f8f4501a
- Commits reviewed: 3 (code commits; the rest of the range is only tasks/ board commits)

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## Verified points (no finding)

- The hand-written rename is justified. The app's Retrofit (core/network/NetworkModule.kt:60-66) uses the generated `Serializer.kotlinxSerializationJson`, and that has `encodeDefaults = true` with the kotlinx default `explicitNulls = true`. So the generated `UpdateMailFolderRequest(name = x)` would send `"role": null` (and `"automatic": null`). On the server, `UpdateMailFolderRequest.role` has a tracking setter: `roleSent = true` makes `changesRole` true, so `overrideRole(..., null, ...)` runs and clears the role. `RenameMailFolderDto` sends only `{name}`, and MailFolderManageRepositoryTest checks that the body keys are exactly `name`. Create and delete use the generated `MailAccountsApi`, as criterion 4 asks.
- The If-Match format `"{version}"` matches `ETags.of` on the server (a strong tag). The list DTO now reads `version`, which `MailFolderResponse` always includes.
- The status codes map correctly: 400 is the invalid name, 409 is a taken name (or, on delete, a folder that holds a special folder), 412 reloads the folders and shows FOLDER_CHANGED, and 5xx/IO is UNREACHABLE (through `mailCall`). This matches the openapi responses for the three operations.
- Rename is hidden for INBOX, and Delete is hidden for folders with a role. The ViewModel guards check this too. Deleting the folder that is shown, or one of its parents, switches to `Mailbox.Unified`.
- Strings exist in both locales (values, values-de), and each action has a TalkBack contentDescription. Busy and double submits are blocked, and a result that arrives after the dialog was cancelled cannot reopen it.
- Tests cover the repository (wire format, If-Match, status mapping), the ViewModel (success, errors, 412, switch after delete, cancel, double submit) and the Compose UI (Robolectric: menu entries per folder type, dialogs, errors, confirmation text, switch, stale message). The parent reports that `./gradlew ktlintCheck testDebugUnitTest lintDebug` passed. The reviewer did not run Gradle.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Folder reloads and the mailbox load write `accounts` without a shared request identity, so an older snapshot can undo a folder change**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt:255` (reloadFolders), `:267-270` (load)
    - Category: Correctness
    - Problem: `load()` reads `repository.accounts()` first and then waits for `repository.messages(...)`. Only after both return does it write `accounts` from the first snapshot. `reloadFolders()` starts an untracked coroutine that also replaces `accounts`, and `load()` does not cancel it (nor does it cancel `load()`). Example: a refresh or mailbox switch is running, and its accounts snapshot is already taken while the messages page is still loading. The user creates, renames or deletes a folder in that time. `finishFolderEdit` applies the change and `reloadFolders` stores the new list. Then the slow `load()` finishes and writes the old snapshot back, so the new folder disappears, or a deleted folder comes back and selecting it fails. Two quick folder edits in a row can do the same with two overlapping `reloadFolders` calls.
    - Why it matters: Criterion 2 asks that the folder list shows the change at once after success. Here the change can show and then silently revert until the next manual refresh. The project checklist names this exact pattern: independent methods writing the same state need a shared cancellation or request identity.
    - Suggested fix: Give the account list one owner. For example, keep a folder-list generation counter that every folder edit bumps. `load()` and `reloadFolders()` then write `accounts` only when the generation they started with is still current, and a newer reload cancels an older one (a `folderJob`, like `loadJob`). Add a ViewModel test where a slow `messages()` call finishes after a successful create.

## P3 Low

- [ ] **CR-2 | A 404 (folder already gone elsewhere) shows the generic "try again" error and keeps the stale folder in the picker**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt:234-252` (finishFolderEdit), `:313-323` (folderError)
    - Category: Correctness
    - Problem: `updateMailFolder` and `deleteMailFolder` answer 404 when the folder was deleted on another device or by a sync. `folderError` maps that to `FolderEditError.FAILED` ("That did not work. Try again."), and no reload runs, so every retry fails the same way and the folder stays listed. A 412 for the same kind of concurrent change does reload.
    - Why it matters: The user gets an error they cannot recover from in the dialog, and the list stays wrong until a manual refresh. This is small because it needs a concurrent change from somewhere else.
    - Suggested fix: Treat a 404 on rename or delete like a 412: close the dialog, reload the folders and show FOLDER_CHANGED (or a "folder no longer exists" message). For delete, the folder can also be removed locally.

## Review conclusion

Material issues found; fix before completing the task. CR-1 is a P2 resilience defect in how the folder list is updated. No acceptance criterion is unmet. The parent decides whether CR-1 and CR-2 are fixed in this task or recorded with a reason.
