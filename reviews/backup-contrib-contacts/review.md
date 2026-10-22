# Code Review

Date: 2026-10-22

Task: backup-contrib-contacts - Backup contributor: contacts

Scope: `git diff 5708c4f251afba4e3c8f09be21e1721233839f85..02b2368b -- server` (committed changes only). Reviewed revision: 02b2368b. This is a re-review after the fixes for the earlier CR-1 and CR-2 (`reviews/backup-contrib-contacts/fixes.md`).

Requirements:
- `tasks/backup-contrib-contacts.md` (acceptance criteria and the two recorded assumptions)
- `SPEC.md` §8.3 (contact model, vCard export), §10.2 (backup layout, `BackupContributor`, external ids kept), §14 (backup/restore round trip for every entity)
- `docs/ARCHITECTURE.md`, `AGENTS.md` project notes
- The `BackupContributor` contract (`backup/domain/BackupContributor.kt`), `core/backupcontrib/BackupRows.kt`, and the sibling contributors (notes, files, links, reminders, mail, calendar)

Git range:
- Branch: main
- HEAD: 02b2368b
- Base/start: 5708c4f2
- Commits reviewed: 3 (28d327fe implementation, 8ab11d57 contributor tests, 02b2368b CR-2 fix)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Acceptance criteria check

1. `data/contacts.jsonl`; `readable/contacts/contacts.vcf` (parses back): met in the code.
   - `export` streams every contact row (Trash and external sources included) into `data/contacts.jsonl`, in id order, with all columns of the V202610211000 schema.
   - `readable` writes one vCard 3.0 file with the contacts not in the Trash, sorted by name then id. It uses the same `VCardService.toVCard` as the vCard export and adds photos as `PHOTO` URIs `../../files/<encoded path>`, using the paths from `FilesBackupContributor.paths`. The writer is flushed and not closed, and no file is written when there is no active contact.
   - `ContactsBackupContributorTests` parses the file back with ez-vcard and checks the fields, the order, UIDs and that each photo URI resolves to an entry in the zip, including names with spaces, brackets and non-ASCII characters, unknown extensions and missing files.
2. Round trip with a `BackupFixture` that seeds contacts with photos: met in the code. `ContactsBackupFixture` seeds two contacts with drive-file photos (one name with spaces and brackets), a contact with every field set, a minimal one, one in the Trash and an external GOOGLE contact with an external id. Its `contributor` id matches `ContactsBackupContributor.ID`.

Import re-inserts the rows with their ids, times, versions, Trash state, source and external id through JDBC, as the sibling contributors do. It indexes the contacts not in the Trash through the existing `ContactService.indexContact`. Contacts have no foreign keys to other modules (`photo_file_id` is no FK by design), so an empty `dependsOn` is correct. The earlier CR-2 is fixed: the contributor now uses the shared `uuid`/`instant`/`instantOrNull`/`toTimestamp`/`toTimestampOrNull` helpers. The earlier CR-1 is fixed: there are 9 focused contributor tests.

The only change outside `touches` is `VCardService.toVCard` going from private to public. That is the second assumption in the task file, and the change is minimal.

Test run by the reviewer: `cd server && ./gradlew test --tests '*ContactsBackupContributorTests'` -> BUILD SUCCESSFUL (10 tests, 0 failures; the 10th test is an uncommitted addition in the working tree, outside this review's scope). The reviewer's runs of `--tests '*BackupRoundTripTest'` failed inside Gradle with `EOFException` / `NoSuchFileException .../build/test-results/test/binary/in-progress-results-generic.bin`. Another Gradle test run was using the same build directory at the same time, so this is not a test failure. The newest JUnit XML for `BackupRoundTripTest` in `server/build/test-results` (timestamp 2026-10-22T02:48:20Z, from that parallel run) shows 1 test, 0 failures, 0 errors. Neither criterion has been recorded with `tasks.py verify` yet. The parent worker must record both from a clean, non-concurrent run.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Path percent-encoding copied from the notes contributor**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/backup/ContactsBackupContributor.kt:180`
    - Category: Quality
    - Problem: `encoded(path)` is the same code as `NotesBackupContributor.encoded` (`server/src/main/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributor.kt:246`). Both build `../../files/<path>` links from `FilesBackupContributor.paths`. Sharing it would mean editing files outside this task's `touches`, so the fixer deliberately left it in this module (`fixes.md`). That is acceptable for this task.
    - Why it matters: If the encoding rule for links into `files/` changes (for example, which characters to escape), it has to be changed in two places. The two mirrors could then produce links that do not match each other.
    - Suggested fix: In a follow-up task that touches the files module, add a helper next to `FilesBackupContributor.paths` that returns the link `files/<encoded path>`, and use it from both contributors. Nothing to change in this task.

## Review conclusion

No material issues found.
