# Code Review

Date: 2026-10-21

Task: backup-contrib-notes - Backup contributor: notes

Scope: the final code of the task, base d8bf81f230b1536f66078983968ebc5fa1a1aad3 to HEAD 60a9225fed3d269291e1adbb5f4ab3972783c190. Commits: b974a8bb (contributor, mirror, fixture, tests), ba5d4206 (more contributor tests), fc18aa4f (fetch-size streaming, `ItemType.NOTE` for links), 9205fe45 (fixes.md), ee518315 (links front-matter test, earlier review report), 60a9225f (tests.md). This is a re-review that replaces the earlier report on this path. The earlier report covered the revision before ee518315, whose hashes changed when the branch was rebased. Since then the production code is unchanged. ee518315 changed only `NotesBackupContributorTests.kt`: it added a test and removed a redundant `!!`.

Requirements:
- tasks/backup-contrib-notes.md: both acceptance criteria and all ASSUMPTION notes (Trash handling, slug and clash rules, front matter fields, percent-encoded image paths, `FilesBackupContributor.paths`, direct read-only access to the core `link` table)
- SPEC.md §10.2 (zip layout, readable mirror, BackupContributor), docs/QUALITY.md, docs/ARCHITECTURE.md, AGENTS.md project notes
- reviews/backup-contrib-notes/fixes.md and the earlier findings CR-1 and CR-2

Git range:
- Branch: main
- HEAD: 60a9225fed3d269291e1adbb5f4ab3972783c190
- Base/start: d8bf81f230b1536f66078983968ebc5fa1a1aad3
- Commits reviewed: 6 (b974a8bb, ba5d4206, fc18aa4f, 9205fe45, ee518315, 60a9225f)
- Uncommitted changes: none before this report

Files reviewed in full: `server/src/main/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributor.kt`, `FilesBackupContributor.paths()` (`files/backup/FilesBackupContributor.kt:54-64`), `NotesBackupContributorTests.kt`, `NotesBackupFixture.kt`, the notes and link Flyway migrations, and the fixture discovery in `BackupRoundTripTest`.

Commands run:
- `git log --oneline d8bf81f2..HEAD`, `git diff --stat d8bf81f2..HEAD`, `git diff d8bf81f2..HEAD -- server/src/main/kotlin`, `git show ee518315` -> scope as above. The only code change after the earlier review is in the test file.
- `cd server && ./gradlew test --tests '*NotesBackupContributorTests' --tests '*BackupRoundTripTest' --tests '*FilesBackupContributor*'` -> BUILD SUCCESSFUL. Test results: NotesBackupContributorTests 18 tests, FilesBackupContributorTests 24 tests, BackupRoundTripTest 1 test, all with 0 failures, 0 errors and 0 skipped.
- `cd server && ./gradlew ktlintCheck` -> BUILD SUCCESSFUL

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of the earlier findings

- **CR-1 (P3, Architecture): the notes module reads the core `link` table directly.** Accepted, not fixed. A task ASSUMPTION (2026-10-21 05:52, dell26/agent-6) records the decision. The query is read-only and runs inside the backup snapshot transaction. It binds `ItemType.NOTE.name` as a parameter (`NotesBackupContributor.kt:152-167`). The new test in ee518315 now covers the coupling to the link schema.
- **CR-2 (P3, Quality): queries described as streamed loaded the whole result set.** Fixed in fc18aa4f. `streamed()` sets `fetchSize = 1000` for both the note and the revision queries. Each stream is closed with `use`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Checked without findings

- AC1, data: `data/notes.jsonl` holds every note, Trash included, in id order, with sorted tags and every column of `note` and `note_tag`. `data/note_revisions.jsonl` is ordered by note and number. On import, notes and tags go in before revisions, which satisfies the FK. Ids, times and versions are kept. Only notes outside the Trash are re-indexed.
- AC1, mirror: `readable/notes/<yyyy-mm-dd>-<slug>.md` covers notes outside the Trash only. The date is the UTC creation day. The slug is lower-case ASCII, at most 60 characters, with `note` as the fallback. On a clash the older note keeps the plain name, and the shared `names` set prevents overwrites. The front matter holds id, title, tags, links, created, updated, pinned and archived. String values are YAML double-quoted with escapes. `cockpit-file:<uuid>` becomes `../../files/<path>` with each segment percent-encoded. References to files that are not in the backup stay unchanged.
- New test (ee518315): it checks that links in both directions appear in the note's front matter (the link to the trashed note is still listed, which matches "both directions, each once, oldest first") and that links between other items are left out. It also checks that a trashed note gets no mirror file. Removing `!!` from `transactions.execute` compiles and passes with the project's Spring version.
- AC2: `NotesBackupFixture` goes through `NoteService` and seeds images, tags (unicode tags and spellings that differ only in case), revisions (one from `restoreRevision`), an untitled empty note, a long archived note, a pinned note and a trashed note with a revision. `BackupRoundTripTest` finds the fixture and passed in this session.
- Security: nothing is logged. All SQL values are bound as parameters. File names in the mirror contain only the date and `[a-z0-9-]`, so path traversal is not possible. No secrets or real personal data appear in the code or the tests.
- Evidence: this review does not run or change `tasks.py`. The parent must record `tasks.py verify` for both criteria. The focused test run above can serve as repeatable evidence for AC2.

## Review conclusion

No material issues found.
