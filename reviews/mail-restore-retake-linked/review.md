# Code Review

Date: 2026-10-26

Task: mail-restore-retake-linked - Mail sync: retake linked/reminded refs on the first sync after a restore

Scope: `git diff 724fe48be..8e82f4e45` (final revision). It holds the implementation commit dbe72b6ea (rebased from 29d496e04; the rebase changes only `tasks/` files) and the test commit 8e82f4e45 (test files only, no production code changed).

Requirements:
- tasks/mail-restore-retake-linked.md (acceptance criteria and recorded assumption)
- SPEC.md §10.2 (`mail_message_refs.jsonl`: Message-ID refs needed to keep links to emails)
- docs/ARCHITECTURE.md / AGENTS.md conventions (modules talk only through services)
- Existing code: MailHeaderSyncService, MailHeaderStore, MailBackupContributor, ReminderService, LinkService

Git range:
- Branch: main
- HEAD: 8e82f4e45923f3ea9c9f281ed447b1f4f1495c7e
- Base/start: 724fe48be72330f0873108be3d17df5970e59e6c
- Commits reviewed: 2 (dbe72b6ea, 8e82f4e45)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Earlier findings

- [x] **CR-1 | The decision that fired reminders count had no test** (P3, Tests). The first review pass (revision 29d496e04) raised this; it is resolved in 8e82f4e45. `ReminderPendingOfItemTests` now has `hasReminders counts fired and pending reminders of exactly that item, and none once they are deleted`. It fires one reminder (`fireDue() shouldBe 1`), then checks that `hasReminders` is true for the item with the fired reminder and for the item with the pending one. It is false for a note with the same id, false for an item with no reminders, and false once the reminders are deleted.

## Verification notes (no finding)

- AC1: in `MailHeaderSyncService.full`, `retakeLinked` now runs on every full sync. `detachFolder` and `pruneDetached` still run only when `uidValidity != null`. Restored folders are inserted without `uidvalidity` (`MailBackupContributor.insertFolder`), so the first sync after a restore takes the full path. Tests:
  - The mock-based sync-service test checks the Message-ID search for a ref older than the window, and that nothing is detached or pruned.
  - The new first-sync test with no detached refs checks that there is no search and no `locatedUids` call.
  - The new restore-then-sync test in `MailBackupContributorTests` restores a real backup and runs the real `MailHeaderSyncService` and `MailHeaderStore` against a scripted mailbox. Both refs are older than their account's sync window: T2 = 2026-03-03 against the IMAP window start of 2026-04-26, and 2025-06-01 against the Google window start of 2025-10-26. The window pass alone cannot reach them, so the test fails without the fix. It checks that the linked ref and the reminder-only ref are reattached to the same row ids, that no duplicate row is created, and that the folders' UIDVALIDITY is recorded afterwards.
- AC2: `isLinked` now means `links.hasLinks || reminders.hasReminders`. It goes through the core `ReminderService`, the same pattern as `LinkService`, and the repository is not used directly. `pruneDetached`, `linkedDetachedMessageIds` and `reconcile`/`remove` all pick it up. `MailHeaderStoreTests` covers all three for a reminder-only row. The Spring-context tests pass, so the new bean dependency causes no circular-dependency error.
- AC3: the warning and its constant are removed. The tests now assert the remaining warnings exactly, and nothing else refers to the old text.
- Running `retakeLinked` on a first sync with no detached rows costs one query that returns nothing, then an early return. No extra IMAP calls (now covered by a test).
- Calling `ReminderService` from outside `touches` (core/reminders) is covered by the recorded assumption.
- The test-only helpers in `MailBackupContributorTests` (`ScriptedMailbox`, `reminder`, the `receivedAt` parameter of `remote`) are local to the test class and use fictitious data.

## Review conclusion

No material issues found.
