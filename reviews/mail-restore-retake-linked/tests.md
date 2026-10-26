# Test Report

Date: 2026-10-26

Task: mail-restore-retake-linked - Mail sync: retake linked/reminded refs on the first sync after a restore

Scope: base `724fe48be72330f0873108be3d17df5970e59e6c`; implementation `29d496e04da417a21f217d34ec081160e41553d4` (`git diff 724fe48be..29d496e04 -- server`); final revision with the tests `8e82f4e45923f3ea9c9f281ed447b1f4f1495c7e`. The parent worker named the base and the implementation commit; I checked both against the task's acceptance criteria and `touches`. The production diff `724fe48be..8e82f4e45 -- server/src/main` has the same five files and hunks as the implementation commit. After a rebase the current HEAD is `e41d187aa73f52c1368bd0eb09c6c7f6a416db7d`, and `git diff 8e82f4e45 e41d187aa -- server` is empty, so the measured code is the code at HEAD. Uncommitted work: only this report.

Requirements:
- `tasks/mail-restore-retake-linked.md` acceptance criteria and its recorded assumption (fired reminders count too; reconcile detaches reminder-only vanished messages; pruneDetached runs only on a UIDVALIDITY resync)
- SPEC.md §10.2 ("links survive"), cited by the task goal
- `docs/ARCHITECTURE.md` "Coverage" and the AGENTS.md project notes
- Existing test conventions: `MailHeaderSyncServiceTests` with a scripted `FakeRemoteFolder`, `MailHeaderStoreTests`, `MailBackupContributorTests` "restore, then sync", `ReminderPendingOfItemTests`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncServiceTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/backup/MailBackupContributorTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/reminders/ReminderPendingOfItemTests.kt`

The implementation commit had already added or updated four kinds of test:
- `MailHeaderSyncServiceTests` "the first sync after a restore takes over linked refs older than the window by Message-ID and prunes nothing";
- `MailHeaderStoreTests` "a reminder alone keeps a detached row through pruneDetached and vanishing, and its Message-ID is retaken";
- the three restore-warning tests in `MailBackupContributorTests`.

I reviewed them. They match the criteria, use fictitious data, and assert observable results (IMAP calls, upserted UIDs, counts, rows). I left them unchanged.

## Tests added or updated

Count: 3 added. No existing test was changed. The `remote(...)` helper in `MailBackupContributorTests` got an optional `receivedAt` parameter whose default is the old value, so existing callers behave as before.

- `MailHeaderSyncServiceTests`: "a first sync without linked or reminded detached rows does not search by Message-ID". The retake now runs on every first sync. On a normal first sync with no detached refs, the test checks:
  - the IMAP calls are still only `uidsSince` and `headers`;
  - `locatedUids` is not read;
  - the result is `created=2`.
- `MailBackupContributorTests`: "the first sync after a restore takes linked and reminder-only refs older than the window over by Message-ID". This runs end to end on Postgres.
  - Setup: export, wipe, mail import. Then the test inserts the link and reminder rows again, as their own contributors do in a full restore.
  - Before the sync, it checks that the restored folders have `uidvalidity` null, and that the real `MailHeaderStore.linkedDetachedMessageIds` lists the linked ref (INBOX) and the reminder-only ref (Google All Mail).
  - It then runs a real `MailHeaderSyncService` on both folders: real store and repositories, a scripted `RemoteMailbox` whose `SEARCH SINCE` compares arrival days, and a clock fixed at 2026-10-26. Both messages are older than their account's window (6 and 12 months).
  - Assertions:
    - results `created=1, updated=1, removed=0` (INBOX) and `created=0, updated=1, removed=0` (All Mail);
    - the Message-ID searches that were made;
    - LINKED at UID 23 in INBOX and REMINDED at UID 5;
    - `MailMessageLocator.current` finds both;
    - no duplicate row;
    - the folder state is recorded.
- `ReminderPendingOfItemTests`: "hasReminders counts fired and pending reminders of exactly that item, and none once they are deleted". This covers `ReminderService.hasReminders` and `ReminderRepository.existsByItemTypeAndItemId`:
  - a fired reminder counts, and so does a pending one;
  - a NOTE with the same id as a reminded TASK does not count;
  - an item without reminders does not count;
  - after the reminders are deleted, the result is false.

## Requirement coverage

Command (run by the parent worker on revision `8e82f4e45923f3ea9c9f281ed447b1f4f1495c7e`; I checked the JUnit XML and the JaCoCo XML it produced):

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.mail.sync.MailHeaderSyncServiceTests' --tests 'ch.sonensei.cockpit.mail.sync.MailHeaderStoreTests' --tests 'ch.sonensei.cockpit.mail.backup.MailBackupContributorTests' --tests 'ch.sonensei.cockpit.core.reminders.ReminderPendingOfItemTests' jacocoTestReport`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: the first full sync (uidvalidity null) also runs `retakeLinked`, tested with a linked detached ref older than the window | `MailHeaderSyncServiceTests`: "the first sync after a restore takes over linked refs older than the window by Message-ID and prunes nothing" (existing) and "a first sync without linked or reminded detached rows does not search by Message-ID" (new). `MailBackupContributorTests`: "the first sync after a restore takes linked and reminder-only refs older than the window over by Message-ID" (new) | focused command above | Pass |
| AC1, unchanged behavior: pruneDetached and detachFolder run only on a resync | the existing first-sync test (asserts no detach or prune), the existing restore first-sync test, "a changed UIDVALIDITY detaches, resyncs and prunes the folder", "a resync looks up linked rows the window missed by Message-ID before pruning" | focused command above | Pass |
| AC2: `isLinked` also counts reminders (EMAIL), so pruneDetached keeps reminder-only refs and linkedDetachedMessageIds lists them | `MailHeaderStoreTests`: "a reminder alone keeps a detached row through pruneDetached and vanishing, and its Message-ID is retaken" (existing). `ReminderPendingOfItemTests`: "hasReminders counts fired and pending reminders of exactly that item, and none once they are deleted" (new). `MailBackupContributorTests`: the new restore first-sync test (reminder-only ref in All Mail) | focused command above | Pass |
| AC3: the restore warning about older linked emails is removed | `MailBackupContributorTests`: "restore warnings name the refetch and the IMAP passwords to enter again, not the linked mails the first sync retakes", "a backup with accounts but no linked or reminded mails has the same warnings", "a reminder on a mail adds no warning" (updated in the implementation commit) | focused command above | Pass |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.mail.sync.MailHeaderSyncServiceTests' --tests 'ch.sonensei.cockpit.mail.sync.MailHeaderStoreTests' --tests 'ch.sonensei.cockpit.mail.backup.MailBackupContributorTests' --tests 'ch.sonensei.cockpit.core.reminders.ReminderPendingOfItemTests' jacocoTestReport`

