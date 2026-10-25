# Code Review

Date: 2026-10-25

Task: backup-contrib-journal - Backup contributor: journal and habits

Scope: `git diff 7f19d160f..96d5fe1e5 -- server/` (task commits `c39677daa` implementation and fixture, merge `d07a56f9e`, `96d5fe1e5` tests); committed changes only

Requirements:
- `tasks/backup-contrib-journal.md` acceptance criteria 1 and 2
- `SPEC.md` §8.5 (journal and habits), §10.2 (zip format, `BackupContributor`), §12 (round-trip test), §14 (every entity covered by a contributor and the round-trip test)
- `docs/ARCHITECTURE.md`, `AGENTS.md` project notes, `docs/QUALITY.md`
- Sibling contributors for conventions: `NotesBackupContributor`, `GtdBackupContributor`, `FilesBackupContributor`, `core/backupcontrib/*`
- Journal and habits migrations (`db/migration/journal/*.sql`), `JournalEntryService`, `HabitService`

Git range:
- Branch: main
- HEAD: 96d5fe1e5d1d6e971d253f8ed78a4dcfb5e90961
- Base/start: 7f19d160fe030258f4257f77cad69f8ae903e7ad
- Commits reviewed: 3 (`c39677daa`, merge `d07a56f9e`, `96d5fe1e5`); working tree clean

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Invisible literal characters in the YAML escape condition**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/journal/backup/JournalBackupContributor.kt:312`
    - Category: Quality
    - Problem: The last three comparisons of the condition (`c == ..., c == ..., c >= ...`) hold the raw characters U+2028, U+2029 and U+FFFE inside the char literals (confirmed with `od -c`: bytes `342 200 250`, `342 200 251`, `357 277 276`). In editors, diffs and review tools they look like spaces or line breaks. The identical function in `NotesBackupContributor` writes them as backslash-u escapes (backslash-u2028, backslash-u2029, backslash-ufffe). The behavior is correct and is covered by the YAML test, so this is a readability issue only.
    - Why it matters: A reader cannot tell what the check does. A later edit, a formatter or an editor that normalizes line separators could change the characters without anyone noticing.
    - Suggested fix: Use the `\uXXXX` escapes as `NotesBackupContributor` does.

- [ ] **CR-2 | Mirror helpers copied verbatim from the notes contributor**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/journal/backup/JournalBackupContributor.kt:222`, `:226`, `:303`, `:321`, `:369`
    - Category: Quality
    - Problem: `streamed`, `links` (apart from the item type), `ref`, `quoted`, `encoded`, the `IMAGE` regex and `ResultSet.strings` are copies of the private helpers in `NotesBackupContributor`. CR-1 shows the copies have already started to drift. Shared backup row helpers already live in `core/backupcontrib/BackupRows.kt` (`uuid`, `instant`, `toTimestamp`).
    - Why it matters: A fix to the YAML escaping, the link-path encoding or the linked-items query has to be made in two places, or more as other contributors copy the code. The two Markdown mirrors would then disagree.
    - Suggested fix: This is optional and can be a follow-up. It may belong to a separate task, because it touches `core/backupcontrib`, which is outside this task's `touches`. Move the format helpers (`quoted`, `encoded`, the `cockpit-file` regex, linked items by type, `strings`, `streamed`) next to `BackupRows.kt` and use them from both contributors.

## Notes (verified, no finding)

- AC1: the contributor writes `data/journal.jsonl` (every entry, Trash included, tags sorted), `data/habits.json` (archived habits included, `reminder_id` kept) and `data/habit_checks.jsonl`. It also writes `readable/journal/YEAR/YEAR-MONTH-DAY.md` for each active entry and `readable/habits.csv`. The unique partial index `journal_entry_date` makes the date alone a safe file name. Image links go `../../../files/...`, which is correct from `readable/journal/YEAR/`.
- Restore: it keeps ids, `created_at`, `updated_at`, `version`, `deleted_at` and `reminder_id`, using JDBC as the notes contributor does. Search documents are rebuilt with the same functions the domain uses: `indexJournalEntry` for entries, and `upsert(HABIT, id, name, null)` as in `HabitService.changed`. Entries in the Trash are not indexed, which matches the Trash behavior.
- Ordering: `dependsOn` is not needed. `habit.reminder_id` has no foreign key (migration `V202610212300`), and `journal_entry_tag` and `habit_check` are inserted after their parents within this contributor.
- CSV: the mirror follows RFC 4180 quoting and guards against formula injection. YAML front matter quoting matches the notes mirror.
- AC2: `JournalBackupFixture` seeds an entry with an image, a mood, unicode tags and an inline link, plus an empty entry, a long entry and an entry in the Trash. It also seeds habits of all three schedules (one with a reminder, one archived) and checks with counts above 1. This makes every data name non-empty, as `BackupRoundTripTest` requires. The test report records `./gradlew test --tests '*BackupRoundTripTest' --tests '*JournalBackupContributorTests' --rerun` -> pass.
- Tests: 16 focused tests cover data contents, the mirrors, escaping, the empty case and restore, including the search index. The test report gives 100% line coverage for the changed production file. No secrets or real personal data are in the diff. The acceptance criteria have no `tasks.py verify` evidence yet. Recording it is the parent worker's next step, not a code defect.

## Review conclusion

No material issues found.
