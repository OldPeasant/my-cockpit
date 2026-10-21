# Code Review

Date: 2026-10-21

Task: backup-contrib-gtd - Backup contributor: GTD

Scope: The whole task diff `be75ae8171a396588d0629a807ec5398220ea941..fe59496af52950d17170aa8ae0aaff17da96b404`: the three "backup-contrib-gtd:" commits 1f911e1e, ea5dfd0a and fe59496a. This is a re-review of the final code after the fixer's changes. It checks whether CR-1, CR-2 and CR-3 from the earlier review are resolved. Only committed code was reviewed, and the working tree was clean.

Requirements:
- tasks/backup-contrib-gtd.md: acceptance criteria 1-3 and the three recorded assumptions
- SPEC.md §5.1, §5.2, §5.3, §10.2, §14
- AGENTS.md task protocol, quality gate and project conventions
- Contracts: `backup/domain/BackupContributor.kt`, `BackupRoundTripTest.kt`, `BackupFixture.kt`, `files/backup/FilesBackupContributor.kt` (pattern for search re-indexing)
- Schema: `db/migration/gtd/V202610201311__gtd_model.sql`, `V202610201830__gtd_task_next_occurrence.sql`
- reviews/backup-contrib-gtd/fixes.md (the fixer's report)

Git range:
- Branch: main
- HEAD: fe59496af52950d17170aa8ae0aaff17da96b404
- Base/start: be75ae8171a396588d0629a807ec5398220ea941 (`git merge-base HEAD origin/main`, which equals origin/main)
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Status of the earlier findings:
- CR-1 (no readable-mirror tests): resolved. `GtdBackupContributorTests.kt` checks the exact Markdown of inbox, next (with the Scheduled section, soonest first), waiting, someday and logbook (newest first, Trash excluded). It also checks project mirrors: fields, description, checkboxes, the next-step marker, done tasks last, `No area`, trashed projects left out, case-insensitive clash numbering, separators and control characters, leading dots, empty names giving `Untitled`, the 100-code-point cut and the emoji cut. It also covers the empty-GTD export.
- CR-2 (Windows-invalid characters): resolved. `FILE_NAME_FORBIDDEN` (`GtdBackupRecords.kt:161`) now also matches `:*?"<>|`, and trailing dots and spaces are trimmed after the cut (`GtdBackupRecords.kt:176`). The test `file names have no characters Windows forbids and no trailing dots or spaces` covers this. Reserved device names (CON, NUL) are left out on purpose, and the task assumptions record this.
- CR-3 (waiting.md order): resolved. `WAITING_ORDER` (`GtdBackupContributor.kt:346`) sorts by waitingSince with nulls last, then by position and id. It is used only for `TaskList.WAITING` (`GtdBackupContributor.kt:63`), and the test `waiting md is ordered by waiting-since, ...` covers it.
- TW-1 (surrogate pair split by the cut): resolved. `takeCodePoints` (`GtdBackupRecords.kt:181`) cuts at a code-point boundary.

Verification notes:
- Criterion 1: every column of `area`, `context`, `project`, `task` (including `next_occurrence_id`), `task_context` (as sorted `contextIds` on each task record, as the assumption describes) and `checklist_item` is exported under the data names SPEC §10.2 lists. Import keeps ids, timestamps and versions through JDBC. It inserts in an order that satisfies every foreign key, and sets `next_occurrence_id` and `next_task_id` after all tasks exist. `waiting_for_contact_id` has no foreign key, so it does not depend on the unbuilt contacts module.
- Criterion 2: the list files and `projects/<area>/<project>.md` files are written as SPEC §10.2 describes, with checkboxes. Paths come from `fileName` plus `UniqueNames`, so they pass `BackupFormat` path safety.
- Criterion 3: `GtdBackupFixture` seeds tasks on all five lists, a tickler task, Waiting For with a contact and a text, done and trashed tasks, a FIXED recurring pair linked by `nextOccurrenceId`, an AFTER_COMPLETION recurrence, projects with next steps (with and without an area, completed, trashed), two areas, two contexts, a task with several contexts and a checklist. Every GTD data name therefore has a non-zero count, which `BackupRoundTripTest` requires. Like the other fixtures, it is a plain class found by classpath scanning.
- Search re-indexing on import uses the same title and body as `TaskService`/`ProjectService` and skips the Trash, as `FilesBackupContributor` does. Search documents are not exported, so the round trip is not affected.
- Nothing logs user content. All test data is fictitious.
- Process observations for the parent worker, not code findings: `python tasks.py verify backup-contrib-gtd` still lists all three criteria as unverified. `reviews/backup-contrib-gtd/tests.md` is not committed in this range. `fixes.md` refers to pre-rebase hashes (base 76be28d5, commits e3539c5e/7599e42c), which do not match the current history. This reviewer was read-only and did not run Gradle, so the round-trip and contributor tests were not executed here.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | `java.lang.Integer` class literal causes a Kotlin compiler warning**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/backup/GtdBackupContributor.kt:279`
    - Category: Quality
    - Problem: `rs.getObject("time_estimate_min", Integer::class.java)?.toInt()` refers to the Java platform class `Integer`. Kotlin warns about this ("This class shouldn't be used in Kotlin", PLATFORM_CLASS_MAPPED_TO_KOTLIN). No other code in `server/src/main` uses `Integer` this way.
    - Why it matters: AGENTS.md says lint is part of the contract and warnings must be fixed, not left in place. It is a small but avoidable compiler warning.
    - Suggested fix: Read the column without the Java class, for example `rs.getInt("time_estimate_min").takeUnless { rs.wasNull() }` or `(rs.getObject("time_estimate_min") as Number?)?.toInt()`.

## Review conclusion

No material issues found. The earlier findings CR-1, CR-2, CR-3 and TW-1 are resolved in the current code. The only remaining finding is the optional P3 cleanup above. Before `done`, the parent worker still needs to record evidence for the criteria (including an actual `./gradlew test --tests '*BackupRoundTripTest'` run for criterion 3) and commit the test-writer report.