The parent worker ran it, because this sub-agent may not run Gradle.

Result:

Pass. BUILD SUCCESSFUL and ktlint clean (as reported by the parent worker). I read the JUnit XML in `server/build/test-results/test/` myself:

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `ReminderPendingOfItemTests` | 3 | 0 | 0 | 0 |
| `MailBackupContributorTests` | 21 | 0 | 0 | 0 |
| `MailHeaderStoreTests` | 25 | 0 | 0 | 0 |
| `MailHeaderSyncServiceTests` | 44 | 0 | 0 | 0 |

Suite timestamps: 2026-10-26T03:11:28Z to 03:12:06Z.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Not run by me or reported to me: this sub-agent may not run Gradle. The parent worker runs `.taskcheck` before `done`.

## .taskcheck

Result:

Not run by me (`sh scripts/check.sh`; same reason). The parent worker must run it.

## Bugs found

None. All focused tests pass, including the three new ones.

## Coverage

Status: verified for the focused run (the four test classes above), not for the full server suite.

Metric: whole-file coverage of task-changed production files. Pre-existing code in those files counts too. `ReminderService.kt` is low because only `ReminderPendingOfItemTests` of the reminder tests ran.

Changed-line coverage: 6/6 executable changed lines (100%) and 6/6 branches on those lines.
- Method: I intersected the added/modified line ranges of `git diff -U0 724fe48be 8e82f4e45 -- server/src/main` with the JaCoCo `<line>` entries. Removed lines and lines without bytecode (comments, imports, doc) are not counted.
- The lines:
  - `ReminderService.kt:95`;
  - `MailHeaderStore.kt:39` (the new `reminders` constructor parameter), `:178`, `:179` (4/4 branches);
  - `MailHeaderSyncService.kt:288`, `:289` (2/2 branches).
- `MailBackupContributor.kt` has only a doc change and a removed block, so it has no changed executable lines. `ReminderRepository.kt` is an interface with no bytecode.

Evidence:
- Task id, immutable base, measured revision, and source of the task scope: `mail-restore-retake-linked`; base `724fe48be72330f0873108be3d17df5970e59e6c`; measured revision `8e82f4e45923f3ea9c9f281ed447b1f4f1495c7e`, which has the same server tree as HEAD `e41d187aa73f52c1368bd0eb09c6c7f6a416db7d`. The parent worker gave the implementation commit `29d496e04` and its production files; I checked them with `git diff --stat`.
- Source/test snapshot: commit `8e82f4e45`. Its blobs match the test files I wrote: `ReminderPendingOfItemTests.kt` `1370b22a118474fe8704066313afca6611062bf0`, `MailBackupContributorTests.kt` `2355564a4d993cd6058186b61c4b272290520996`, `MailHeaderSyncServiceTests.kt` `61b9b66890ba59b6a8bb192c0cc6adf82bce7bcc`.
- Exact included files:
  - `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt`
  - `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderStore.kt`
  - `server/src/main/kotlin/ch/sonensei/cockpit/mail/backup/MailBackupContributor.kt`
  - `server/src/main/kotlin/ch/sonensei/cockpit/core/reminders/domain/ReminderService.kt`
  - `server/src/main/kotlin/ch/sonensei/cockpit/core/reminders/infra/ReminderRepository.kt` (a Spring Data interface; JaCoCo has no `<sourcefile>` for it, so N/A)
  - Excluded: `tasks/*.md` (task board files). No renames or deletions.
