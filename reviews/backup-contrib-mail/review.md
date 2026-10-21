# Code Review

Date: 2026-10-22

Task: backup-contrib-mail - Backup contributor: mail accounts

Scope: the whole task diff `git diff 0e52c67f0f5de294a011991d8c926988805be59b..HEAD` on `main`, base `git merge-base HEAD origin/main` = 0e52c67f0f5de294a011991d8c926988805be59b. Task code commits: d5df4ee6 (implementation: `server/src/main/kotlin/ch/sonensei/cockpit/mail/backup/MailBackupContributor.kt`, `server/src/test/kotlin/ch/sonensei/cockpit/mail/backup/MailBackupFixture.kt`), abe98ca6 and 445a6da7 (`MailBackupContributorTests.kt`, `reviews/backup-contrib-mail/tests.md`), d18a847a (restore warning for linked mails older than the sync window, follow-up task `tasks/mail-restore-retake-linked.md`). Later commits 8a161a7c (earlier review report) and bd8d5108 (quality.json) touch only `reviews/`. This is a re-check after a rebase: the task files `mail/backup/*` (main and test) and `tasks/mail-restore-retake-linked.md` are byte-identical to the revision reviewed before (`git diff 9199bd87 HEAD` on those paths is empty, and the sha256 hashes match the ones in `tests.md`). The findings and checks below were re-confirmed against HEAD.

Requirements:
- tasks/backup-contrib-mail.md (acceptance criteria 1-2, recorded assumptions #1-#4)
- SPEC.md §10.2 (backup format, secrets, links survive), §7.1
- docs/ARCHITECTURE.md, docs/QUALITY.md, AGENTS.md project notes
- Context code: backup/domain/BackupContributor.kt (reader and writer API), mail/sync/MailHeaderStore.kt (`isLinked`, `pruneDetached`, `linkedDetachedMessageIds`, `reattachable`), mail/sync/MailHeaderSyncService.kt (`full`, `retakeLinked`), backup/BackupRoundTripTest.kt (fixture discovery)

Git range:
- Branch: main
- HEAD: bd8d510821db35130e67c8a23ae83adbed23483f
- Base/start: 0e52c67f0f5de294a011991d8c926988805be59b (merge-base with origin/main)
- Commits reviewed: 6 (d5df4ee6, abe98ca6, d18a847a, 445a6da7 with code; 8a161a7c, bd8d5108 with review artifacts only). The working tree was clean.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Import log line ignores singular and plural**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/backup/MailBackupContributor.kt:92`
    - Category: Quality
    - Problem: The import logs "Restored {} IMAP mail accounts without their password", which reads "Restored 1 IMAP mail accounts" in the common one-account case. The `count()` helper in the same class already handles singular and plural for the restore warnings.
    - Why it matters: A small inconsistency in an operator-facing message. It has no functional impact.
    - Suggested fix: Optional. Build the message with `count(withoutPassword, "IMAP mail account")`, and update the assertion in `MailBackupContributorTests.kt:355`.

## Resolution of the earlier findings

- Earlier CR-1 (linked mails older than the sync window are not reattached after a restore): accepted as resolved for this task's scope. The fix has three parts:
  - The restore warning at `MailBackupContributor.kt:109-111`, shown only when `mail_message_refs` exists.
  - Assumption #4, which humans can review.
  - The follow-up task `tasks/mail-restore-retake-linked.md`. It depends on `backup-contrib-mail` and on `mail-sync-perf` (which exists and is not started yet). Its criteria cover `retakeLinked` on the first sync, `isLinked` counting reminders, and removing the warning again.

  The reason for not using a sentinel UIDVALIDITY holds up in the code. `MailHeaderStore.isLinked` (line 152) checks only `links.linksOf(...)`, so on the resync path `pruneDetached` (from line 117) would delete refs that only a reminder points to, such as the fixture's reminded Google message. Rejecting the sentinel avoids that data loss.
- Earlier CR-2 (no contributor tests for criterion 1): resolved. `MailBackupContributorTests.kt` covers:
  - the password only in the encrypted secrets with a passphrase, and nowhere without one, including no ciphertext;
  - no password or sync-state fields in `mail_accounts.json`;
  - refs only for messages that a link (either end) or a reminder points to, and not for links or reminders of other item types that use the same UUID;
  - an exact set of ref fields and no content strings anywhere in `data/`;
  - restored refs that are detached and have no content;
  - the password encrypted again on import, and null when the passphrase is missing;
  - every restore-warning variant;
  - reattachment by Message-ID through `MailHeaderStore.upsert`.

  The sha256 hashes in `tests.md` for `MailBackupContributor.kt`, `MailBackupContributorTests.kt` and `MailBackupFixture.kt` match the files at HEAD. The commit hashes it cites (24a2beb3 and earlier) are from before the rebases, but the content is identical.

## Checked without findings

- Criterion 1:
  - `mail_accounts.json` nests identities (ordered by position) and folders (path, role, override, selectable), as assumption #1 records.
  - Passwords are decrypted only when `writer.includesSecrets` is true. They are written only through `writer.secret(PASSWORDS, ...)`, never to the data files, logs or warnings.
  - The ref query uses EXISTS on both ends of `link` and on `reminder` with `item_type = EMAIL`. It writes no ref file when nothing matches.
  - Refs carry only id, times, version, account, folder and Message-ID.
- Criterion 2: `MailBackupFixture` seeds an IMAP account (with a password, identities, an overridden folder and sync state) and a Google OAuth account. It links to a mail from a task, links from a detached mail, and adds a reminder on a mail. `BackupRoundTripTest` finds it by classpath scanning, and the contributor id matches. The test-writer reports that `BackupRoundTripTest` passes.
- Import order and constraints: the account is inserted before its identities and folders, and refs are inserted after all accounts, so the foreign keys hold. `dependsOn` the OAuth contributor matches the calendar contributor. Ids, times and versions are kept through JDBC, the same way as the other contributors.
- Restored folders have null sync state, so the first sync is a full window sync. `reattachable` prefers the same folder and then any folder of the account, and a test covers this.
- Architecture: the contributor stays inside `mail/backup`. Reading the core `link`/`reminder` tables with SQL inside the export follows an existing precedent and is recorded in assumption #2. No build files, migrations, API or shared docs changed.
- Security: the fixture and test data are fictitious. No secrets are in the diff. Logs contain only counts.
- Evidence: `python tasks.py verify backup-contrib-mail` shows both criteria as verified.

Note for the parent worker: `reviews/backup-contrib-mail/quality.json` still names `reviewed_revision` 7e07ad0b, a pre-rebase hash that is not on the current main history. Set it to the current hash of the final reviewed code.

## Review conclusion

No material issues found.
