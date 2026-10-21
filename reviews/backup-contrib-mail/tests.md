# Test Report

Date: 2026-10-22

Task: backup-contrib-mail - Backup contributor: mail accounts

Scope: base 06232336fb2d4b66ba1e6ece629c6dc55d225f1e .. HEAD 24a2beb3929f8b34c0b6e9ecc12c2ffc326311d6 on `main`, task commits 3ec522fd (implementation), 5e2f25a7 (contributor tests from an earlier test-writer run that stopped before writing its report), 24a2beb3 (restore warning for review CR-1), plus this run's uncommitted change to `MailBackupContributorTests.kt`. The production file in scope is `server/src/main/kotlin/ch/sonensei/cockpit/mail/backup/MailBackupContributor.kt`. `MailBackupFixture.kt` is test code and is not in the coverage scope.

Requirements:
- tasks/backup-contrib-mail.md: acceptance criteria 1-2 and the recorded assumptions, including the CR-1 restore warning
- reviews/backup-contrib-mail/review.md: CR-1 (restore warning) and CR-2 (the list of contributor tests that were missing)
- SPEC.md §10.2, docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/backup/MailBackupContributorTests.kt`

## Tests added or updated

Count: 3 (1 updated, 2 added)

- Updated `restore warnings name the refetch, the IMAP passwords to enter again and the old linked mails that come back later` (formerly `restore warnings say that mail is fetched again and that IMAP passwords must be entered again`). It now expects the new warning as the third entry, both with and without the passphrase. Before this update it failed against 24a2beb3 because it compared the exact list without that warning.
- Added `a backup with accounts but no linked or reminded mails has no warning about old linked mails`. Accounts are present but no link or reminder points to a mail, so no `mail_message_refs.jsonl` is written. The exact warnings list (with and without a passphrase) leaves out the old-linked-mails warning.
- Added `only a reminder on a mail is enough for the warning about old linked mails`. A single OAuth account and one mail that only a reminder points to (no link) produce the warning.

The other 17 tests from 5e2f25a7 were checked against review CR-2 and kept unchanged.

## Requirement coverage

Command for all rows: `cd server && ./gradlew ktlintCheck test --tests '*MailBackupContributorTests' --tests '*BackupRoundTripTest'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 / CR-2: mail_accounts.json holds the config with identities and folders, and no password | `accounts json holds every account's config with identities by position and folders by path, without password` | see above | Pass |
| AC1 / CR-2: no sync-state fields in mail_accounts.json | `folder sync state (UIDVALIDITY, UIDNEXT, HIGHESTMODSEQ, last sync) is not exported` | see above | Pass |
| AC1 / CR-2: with a passphrase, passwords are only in the encrypted secrets | `with a passphrase the IMAP password is only in the encrypted secrets, keyed by account id` | see above | Pass |
| AC1 / CR-2: without a passphrase, no password anywhere | `without a passphrase no password is written anywhere and there are no secrets` | see above | Pass |
| CR-2: password decryptable after import with the passphrase | `with the passphrase the IMAP password is restored and encrypted again, OAuth accounts keep none` | see above | Pass |
| CR-2: without the passphrase, the IMAP password is null after restore, plus warnings | `without the passphrase the IMAP account is restored without its password, and the log says so`, `restore warnings name the refetch, ...`, `restore warnings for OAuth accounts only mention the sync, and count accounts in the plural` | see above | Pass |
| AC1 / CR-2: refs only for linked or reminded mails | `message refs are only the messages a link (either end) or a reminder points to, once each, in id order`, `links and reminders of other item types with a message's id do not export it`, `accounts without links or reminders to their messages write no message refs` | see above | Pass |
| AC1 / CR-2: no message content (no subject, addresses, snippet or body) | `message refs carry no subject, addresses, snippet, body, UID or flags`, `import restores refs as detached rows without content, and unreferenced messages not at all` | see above | Pass |
| CR-2: restored refs are detached (uid null) | `import restores refs as detached rows without content, and unreferenced messages not at all` | see above | Pass |
| Restore keeps ids, times and versions; folders come back without sync state | `import restores accounts, identities and folders with their ids, times and versions, folders without sync state` | see above | Pass |
| Restored refs are taken over again by Message-ID | `the header sync takes a restored ref over by its Message-ID, so the link points to the message again`, `a ref found in another folder of its account is taken over there too` | see above | Pass |
| No mail data: no rows, no warnings | `an install without mail accounts writes no mail data`, `a backup without mail data restores no mail rows and has no restore warnings` | see above | Pass |
| CR-1: the old-linked-mails warning appears only when refs exist | `restore warnings name the refetch, ...`, `a backup with accounts but no linked or reminded mails has no warning about old linked mails`, `only a reminder on a mail is enough for the warning about old linked mails` | see above | Pass |
| AC2: round trip with MailBackupFixture (IMAP + OAuth account + a link to a mail) | `BackupRoundTripTest` | see above | Pass |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests '*MailBackupContributorTests' --tests '*BackupRoundTripTest'`

Result:

Pass (BUILD SUCCESSFUL, 2026-10-21 23:40). JUnit XML: MailBackupContributorTests 20 tests, 0 failures, 0 errors, 0 skipped; BackupRoundTripTest 1 test, 0 failures. An earlier attempt failed on ktlint (a test name longer than 140 characters). I shortened the name and reran.

## Full test execution

Command:

`cd server && ./gradlew check jacocoTestReport` (23:41-23:50), then `cd server && ./gradlew test jacocoTestReport` (23:52-00:00)

Result:

Pass on the second run (BUILD SUCCESSFUL in 8m 5s). The first run failed: `check` reported 3926 tests, 1 failed. The failing test was `EventsStreamTests > the event data is the ChangeEvent as JSON with an ISO-8601 timestamp()` (core/events, outside this task): the SSE read stopped early (`"op":"UPDAT`). Run alone (`./gradlew test --tests '*EventsStreamTests'`), it passed, and it also passed in the second full run. It is a timing-dependent flaky test, not related to this task. The parent may want to report it separately. Web and Android were not run because the task changes only server files.

