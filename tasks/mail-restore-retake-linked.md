---
title: Mail sync: retake linked/reminded refs on the first sync after a restore
status: in_progress
owner: dell26/agent-1
deps: [backup-contrib-mail,mail-sync-perf]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/sync,server/src/test/kotlin/ch/sonensei/cockpit/mail/sync,server/src/main/kotlin/ch/sonensei/cockpit/mail/backup]
prio: 2
lease_until: 2026-10-26T03:31:38Z
---
## Goal
After a backup restore, mail refs (detached rows, uid null) that a link or reminder points to are reattached by Message-ID even when the mail is older than the sync window (SPEC.md §10.2 "links survive"; review CR-1 of backup-contrib-mail).

## Acceptance criteria
- [x] The first full sync of a folder (uidvalidity null) also runs the linked-refs retake (`retakeLinked`), covered by a `MailHeaderSyncService` test with a linked detached ref older than the window
  - verified (2026-10-26 04:12+0100 by dell26/agent-1): cd server && ./gradlew test --tests '*MailHeaderSyncServiceTests' --tests '*MailBackupContributorTests' (Postgres Testcontainers) -> 44/44 + 21/21 pass, incl. 'the first sync after a restore takes over linked refs older than the window by Message-ID and prunes nothing' and the restore->first-sync end-to-end test
- [x] `MailHeaderStore.isLinked` also counts reminders (item_type EMAIL), so `pruneDetached` keeps reminder-only refs and `linkedDetachedMessageIds` retakes them; covered by a test
  - verified (2026-10-26 04:12+0100 by dell26/agent-1): cd server && ./gradlew test --tests '*MailHeaderStoreTests' --tests '*ReminderPendingOfItemTests' -> 25/25 + 3/3 pass, incl. 'a reminder alone keeps a detached row through pruneDetached and vanishing, and its Message-ID is retaken' and hasReminders fired/pending test
- [ ] Remove the restore warning about older linked emails in `MailBackupContributor.restoreWarnings` once it no longer applies

## Notes
- ASSUMPTION (2026-10-26 04:06+0100 by dell26/agent-1): Added ReminderService.hasReminders (+ ReminderRepository.existsByItemTypeAndItemId) in core/reminders, outside touches, so the mail module asks through a service; fired reminders count too, matching what the backup exports. Since isLinked is shared, reconcile now also detaches (not deletes) reminder-only vanished messages. pruneDetached still runs only on a UIDVALIDITY resync, not on the first sync.
