# Code Review

Date: 2026-11-08

Task: and-mail-actions - Android mail: unread, flag, move, spam, multi-select

Scope: committed changes `git diff 30a2514 HEAD -- android` (production code in `mail/list`, `mail/read`, `res/values*/strings_mail.xml`; adapted existing tests). Uncommitted test files added at the same time by the test-writer were not reviewed.

Requirements:
- `tasks/and-mail-actions.md` acceptance criteria and recorded assumptions (undo semantics, single-account bulk move, no selection in search)
- SPEC.md §7 (mail actions), `api/openapi.yaml` `/api/v1/mail/messages/actions` (`MailActionRequest`: `targetFolderId` required for MOVE, not allowed otherwise; a moved message keeps its id only when the server reports its new UID)
- AGENTS.md conventions (i18n, lint, feature folders)

Git range:
- Branch: main
- HEAD: 3ae6ac899692b4129bace456b1dd4749a4443dbf
- Base/start: 30a2514056307711e863f3cf1f277d5f9d152dc5
- Commits reviewed: 2 (4f0621776, 3ae6ac899)

Summary: 0 P0, 1 P1, 1 P2, 0 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Undoing a bulk mark/flag changes mails the action never changed**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt:239-241` (with `MailScreen.kt` `SelectionBar`, `val read = ...` / `val flag = ...`)
    - Category: Correctness
    - Problem: The selection bar offers "Mark read" while *any* selected mail is unread and "Flag" unless *all* are flagged, so a bulk READ/FLAG regularly covers mails that already were read/flagged. `perform` records every selected mail in the `MailUndo`, and `undo()` sends the inverse (`UNREAD`/`UNFLAG`) for all `undo.mails`. Example: select 3 mails, 1 already flagged, 2 not; tap Flag; tap Undo. All 3 end up unflagged on the mail server, so the user loses the flag they had set before. The same happens for read state (a mail that was read becomes unread). The failure path (`restored`) does restore each mail's old state correctly. Only undo is wrong.
    - Why it matters: Undo is meant to put things back as they were (criterion 3, and the recorded assumption "marks are undone by the inverse action"). Here it changes user state on the server that the user never touched.
    - Suggested fix: For marking actions, keep in the undo (and send the inverse to) only the mails whose state the action actually changed (`action.applyTo(m) != m`). Better still, send the action only to those mails in the first place. Add a test that selects a mixed selection, applies FLAG/READ, then undoes it.

## P2 Medium

- [ ] **CR-2 | Undo of a mark does nothing after the mailbox was switched**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt:174-178, 241, 259-262`
    - Category: Correctness
    - Problem: `select(mailbox)` keeps `state.undo`, so the snackbar keeps offering Undo (SnackbarDuration.Long) after the user picks another mailbox. For a marking action, `undo()` calls `perform(undo.mails ids, inverse)`. `perform` looks the mails up in `before.messages`, which now holds the new mailbox, and returns early when none are found (`if (mails.isEmpty()) return`). The undo is cleared, no request goes out and nothing tells the user. If only some of the mails are in the new list, only those are reverted. Undo of a move does not have this problem because `moveBack` works from `undo.mails` directly.
    - Why it matters: The user taps Undo, nothing happens, and they cannot try again, because the undo was dropped.
    - Suggested fix: Undo marks from `undo.mails` (send the inverse for those ids, and update the list only for mails it shows), the same way `moveBack` works. Or clear `undo` in `select()` so the snackbar is no longer offered for a mailbox that is not shown.

## P3 Low

None.

## Notes (not findings)

- These parts were checked and found correct: the race handling between optimistic list updates and reloads (`changed(done)` re-applied, failed mails restored only in the same mailbox); selection filtering on reload, mailbox switch and search; `BackHandler` order (selection vs. search vs. read view); swipes disabled while selecting; `targetFolderId` left out for non-MOVE requests (`@EncodeDefault(NEVER)`, matches the OpenAPI contract); the read view's flag rollback on failure; the "unread" request waiting for the open-time mark-read job; the move picker limited to the mail's account; English and German strings and plurals complete and parallel.
- Lint and unit tests were not re-run by this review (read-only). Tests for the new behaviour are being added by the test-writer and were out of scope here.

## Review conclusion

Material issues found; fix before completing the task.
