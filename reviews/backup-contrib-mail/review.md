# Code Review

Date: 2026-10-21

Task: backup-contrib-mail - Backup contributor: mail accounts

Scope: implementation commit b405ee14ddec5d3467df63dfc76f8c3bb6a82391 on base 3380f4bd2d7a247317bae24cd0564c0345591d7d (`git diff 3380f4bd..b405ee14`). The server code is identical to HEAD 830c1222 (`git diff b405ee14 830c1222 -- server` is empty). Files: `server/src/main/kotlin/ch/sonensei/cockpit/mail/backup/MailBackupContributor.kt`, `server/src/test/kotlin/ch/sonensei/cockpit/mail/backup/MailBackupFixture.kt`. The test-writer's tests, written at the same time, were not part of this scope.

Requirements:
- tasks/backup-contrib-mail.md (acceptance criteria 1-2, recorded assumptions #1-#3)
- SPEC.md §10.2 (zip format, secrets, links survive), §7.1
- docs/ARCHITECTURE.md, docs/QUALITY.md, AGENTS.md project notes
- Compared with core/backupcontrib/OAuthBackupContributor.kt, calendar/backup/CalendarBackupContributor.kt, core/backupcontrib/LinksBackupContributor.kt, mail/sync/MailHeaderStore.kt, mail/sync/MailHeaderSyncService.kt, mail/imap/MailFolderService.kt, mail/model/MailMessageLocator.kt, backup/BackupRoundTripTest.kt

Git range:
- Branch: main
- HEAD: 830c1222523d09077e26f969d76f1782999e889a
- Base/start: 3380f4bd2d7a247317bae24cd0564c0345591d7d
- Commits reviewed: 1 (b405ee14, rebased as 830c1222; the other commits in the range are only task-state/assumption commits)

Summary: 0 P0, 0 P1, 2 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Linked mails older than the sync window stay unresolvable after a restore**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/backup/MailBackupContributor.kt:77` (folders restored with `uidvalidity` null), interacting with `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt:160-178`
    - Category: Correctness
    - Problem: Restored folders have `uidvalidity = null`, so the first sync takes the "first sync" path of `full()` (`resync = false`). That path only fetches the sync window and never calls `retakeLinked`. A restored ref (detached, `uid` null) whose message arrived before the window (default 12 months, 6 in the fixture's IMAP account) is never matched by Message-ID. `MailMessageLocator.current` then returns null, so the link or reminder shows nothing until the owner scrolls back far enough for `loadOlder` to load it. Assumption #2 records this, but SPEC.md §10.2 says the refs exist "to keep links to emails", and linked mails are often older than the window (e.g. an invoice linked to a task).
    - Why it matters: For older linked mails, a backup round trip on a real install silently breaks links that SPEC.md §10.2 says must survive. The round-trip test cannot catch this because it compares exports, not resolution against an IMAP server.
    - Suggested fix: Make the first sync after a restore look up linked detached rows by Message-ID. One option that stays inside this task's files is to restore folders with a sentinel UIDVALIDITY that no server reports (IMAP UIDVALIDITY is a non-zero 32-bit value, so `0` works). The first sync then takes the resync path: `detachFolder` (a no-op), the window pass, `retakeLinked`, `pruneDetached`. Check first that `pruneDetached` does not drop refs that only a reminder points to (`MailHeaderStore.isLinked` only looks at links). The cleaner option is to run `retakeLinked` in `full()` on the first sync as well, which would need a small follow-up task in `mail/sync`. If the current behavior is kept on purpose, flag it for humans as a deviation from §10.2 (the assumption already exists) and add a restore warning saying that links to mails older than the sync window come back only when those mails are loaded.

- [ ] **CR-2 | Criterion 1 has no automated check in the reviewed commit (passwords secret, linked only, no content)**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/mail/backup/MailBackupFixture.kt:1` (the only test file); `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupRoundTripTest.kt:102-103` (exports without a passphrase)
    - Category: Tests
    - Problem: `BackupRoundTripTest` exports without a passphrase, so `writer.includesSecrets` is false. The export of the `mail_passwords` secret (`MailBackupContributor.kt:58`) and the import that encrypts it back into `mail_account.password` never run. The round trip only compares export before and after. It would still pass if the export left out the link/reminder filter in `messageRefs()` (the extra row is simply not restored), and it never checks that no subject, addresses or body reach the backup. `restoreWarnings` and the "restored without password" case are not covered either. Unlike calendar (`CalendarBackupContributorTests.kt`) and core (`CoreBackupContributorsTests.kt`), the reviewed commit has no contributor test.
    - Why it matters: Criterion 1 (secrets only through the secrets mechanism, refs for linked mails only, no message content) has no repeatable evidence. A regression that leaks a password into `mail_accounts.json`, or that exports unlinked messages, would go unnoticed.
    - Suggested fix: Add `MailBackupContributorTests`, following `CalendarBackupContributorTests`. Cover: with a passphrase the password is only in the secrets and comes back decryptable after import; without a passphrase the IMAP password is null and the warnings name the IMAP account; `mail_accounts.json` has no password or sync-state fields; `mail_message_refs.jsonl` holds only the linked and reminded messages, with no subject or address fields; a restored ref is detached (`uid` null). The test-writer is reportedly adding these. Check them before recording evidence for criterion 1.

## P3 Low

None.

Checked without findings:
- Ids, `created_at`, `updated_at` and `version` are kept through JDBC, the same way as the other contributors. The CHECK constraints (`mail_account_credentials`, ports, roles) and the unique indexes (folder path and role, default identity) are satisfied by data exported from a valid database.
- Accounts are inserted before identities, folders and refs, so the foreign keys hold. `dependsOn` the OAuth contributor matches calendar. The `oauth_connection_id` has no foreign key, as the schema intends.
- Password handling: the password is decrypted only when `includesSecrets` is true, written only through `writer.secret`, and re-encrypted with `SecretCipher` (the same format as `EncryptedSecretConverter`). Without it, `MailSessions` reports AUTHENTICATION_FAILED. Logs and warnings contain only counts.
- Restored folders keep their path, role and `role_overridden`. `MailFolderService.sync` matches them by path and keeps the overrides. Folders that are gone from the server are deleted, and refs fall back to `folder_id` null through the foreign key's `on delete set null`. `MailHeaderStore.upsert` reattaches detached refs by Message-ID within the window, preferring the same folder.
- Detached refs are hidden from lists (`uid is not null`) and resolve through `MailMessageLocator`, as the KDoc says.
- Reading the core `link` and `reminder` tables with SQL inside the export snapshot follows the precedent in `NotesBackupContributor`, and assumption #2 records it.

## Review conclusion

Material issues found; fix before completing the task. CR-2 must be resolved (contributor tests for criterion 1) before criterion 1 can be verified. CR-1 needs a fix, or an explicit human-visible decision on the recorded assumption #2, plus a restore warning.