- Provider and setup: JaCoCo (Gradle `jacoco` plugin), run with the focused command above in `server/`, with the four test classes selected.
- Run time and exit status: tests ran 2026-10-26T03:11:28Z to 03:12:06Z (JUnit XML). The report was written at 2026-10-26T03:12:10Z (04:12:10 +0100). The parent worker reported exit status BUILD SUCCESSFUL.
- Report: `server/build/reports/jacoco/test/jacocoTestReport.xml`, git blob hash `340cd598e491d57bee9ac121bfeef2e568910c57`. The numbers below are from this file and match the parent worker's numbers.
- Freshness: the report is newer than every JUnit result of the run. The measured test files equal the committed revision, and no production or test file changed after the run (the working tree holds only this report).
- Final run after the latest test additions and production fixes: yes. No production fix was needed after these tests.

Values below are covered/total (percentage) from the JaCoCo counters. Statements are JaCoCo INSTRUCTION counters, and functions are METHOD counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt` | 1310/1321 (99.2%) | 103/104 (99.0%) | 34/37 (91.9%) | 176/176 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderStore.kt` | 770/807 (95.4%) | 52/56 (92.9%) | 25/26 (96.2%) | 134/135 (99.3%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/backup/MailBackupContributor.kt` | 1222/1222 (100%) | 42/42 (100%) | 73/73 (100%) | 226/226 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/reminders/domain/ReminderService.kt` | 201/343 (58.6%) | 4/12 (33.3%) | 13/20 (65.0%) | 38/50 (76.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/reminders/infra/ReminderRepository.kt` | N/A (interface) | N/A | N/A | N/A |

Aggregate over the four measured files (sums of counters): statements 3503/3693 (94.9%), branches 201/214 (93.9%), functions 145/156 (92.9%), lines 574/587 (97.8%). `ReminderRepository.kt` has no bytecode and is not in the aggregate. The aggregate covers only the focused test selection; it is not a full-suite number.

Uncovered lines and branches, and what I did about them:

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailHeaderSyncService.kt:288-289`, `:305` | AC1: retake on every full sync, prune only on resync, early return without refs | Fully covered (289: 2/2 branches, 305: 2/2), by the existing tests and the new no-refs and end-to-end tests |
| `MailHeaderSyncService.kt:38`, `:44`, `:48` (partly missed instructions) and `:337` (1 of 2 branches) | Pre-existing data-class defaults and the incremental path; not changed by this task | Left uncovered: outside the task's changes |
| `MailHeaderStore.kt:178-179`, `:39` | AC2: `hasLinks \|\| hasReminders` | Fully covered (179: 4/4 branches) |
| `MailHeaderStore.kt:50` (`locatedIds`), `:101` (1 of 2 branches), `:256` (3 of 6 branches) | Pre-existing code not changed by this task; other test classes outside the focused run exercise `locatedIds` | Left uncovered: outside the task's changes |
| `ReminderService.kt:95` | AC2: `hasReminders` | Covered (new `ReminderPendingOfItemTests` test) |
| `ReminderService.kt:67`, `:75`, `:84`, `:91`, `:110`, `:129-130`, `:142-152` | Pre-existing reminder create/get/delete/upcoming/fire/item-deleted code. These lines are uncovered only because the focused run left out the other reminder test classes (`ReminderServiceTests`, `ReminderServiceEdgeCaseTests`, `ReminderSchedulerTests`, ...) | Left uncovered in this run: not changed by this task. A full-suite run would cover them |

## Unable to test

- I did not run the full server suite (`./gradlew check`) or `.taskcheck`, because this sub-agent may not run Gradle. Follow-up: the parent worker runs `sh scripts/check.sh` before `done`.
- The restore-then-first-sync flow against a real IMAP server (Dovecot Testcontainers, `HeaderSyncDovecotTests`): not added. The scripted mailbox in the new end-to-end test models `SEARCH SINCE` by day and the substring Message-ID search, as the unit tests do. A Dovecot run would be a slow suite, and no criterion names it. No follow-up needed.
- A full `BackupService.restore` (all contributors in one run) followed by a sync: not used. The existing "restore, then sync" pattern runs only the mail import, so the new test inserts the link and reminder rows itself. No follow-up needed.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. I added three tests, and all 93 tests in the four focused classes pass with ktlint clean. Every changed executable line and branch is covered. The full suite and `.taskcheck` are still for the parent worker to run.
