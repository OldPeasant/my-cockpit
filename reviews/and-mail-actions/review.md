# Code Review

Date: 2026-11-08

Task: and-mail-actions - Android mail: unread, flag, move, spam, multi-select

Scope: committed changes `git diff ff304cbc4..59bdf99ac -- android` (all commits since the merge base whose message starts with "and-mail-actions:"): production code in `mail/list` (view model, models, repository, API DTO, screen, move dialog), `mail/read` (repository, screen, view model), `res/values*/strings_mail.xml`, and the new and adapted tests. A fresh, full review of the final code, including a check of the fixes for the earlier findings CR-1 and CR-2 (and the fixer's T-1 to T-3) described in `reviews/and-mail-actions/fixes.md`.

Requirements:
- `tasks/and-mail-actions.md`: acceptance criteria and the three recorded ASSUMPTIONs (undo semantics; bulk move only within one account; no multi-select in search, spam moved into the read view's overflow menu)
- `api/openapi.yaml` `MailActionRequest` / `MailActionResultResponse` / `MailMessageResponse` (`targetFolderId` only for MOVE, at most 1000 ids, one result per id; `flagged`, `accountId`, `folderId` on a message)
- `AGENTS.md` project conventions (i18n en+de, lint, feature folders, task `touches`)
- Server behaviour for moved ids (`MailActionService` KDoc: a moved mail keeps its id only with UIDPLUS `COPYUID`)

Git range:
- Branch: main
- HEAD: 59bdf99ac6cf2d75b485adf1b54daf95c8613a9f
- Base/start: ff304cbc4601f28c98fd59ff0bbf747197723977 (`git merge-base HEAD origin/main`)
- Commits reviewed: 4 (124e257aa, 16f1a5c68, f978799c9, 59bdf99ac)
- Uncommitted changes: none at review start

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Bulk failure messages use single-mail wording**
    - File: `android/app/src/main/res/values/strings_mail.xml:21-22,50,69-70` (and `values-de/strings_mail.xml`), used by `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailScreen.kt` `ListMessage` for `ARCHIVE_FAILED`/`DELETE_FAILED`/`SPAM_FAILED`/`MOVE_FAILED`/`MARK_FAILED`
    - Category: Quality
    - Problem: When a bulk action of several mails fails in part or in full, the list shows the singular text, for example "The mail could not be moved." / "Die E-Mail konnte nicht verschoben werden.", even after a 10-mail bulk move where 3 were refused. The success snackbars use plurals (`mail_done_*`), so the failure texts do not match them.
    - Why it matters: A small UX inconsistency. The user is not told that several mails (or which count) came back. Behaviour is correct, because the failed mails do reappear in the list.
    - Suggested fix: Make the failure texts count-neutral ("Some mails could not be moved." / "Nicht alle E-Mails konnten verschoben werden."), or turn them into plurals keyed by the number of failed mails. Optional.

## Verification of earlier findings

- CR-1 (bulk mark undo touched unchanged mails): fixed. `perform` (`MailListViewModel.kt:284`) now sends a marking action only to mails it changes (`action.applyTo(it) != it`), and only those go into `MailUndo`. A no-op mark sends nothing and ends the selection (`:285-288`). Regression tests exist: `MailActionsViewModelTest` "a bulk flag of a mixed selection ...", "a bulk mark read of a mixed selection ...", "a mark that changes no mail ...".
- CR-2 (mark undo lost after a mailbox switch): fixed. `unmark` (`:251-266`) sends the inverse for every mail in `undo.mails`, whatever mailbox is shown, updates only the rows that are listed, and on a partial failure puts the original mark back with UNDO_FAILED. Tests: "undoing a mark after another mailbox was opened ...", "a partly failed undo of a mark ...".
- T-1 (restore position after a partial failure): `restored` (`:654-674`) now leaves out acted mails that stay away when it computes the return index. Checked by hand against the [1,2,3,5] example: mail 2 returns above 5. Full undo restores behave the same as before.
- T-2 (an older answer replacing a newer undo): `actionCount` is taken at the start of each action and each undo. `perform` offers its undo only while `generation == actionCount` (`:302-303`), and otherwise keeps `s.undo`.
- T-3 (partial move undo message cleared by the reload): `load(mailbox, message)` (`:579-605`) carries UNDO_FAILED through a successful reload.

## Notes (not findings)

- Checked and found correct: read view menu (mark unread waits for the open-time mark-read job and then returns to the list; optimistic flag rolls back on refusal; the list is told only after the server took the change; move picker limited to the mail's account without its current folder; spam moved into the menu, as the assumption records); multi-select (long press starts it, tap toggles while selecting, swipes and their accessibility actions are disabled while selecting, Back ends the selection before leaving, the selection ends on mailbox switch and when search opens, and a reload filters it); bulk move enabled only for a single-account selection (`moveTargets`); bulk archive hidden and guarded in the Archive folder; undo of moves through MOVE back to each mail's own folder, grouped per folder (so mixed accounts work); no undo for a delete in Trash or a mail with an unknown folder; the snackbar is keyed on `MailUndo` (with `id`), and a replaced undo cancels the older snackbar without calling dismiss; `targetFolderId` left out for non-MOVE requests (`@EncodeDefault(NEVER)`), batching at the server's 1000-id limit; `MailMessageDto.flagged/accountId/folderId` match `MailMessageResponse`.
- A move undo depends on the server keeping the mail's id (UIDPLUS `COPYUID`). Without it the MOVE-back fails and UNDO_FAILED is shown, which is a graceful degradation consistent with the server contract.
- All changed files are within the task's `touches` (`MailApi.kt` and `MailRepository.kt` live in `mail/list`). English and German strings and plurals are complete and parallel.
- Adapted existing tests (`MailListViewModel*Test`, `MailReadScreenTest`, `MailReadSpamGermanTest`, `MailReadViewModelTest`) were changed to the new `actAll` API and the menu location of spam. No assertion was weakened. `MailReadViewModelTest` restricts its "closes the view" loop to ARCHIVE/DELETE/SPAM, because marks no longer close the view.
- `python tasks.py verify and-mail-actions` lists all 5 criteria as not yet verified. The parent worker still has to record the evidence. This review did not run lint or the test suites (read-only review).

## Review conclusion

No material issues found.