## .taskcheck

Result:

Not run: this sub-agent was not allowed to run `sh scripts/check.sh`. The server part of it (ktlintCheck plus the full server test suite) passed as shown above. The parent worker must run `.taskcheck`.

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. `MailBackupContributor.kt` was added by this task (3ec522fd, extended by 24a2beb3), so all of its executable lines are task lines, and whole-file line coverage is 228/228.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: backup-contrib-mail; base 06232336fb2d4b66ba1e6ece629c6dc55d225f1e; HEAD 24a2beb3929f8b34c0b6e9ecc12c2ffc326311d6; task commits 3ec522fd, 5e2f25a7 and 24a2beb3, as given by the parent and matching the task's `touches`. `git diff --name-only 06232336..HEAD -- server/src/main web/src android/app/src/main` lists only `MailBackupContributor.kt`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 24a2beb3 plus the uncommitted `MailBackupContributorTests.kt` (sha256 21e8716a0267988e505d681feae9fa9388d6a9b0636a70e80ebad9bf26882aee). `MailBackupContributor.kt` sha256 8b2daf29d9f68b46db8a711cbde9c323f3fd94f241480c07d8de563031934f07; `MailBackupFixture.kt` sha256 47cca3d7887fec002e1c105a2b5a75d3e4c34a00cb04fdac4b0034b1eceb4a24.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/mail/backup/MailBackupContributor.kt`. Excluded: `MailBackupFixture.kt` and `MailBackupContributorTests.kt` (test code), `reviews/` and `tasks/` (documentation). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (Gradle 9.8.1 wrapper, plugin default tool version); `./gradlew test jacocoTestReport` in `server/`; the full server test suite.
- Run start/completion time and exit status: 2026-10-21T23:52:11+02:00 to 2026-10-22T00:00:16+02:00, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` (mtime 2026-10-22 00:00:16, sha256 008885114e449fb1d7cb4562d9f89172809e5ee50fd0a9a5cffcdd298ac9a100, sessioninfo id dell26-62b765f).
- Evidence outputs are fresh and inputs stayed unchanged: the report was rewritten by this run (the earlier report was from 23:29, before this run). The source and test hashes were the same before and after the run. The first full run failed, and its coverage was not used.
- Final run after latest test additions and production fixes: yes. It ran after all test edits. Production code was not changed during this run.

Values below use covered/total (percentage) from JaCoCo `<sourcefile>` counters.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/backup/MailBackupContributor.kt` | 1231/1231 (100%) | 44/44 (100%) | 73/73 (100%) | 228/228 (100%) |

Aggregate over the scope (one file, no unmeasured files): the same as the row above.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| None | None | No uncovered lines or branches |

## Unable to test

- CR-1 behavior at runtime: a linked mail older than the sync window is not reattached after a restore. This needs an IMAP sync against a server, and the sync-side fix is deferred to task mail-restore-retake-linked. This task tests only the restore warning. Follow-up: mail-restore-retake-linked.
- `.taskcheck` as a whole: not run here because permission was denied (see above). The parent must run it.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The outdated warnings test now matches the CR-1 warning, and two new tests check when the warning appears. All focused tests and the full server suite pass. The full suite passed on the second run; the first run hit a flaky `EventsStreamTests` failure outside this task. `.taskcheck` must still be run by the parent.
