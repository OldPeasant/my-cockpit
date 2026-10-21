# Code Review

Date: 2026-10-21

Task: backup-contrib-gtd - Backup contributor: GTD

Scope: Task commit for backup-contrib-gtd on top of base 76be28d5f9df6c21b174b2d5b6445b37f385e68c (commit given by the parent as 10c345d4; the working tree HEAD at review start was cb6f40ad "backup-contrib-gtd: GTD backup contributor, readable mirrors and round-trip fixture"). Files reviewed: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/backup/GtdBackupContributor.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/gtd/backup/GtdBackupRecords.kt`, `server/src/test/kotlin/ch/sonensei/cockpit/gtd/backup/GtdBackupFixture.kt`. Committed code only.

Requirements:
- tasks/backup-contrib-gtd.md (acceptance criteria 1-3 and the two recorded assumptions)
- SPEC.md §5.1, §5.2, §5.3, §10.2, §14
- AGENTS.md project notes and conventions
- Existing contracts and conventions: `backup/domain/BackupContributor.kt`, `backup/domain/BackupFormat.kt`, `files/backup/FilesBackupContributor.kt`, `core/backupcontrib/*`, `BackupRoundTripTest.kt`, `BackupFixture.kt`
- Schema: `db/migration/gtd/V202610201311__gtd_model.sql`, `V202610201830__gtd_task_next_occurrence.sql`

Git range:
- Branch: main
- HEAD: cb6f40ad (as reported in the session git status; `git` commands were not permitted in this review session, so the range could not be re-resolved here)
- Base/start: 76be28d5f9df6c21b174b2d5b6445b37f385e68c
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 1 P2, 2 P3

Verification notes:
- Every column of `area`, `context`, `project`, `task` (including `next_occurrence_id`), `task_context` and `checklist_item` is exported and re-inserted; ids, timestamps and versions are preserved via JDBC, following the files/core contributor pattern.
- Import order satisfies all foreign keys: areas, contexts, projects (without `next_task_id`), tasks with their `task_context` rows, then `next_occurrence_id` and `next_task_id` updates, then checklist items. No other module's table references GTD tables, so an empty `dependsOn` is correct.
- Search re-indexing on restore uses the same title/body as `TaskService.changed` and `ProjectService`, and skips Trash items as `SearchIndexer` documents.
- Readable paths go through `fileName`, which removes `/`, `\` and control characters and leading dots, so `BackupFormat.requireSafePath` is satisfied; folder and file clashes are resolved case-insensitively.
- The fixture seeds tasks on all five lists, a tickler, Waiting For with contact and text, done and trashed tasks, a FIXED recurring pair linked by `nextOccurrenceId`, an AFTER_COMPLETION recurrence, projects with next steps (with and without area, completed, trashed), two areas, two contexts, multiple contexts on one task and a checklist, so every data name gets a non-zero count as `BackupRoundTripTest` requires.
- The round-trip test was not executed by this reviewer (read-only session; the Testcontainers suite is the parent's evidence for criterion 3). No acceptance criterion has recorded evidence yet (`tasks.py verify backup-contrib-gtd` lists all three unticked).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Readable mirrors (criterion 2) have no test at the reviewed revision**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/gtd/backup/GtdBackupFixture.kt:31`
    - Category: Tests
    - Problem: The only test code in the commit is the round-trip fixture. `BackupRoundTripTest` only compares `data/` and `files/`; it runs `readable()` (via `BackupService.export`) but asserts nothing about `readable/`. Nothing checks that `tasks/{inbox,next,waiting,someday,logbook}.md` and `projects/<area>/<project>.md` exist, contain checkboxes, exclude the Trash, put SCHEDULED tasks in the Next file's "Scheduled" section, order the logbook newest first, mark the next step, or resolve file name clashes (`UniqueNames`, `fileName`, `No area`).
    - Why it matters: Criterion 2 is a deliverable of this task, and the mirror layout plus the recorded assumptions (scheduled section, no Trash, clash numbering) are regression-prone. Without a test there is no repeatable evidence for criterion 2.
    - Suggested fix: Add a contributor-level test (the test-writer runs in parallel) that seeds a small dataset, calls `readable()` with a capturing `BackupReadableWriter`, and asserts on the paths and key lines. Also add unit tests for `fileName`/`UniqueNames` edge cases (separators, control chars, leading dots, empty title, case-insensitive clashes, truncation).

## P3 Low

- [ ] **CR-2 | Readable file names keep characters that are invalid on Windows**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/backup/GtdBackupRecords.kt:160`
    - Category: Quality
    - Problem: `FILE_NAME_FORBIDDEN` only removes `/`, `\` and control characters. Titles such as `Q1: plan?` or `"Big" <launch>` give `readable/projects/...` entries with `: ? " < > | *`, or names that end in a dot or space. Windows Explorer and many unzip tools cannot extract these entries.
    - Why it matters: SPEC.md §10.2 says the readable mirrors are "for browsing and use without the app". Unlike `files/`, where original names are required, these names are derived and fully under the contributor's control.
    - Suggested fix: Also replace `:*?"<>|` and trim trailing dots and spaces in `fileName`. Update the assumption text if the rule changes.

- [ ] **CR-3 | waiting.md is ordered by position, not by waiting-since**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/backup/GtdBackupContributor.kt:63`
    - Category: Correctness
    - Problem: Every list file uses `LIST_ORDER` (position, id). SPEC.md §5.3 defines the Waiting For list as "sorted by `waitingSince`". The readable mirror therefore lists waiting items in a different order than the app shows them.
    - Why it matters: This is a small mismatch with the documented view, and the mirror's purpose is to be read without the app. It has no data impact.
    - Suggested fix: For `TaskList.WAITING`, sort by `waitingSince` (nulls last), then position and id. Alternatively, record the chosen order as an assumption.

## Review conclusion

Material issues found; fix before completing the task. The canonical export/import is correct and complete against the schema. Before completion, CR-1 (missing tests and evidence for the readable-mirror criterion) must be closed; the test-writer may cover it. CR-2 and CR-3 are low-priority and optional.
