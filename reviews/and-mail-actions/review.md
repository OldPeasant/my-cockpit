# Code Review

Date: 2026-11-08

Task: and-mail-actions - Android mail: unread, flag, move, spam, multi-select

Scope: committed changes `git diff 0d22fac19..c3de08f84 -- android`, i.e. all six task commits since the merge base. That covers the production code in `mail/list` (view model, models, repository, API DTO, screen, move dialog), `mail/read` (repository, screen, view model) and `res/values*/strings_mail.xml`, plus all new and adapted tests. This is a complete review of the final code. It pays particular attention to the test-only commits made after the previous review: 8c3d6ce7b (additions to `MailActionsViewModelTest`, `MailSelectionScreenTest` and `MailReadMenuScreenTest`) and c3de08f84 (new `MailActionsInAppTest.kt`, `reviews/and-mail-actions/tests.md`).

Requirements:
- `tasks/and-mail-actions.md`: the acceptance criteria and the three recorded ASSUMPTIONs. They cover undo semantics, bulk move only within one account, no multi-select in search, and spam moving into the read view's overflow menu.
- `api/openapi.yaml` `MailActionRequest` / `MailActionResultResponse` / `MailMessageResponse`, together with the server's `MailActionController` / `MailActionService`. `targetFolderId` is only for MOVE, a request takes at most 1000 ids (`MAX_IDS`), and the result has one entry per id. A message has `flagged`, `accountId` and `folderId`. A moved message keeps its id when the server has UIDPLUS `COPYUID`.
- `AGENTS.md` project conventions: i18n en+de, lint, feature folders, the task's `touches`, fictitious test data.

Git range:
- Branch: main
- HEAD: c3de08f843cca1f9986f71c6ba1bf8f5b93afdba
- Base/start: 0d22fac191a21a2445375034c4005d4688842244 (`git merge-base HEAD origin/main`)
- Commits reviewed: 6 (52f8828c7, 9d132f730, 7ffe7bfcb, e888d6e33, 8c3d6ce7b, c3de08f84)
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
    - File: `android/app/src/main/res/values/strings_mail.xml` (`mail_archive_failed`, `mail_delete_failed`, `mail_spam_failed`, `mail_move_failed`, `mail_mark_failed`) and `values-de/strings_mail.xml`. They are used by `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailScreen.kt` `ListMessage` (around line 985).
    - Category: Quality
    - Problem: When a bulk action fails in part or in full, the list shows the singular text, for example "The mail could not be moved." / "Die E-Mail konnte nicht verschoben werden.". The success snackbars, by contrast, use plurals (`mail_done_*`).
    - Why it matters: This is a small UX inconsistency. The behaviour is correct, because the failed mails reappear in the list.
    - Suggested fix (optional): Make the texts count-neutral (for example "Some mails could not be moved." / "Nicht alle E-Mails konnten verschoben werden."), or use plurals keyed by the number of failed mails. The previous review already reported this as an optional P3.

## Acceptance criteria check

- AC1, read view menu: met. `MailReadScreen.kt` `MoreMenu` offers:
  - mark unread. It waits for the open-time mark-read job (`markReadJob?.join()`), sets `unread`, and the route calls `onMarked(id, UNREAD)` and closes.
  - flag/unflag. This is optimistic, rolls back with a notice on refusal, and notifies the list only after the server accepted it, through `MailMark.seq`.
  - move. `MoveFolderDialog` shows the other folders of the mail's own account (`moveTargets`) and is disabled when there are none.
  - spam.
  
  READ is ignored, as is MOVE without a target. A MOVE refused with `NO_ROLE_FOLDER` falls back to showing the code, because `roleFolder` returns `null`.
- AC2, multi-select: met.
  - A long press (`combinedClickable`) starts the selection. While selecting, a tap toggles a row, swipes and their accessibility actions are disabled, the compose FAB is hidden, and Back ends the selection.
  - The selection is cleared on a mailbox switch and when search opens, and a reload filters it.
  - The bar offers archive (only where `canArchive`), delete, read/unread and flag/unflag as the selection needs, and in its overflow move and spam.
  - Move is enabled only when all selected mails belong to one account (ASSUMPTION 2).
