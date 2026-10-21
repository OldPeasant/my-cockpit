# Code Review

Date: 2026-10-21

Task: backup-contrib-notes - Backup contributor: notes

Scope: the final code of the task. Base 004f75ec9bb6363377b2ecaa73b42b4a400b34d8, task commits 93683682 (contributor, mirror, fixture, tests), a8dc9b66 (more contributor tests), 9dde4cd6 (fixes for the earlier review's CR-1 (partial) and CR-2), 51982bde (fixes.md). The review covers mainly production code. The test files and the fixture were read for context, because a test-writer may be changing tests at the same time. This is a re-review: the earlier report on this path covered an earlier revision, and this report replaces it.

Requirements:
- tasks/backup-contrib-notes.md: both acceptance criteria and all ASSUMPTION notes (Trash handling, slug and clash rules, front matter fields, percent-encoded image paths, `FilesBackupContributor.paths`, direct read of the `link` table)
- SPEC.md §10.2 (zip layout, readable mirror, BackupContributor)
- docs/ARCHITECTURE.md and the AGENTS.md project notes (module boundaries, logging, lint)
- reviews/backup-contrib-notes/fixes.md and the earlier review findings CR-1 and CR-2
- Existing code: BackupService (snapshot transaction), FilesBackupContributor (streaming pattern, `paths()`)

Git range:
- Branch: main
- HEAD: 51982bde0606362513a0824f5867cf07761ef00c
- Base/start: 004f75ec9bb6363377b2ecaa73b42b4a400b34d8
- Commits reviewed: 4 (93683682, a8dc9b66, 9dde4cd6, 51982bde)
- Uncommitted changes: only this report
- Method note: shell access was denied in this review session. I could not run `git diff`, `git log` or `tasks.py verify`. I read the changed files in full instead: `notes/backup/NotesBackupContributor.kt`, `files/backup/FilesBackupContributor.kt`, `NotesBackupContributorTests.kt`, `NotesBackupFixture.kt` and `BackupService.kt`. The parent's commit list and fixes.md identified them. The HEAD hash comes from `.git/refs/heads/main`.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of the earlier findings

- **CR-1 (P3, Architecture): the notes module reads the core `link` table with its own SQL.** Accepted, not fixed. A task ASSUMPTION (2026-10-21 05:52, dell26/agent-6) records the direct read-only access inside the backup snapshot transaction. The hard-coded `"NOTE"` is gone: `NOTE = ItemType.NOTE.name` (`NotesBackupContributor.kt:200`). The SQL now passes it as bind parameters (`where source_type = ? or target_type = ?`, lines 155-165), and the Kotlin comparisons use the same constant. The remaining coupling to the link schema is documented and covered by the front-matter links test. No further action is needed for this task.
- **CR-2 (P3, Quality): "streamed" queries loaded the whole result set.** Fixed. The new helper `streamed(sql)` (lines 148-149) returns a `PreparedStatementCreator` with `fetchSize = FETCH_SIZE` (1000). It uses the same pattern and value as `FilesBackupContributor`. Both the `notes()` query (line 132) and the `note_revision` query (line 55) use it. `BackupService.export` runs `export` and `readable` inside one read-only REPEATABLE READ `TransactionTemplate` (`BackupService.kt:39-43, 66-75`), so autocommit is off and PgJDBC uses a cursor. The KDoc of `notes()` now describes the behaviour correctly.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Checked without findings

- AC1, data: `export` writes `data/notes.jsonl` with every note, in id order and including the Trash. Tags are sorted, coming from the `array(select ... order by t.tag)` column. It also writes `data/note_revisions.jsonl` ordered by note and number. All columns of `note`, `note_tag` and `note_revision` are exported and imported, including ids, times, versions and `deleted_at`.
- AC1, mirror: `readable/notes/<yyyy-mm-dd>-<slug>.md` covers only notes outside the Trash. It uses the UTC creation day and the slug of the title or derived title (`Note.displayTitle`, the same rule as the domain). Slugs are ASCII-only, at most 60 characters, and fall back to `note`. Iteration runs in `created_at, id` order, so the older note keeps the plain name. The shared `names` set stops a numbered name from overwriting a note whose own title produces that name. The front matter holds id, title, tags, links (both directions, each once, oldest first through a `LinkedHashSet` and `order by created_at, id`), created, updated, pinned and archived. String values are YAML double-quoted scalars with escapes for quotes, backslashes, newlines, tabs, C0 control characters and DEL. `cockpit-file:<uuid>` becomes `../../files/<path>`, with each segment percent-encoded and spaces as `%20`. That path resolves correctly from `readable/notes/`. References to files that are not in the backup stay unchanged. Upper-case UUIDs are handled through `UUID.fromString`.
- `FilesBackupContributor.paths()` reuses the export's own `folders()` and `files()` clash logic and returns early for an empty set. It is called inside the same snapshot transaction, so the paths match the exported `files/` entries.
- Resource handling: each `queryForStream` is closed with `use`. The first `notes()` pass is closed before `files.paths()` and `links()` run. The second pass holds a single open cursor while it writes.
- Import order: notes and tags are imported before revisions, which satisfies the FK. Only notes outside the Trash are indexed, through the shared `SearchIndexer.indexNote`. Links and images are left to their own contributors, as the KDoc states.
- AC2 fixture: `NotesBackupFixture` is a plain `BackupFixture`. It goes through `NoteService`, so it seeds realistic rows: an image added via `addImage` and referenced in the body, unicode tags and tags that differ only in case, revisions (including one from `restoreRevision`), an empty untitled note, a long archived note with an inline link, a pinned note, and a note in the Trash with a revision.
- Security: nothing is logged and no note contents leave the backup. Readable file names are built from the date and an ASCII `[a-z0-9-]` slug, so path traversal is not possible. All SQL values are bound as parameters.
- Tests (read for context): the contributor tests cover the data files, Trash handling, naming and clashes, the UTC date boundary, slug edge cases, front matter, which a YAML parser reads back correctly, image path resolution against the actual zip entries, `paths()`, and import with search indexing.
- Evidence: in `tasks/backup-contrib-notes.md` both criteria are still unticked. I could not run `tasks.py verify` in this session. The parent still has to record evidence for AC1 and for AC2 (`./gradlew test --tests '*BackupRoundTripTest'`). fixes.md reports that run as passing (1 test, 0 failures) at commit 9dde4cd6. Nothing is claimed without evidence, so this is not a finding.

## Review conclusion

No material issues found.
