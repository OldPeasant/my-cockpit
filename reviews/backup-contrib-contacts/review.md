# Code Review

Date: 2026-10-22

Task: backup-contrib-contacts - Backup contributor: contacts

Scope: `git diff 315ed79a..1d77bdf4 -- server` (the task's single implementation commit), committed changes only

Requirements:
- tasks/backup-contrib-contacts.md (acceptance criteria and the two recorded assumptions)
- SPEC.md §8.3 (contact model), §10.2 (zip format, contributors, external ids kept), §12 (round trip), §14
- docs/ARCHITECTURE.md and AGENTS.md project notes (module boundaries, JDBC restore convention, lint, logging)
- BackupContributor contract (server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupContributor.kt), BackupFixture contract
- Sibling contributors for conventions: notes/backup, calendar/backup, files/backup, core/backupcontrib

Git range:
- Branch: main
- HEAD: 6cf1804e2a4feec3380fb7ff02538a94aaced2f0 (task commit 1d77bdf485067b48e0503db2d168f427400edbcd plus task-state commits)
- Base/start: 315ed79a9ba4057d6ab7a8ba80e1fe6e544ce647
- Commits reviewed: 1 (1d77bdf4)

Summary: 0 P0, 1 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Nothing tests that readable/contacts/contacts.vcf parses back, or what goes into contacts.jsonl**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/backup/ContactsBackupContributor.kt:62`
    - Category: Tests
    - Problem: Criterion 1 says "readable/contacts/contacts.vcf (parses back)". The commit adds only `ContactsBackupFixture`. `BackupRoundTripTest` compares just `data/` and `files/` (BackupRoundTripTest.kt, `export()` reads only `DATA_DIR` and `FILES_DIR` entries). So the mirror runs but nobody checks its output. Nothing asserts that the vcf parses with ez-vcard, that cards in the Trash are left out, that cards are sorted by name and use the contact id as UID, that a photo becomes a `PHOTO` URI `../../files/<percent-encoded path>` pointing to an entry that exists in `files/`, or that no file is written when there are no active contacts. Nothing checks that external and trashed contacts are in `contacts.jsonl` with their `externalId`, or that import indexes only active contacts for search. Each sibling module has a `*BackupContributorTests` for this (notes, calendar, files, gtd, mail). Contacts has none at the reviewed revision.
    - Why it matters: The parse-back half of criterion 1 has no evidence. The round trip would still pass if the mirror were empty, malformed, or had broken photo links.
    - Suggested fix: Add `ContactsBackupContributorTests`, modelled on `NotesBackupContributorTests`. It should export a backup, parse `readable/contacts/contacts.vcf` with `Ezvcard.parse`, and assert the points above, including that each photo URI resolves to a `files/` entry of the same zip. The parallel test-writer may already be adding this. If so, check that it covers the parse-back before recording criterion 1 as verified.

## P2 Medium

None.

## P3 Low

- [ ] **CR-2 | Copies helpers that siblings share instead of reusing them**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/backup/ContactsBackupContributor.kt:96`, `:131`, `:176`
    - Category: Quality
    - Problem: The contributor calls `Timestamp.from`, `rs.getObject(..., UUID::class.java)` and `rs.getTimestamp(...)?.toInstant()` inline. The notes, calendar and mail contributors use the shared row helpers in `core/backupcontrib/BackupRows.kt` (`uuid`, `instant`, `instantOrNull`, `toTimestamp`, `toTimestampOrNull`). The private `encoded(path)` is a copy of `NotesBackupContributor.encoded` (NotesBackupContributor.kt:246).
    - Why it matters: Small drift from the sibling convention. Any fix to the percent-encoding of `../../files/` links now has to be made in two places.
    - Suggested fix: Use the `BackupRows.kt` helpers. Optionally, move the path encoding next to `FilesBackupContributor.paths` (for example a function that returns the encoded relative link) so notes and contacts share it. That second part changes files outside this task's `touches`, so it can be a follow-up.

## Review conclusion

Material issues found; fix before completing the task.

Notes from the review (no findings):
- The contributor contract is met. `export` streams `data/contacts.jsonl` with all columns. `readable` writes one vCard 3.0 file through the same `VCardService.toVCard` the export uses, with `prodId` off as in `VCardService.export`. `import` inserts with JDBC and keeps ids, times and versions. `dependsOn` is not needed because `photo_file_id` has no foreign key (V202610211000__contacts_model.sql).
- SPEC §10.2 "external ids are kept so links survive" holds: `source` and `external_id` are exported and restored. Both recorded assumptions are visible in the code and documented in the KDoc.
- Making `VCardService.toVCard` public changes only its visibility (outside `touches`, recorded as an assumption).
- No secrets, personal data or contact content are logged. The fixture uses fictitious `example.test` data.
- `server/build/test-results/test/TEST-ch.sonensei.cockpit.backup.BackupRoundTripTest.xml` (2026-10-22T02:20:56Z, shortly before the commit) shows the round trip passing: 1 test, 0 failures. That is consistent with criterion 2. The parent should still record its own run as evidence.
