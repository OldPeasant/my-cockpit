# Code Review

Date: 2026-10-22

Task: backup-contrib-contacts - Backup contributor: contacts

Scope: `git diff 47970abfe527952cad61dc4703aac682835a7423..e08bbc45 -- server` (committed changes only). Reviewed revision: e08bbc45. This review covers the whole task. Commits 07a746d1..bbb94860 were reviewed earlier, after the fixes for the earlier CR-1 and CR-2 (`reviews/backup-contrib-contacts/fixes.md`). At that time they were based on 5708c4f2 with HEAD 02b2368b, which is the same patch. This pass adds the delta `git diff bbb94860 e08bbc45 -- server` (one added test, see "Delta review").

Requirements:
- `tasks/backup-contrib-contacts.md` (acceptance criteria and the two recorded assumptions)
- `SPEC.md` §8.3 (contact model, vCard export), §10.2 (backup layout, `BackupContributor`, external ids kept), §14 (backup/restore round trip for every entity)
- `docs/ARCHITECTURE.md`, `AGENTS.md` project notes
- The `BackupContributor` contract (`backup/domain/BackupContributor.kt`), `core/backupcontrib/BackupRows.kt`, and the sibling contributors (notes, files, links, reminders, mail, calendar)

Git range:
- Branch: main
- HEAD: e08bbc45
- Base/start: 47970abf
- Commits reviewed: 4 (07a746d1 implementation, 9e9598e3 contributor tests, bbb94860 CR-2 fix, e08bbc45 added test by the test-writer)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Delta review (bbb94860..e08bbc45)

The only code change is one new test in `server/src/test/kotlin/ch/sonensei/cockpit/contacts/backup/ContactsBackupContributorTests.kt:181-196`. Production code did not change: `git diff 47970abf e08bbc45 --stat` lists the same production files as before (`ContactsBackupContributor.kt`, `VCardService.kt`), and none of them is in the delta. The test:
- seeds one photo file without an extension (`portrait`) and one with an unknown extension (`alex.unknownext`);
- checks that both cards get a `PHOTO` URI `../../files/...` that resolves to the file's path in the backup;
- checks that the extension-less photo has no content type. This matches `ImageType.find(null, null, "")` at `ContactsBackupContributor.kt:88`, which returns null for an empty extension.

The order assertion (Alex, then Blake) follows the mirror's sort by name. All the matchers it uses are already imported. The test checks behavior, not implementation details, and mocks nothing. No findings in the delta.

## Acceptance criteria check

1. `data/contacts.jsonl`; `readable/contacts/contacts.vcf` (parses back): met in the code.
   - `export` streams every contact row (Trash and external sources included) into `data/contacts.jsonl`, in id order, with all columns of the V202610211000 schema.
   - `readable` writes one vCard 3.0 file with the contacts not in the Trash, sorted by name then id. It uses the same `VCardService.toVCard` as the vCard export and adds photos as `PHOTO` URIs `../../files/` + encoded path, using the paths from `FilesBackupContributor.paths`. The writer is flushed and not closed, and no file is written when there is no active contact.
   - `ContactsBackupContributorTests` parses the file back with ez-vcard and checks the fields, the order, UIDs and that each photo URI resolves to an entry in the zip. This includes names with spaces, brackets and non-ASCII characters, names with no extension or an unknown one, and missing files.
2. Round trip with a `BackupFixture` that seeds contacts with photos: met in the code. `ContactsBackupFixture` seeds:
   - two contacts with drive-file photos (one name with spaces and brackets);
   - a contact with every field set;
   - a minimal contact;
   - a contact in the Trash;
   - an external GOOGLE contact with an external id.

   Its `contributor` id matches `ContactsBackupContributor.ID`.

Import re-inserts the rows through JDBC with their ids, times, versions, Trash state, source and external id, as the sibling contributors do. It indexes the contacts not in the Trash through the existing `ContactService.indexContact`. Contacts have no foreign keys to other modules (`photo_file_id` is no FK by design), so an empty `dependsOn` is correct. The contributor uses the shared `BackupRows` helpers (`uuid`, `instant`, `instantOrNull`, `toTimestamp`, `toTimestampOrNull`).

The only change outside `touches` is `VCardService.toVCard` going from private to public. That is the second assumption in the task file, and the change is minimal.

Test run by the reviewer at e08bbc45: `cd server && ./gradlew test --tests '*ContactsBackupContributorTests'` -> BUILD SUCCESSFUL. The JUnit XML (2026-10-22T03:02:59Z) shows 10 tests, 0 skipped, 0 failures, 0 errors, and includes the new test. The reviewer did not re-run `BackupRoundTripTest` in this pass. Its production inputs have not changed since the earlier review, and the parent worker records criterion 2 from its own run.

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
    - Problem: `encoded(path)` is the same code as `NotesBackupContributor.encoded` (`server/src/main/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributor.kt:246`). Both build `../../files/` + encoded path links from `FilesBackupContributor.paths`. Sharing it would mean editing files outside this task's `touches`, so the fixer deliberately left it in this module (`fixes.md`). That is acceptable for this task.
    - Why it matters: If the encoding rule for links into `files/` changes (for example, which characters to escape), it has to be changed in two places. The two mirrors could then produce links that do not match each other.
    - Suggested fix: In a follow-up task that touches the files module, add a helper next to `FilesBackupContributor.paths` that returns the link `files/` + encoded path, and use it from both contributors. Nothing to change in this task.

## Review conclusion

No material issues found.
