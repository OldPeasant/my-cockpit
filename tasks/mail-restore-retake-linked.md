---
title: Mail sync: retake linked/reminded refs on the first sync after a restore
status: in_progress
owner: dell26/agent-1
deps: [backup-contrib-mail,mail-sync-perf]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/sync,server/src/test/kotlin/ch/sonensei/cockpit/mail/sync,server/src/main/kotlin/ch/sonensei/cockpit/mail/backup]
prio: 2
lease_until: 2026-10-26T03:21:10Z
---
## Goal
After a backup restore, mail refs (detached rows, uid null) that a link or reminder points to are reattached by Message-ID even when the mail is older than the sync window (SPEC.md §10.2 "links survive"; review CR-1 of backup-contrib-mail).

## Acceptance criteria
- [ ] The first full sync of a folder (uidvalidity null) also runs the linked-refs retake (`retakeLinked`), covered by a `MailHeaderSyncService` test with a linked detached ref older than the window
- [ ] `MailHeaderStore.isLinked` also counts reminders (item_type EMAIL), so `pruneDetached` keeps reminder-only refs and `linkedDetachedMessageIds` retakes them; covered by a test
- [ ] Remove the restore warning about older linked emails in `MailBackupContributor.restoreWarnings` once it no longer applies

## Notes