- AC3, swipes and undo: met.
  - Swipes still go through `act` -> `perform`, which is optimistic.
  - Failed mails are restored at their former positions (`restored`), and only in the same mailbox.
  - Undo is offered only when the action was the newest (`actionCount`) and fully succeeded. It is not offered for a delete in Trash or for a mail with an unknown folder (ASSUMPTION 1).
  - A move is undone by MOVE back to each mail's folder, grouped per folder, followed by a reload. A mark is undone by its inverse, also after a mailbox switch.
- AC4, strings: met. The 13 strings and 9 plurals are present and parallel in `values/` and `values-de/`. Every new UI text uses resources.
- AC5, tests: met.
  - Unit and Compose tests cover the view model (`MailActionsViewModelTest`, `MailReadActionsViewModelTest`), the repositories (batching, `targetFolderId` omission), the screens (`MailSelectionScreenTest`, `MailReadMenuScreenTest`), German texts, and the new in-app Hilt flow.
  - The reviewer re-ran `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.MailActions*' --tests 'ch.sonensei.cockpit.mail.list.MailSelectionScreenTest' --tests 'ch.sonensei.cockpit.mail.read.MailRead*' --offline`. Result: passed, no failures in the 17 result files (`MailActionsInAppTest`: 4 tests, 0 failures).
  - The reviewer did not re-run the full `ktlintCheck testDebugUnitTest lintDebug` or `sh scripts/check.sh`. The test-writer reports both as passed.

## Review of the new tests (8c3d6ce7b, c3de08f84)

- `MailActionsInAppTest` runs the real `MainActivity` with Hilt, with `MailRepository` / `MailReadRepository` replaced by MockK fakes. Its reloads deliberately answer with the old server state, so any change the test sees in the list can only come from the action under test. The assertions are behavioural (content descriptions "Unread" / "Flagged", row presence, snackbar text, exact `coVerify` calls). The waits are bounded (`WAIT_MS` 5 s). The test data is fictitious (`example.org`, tokens `*-fictitious`).
  - The mark-unread test checks that `markRead` is never called for the already-read mail 1. This confirms that unread reaches the list through `onMarked`, not through a reload.
  - The move test relies on the existing "removed goes on to the next mail" navigation, then checks the list without the moved mail.
  - The bulk archive + Undo test checks the exact `actAll` calls (ARCHIVE, then MOVE back to `inbox.id`).
- The `MailActionsViewModelTest` additions are correct against the code:
  - offline move-undo: rows are restored at once, then removed again, `UNDO_FAILED` is shown, no reload happens, and a double tap sends only once because `undo` checks `state.value.undo != undo`.
  - bulk mark overtaken by an older reload: `changed(done, action)` re-applies the mark after the reload.
  - refused mark after a mailbox switch: the new mailbox's rows are untouched and no undo is offered.
- The `MailReadMenuScreenTest` additions (NO_ROLE_FOLDER for MOVE shows the code, the menu is disabled while busy) and the extra SPAM_FAILED case in `MailSelectionScreenTest` match the production code.
- No existing test was weakened or removed. The adapted tests only follow the `actAll` API and spam's move into the menu.

## Notes (not findings)

- Integration:
  - `MailActionRequestDto.targetFolderId` is left out of non-MOVE requests (`@EncodeDefault(NEVER)`), which matches the server's "not allowed for other actions" rule.
  - `MAX_ACTION_IDS = 1000` matches `MailActionController.MAX_IDS`.
  - `MailMessageDto.flagged/accountId/folderId` and `MailSummaryDto.folderId` match the OpenAPI schemas, and malformed UUIDs map to `null`, so no undo is offered for them.
- All changed files are within the task's `touches` (`MailApi.kt`, `MailModels.kt` and `MailRepository.kt` live in `mail/list`).
- No logging of mail content was added. No new dependencies were added.
- `python tasks.py verify and-mail-actions` still lists criteria 1-5 as unverified. The parent worker has to record the evidence before `done`.

## Review conclusion

No material issues found.
