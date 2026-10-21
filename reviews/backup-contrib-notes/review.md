# Code Review

Date: 2026-10-21

Task: backup-contrib-notes - Backup contributor: notes

Scope: final code of the task at HEAD 323bf50ae9a31271f809a44c7777730c9a04cb19 (branch main), the task commits `git log 3b0efeeb..HEAD` (14 commits). This re-review replaces the earlier report at this path (written at 5ceb75bf) and covers the CR-4 fix (d244c4e5) and the extended escaping test (323bf50a). Production files: `server/src/main/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributor.kt` (new) and `FilesBackupContributor.paths()` (`server/src/main/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributor.kt:54-64`, added). Test files: `server/src/test/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributorTests.kt` and `NotesBackupFixture.kt`.

Requirements:
- tasks/backup-contrib-notes.md: both acceptance criteria and all ASSUMPTION notes (Trash handling, UTC creation day, slug and clash rules, front matter fields, percent-encoded image paths, `FilesBackupContributor.paths`, read-only access to the core `link` table)
- SPEC.md §8.1 (notes, `cockpit-file:` image references, history) and §10.2 (zip layout, `data/notes.jsonl`, `data/note_revisions.jsonl`, `readable/notes/` mirror, BackupContributor, round-trip test)
- AGENTS.md project notes (module boundaries, JDBC/Flyway, logging, lint, fictitious test data), docs/QUALITY.md
- reviews/backup-contrib-notes/fixes.md (history of CR-1, CR-2, CR-3) and the earlier review (CR-4)

Git range:
- Branch: main
- HEAD: 323bf50ae9a31271f809a44c7777730c9a04cb19
- Base/start: 3b0efeeb4508453483f15cc2bddff432b0ae40d4 (merge base with origin/main, supplied by the parent worker)
- Commits reviewed: 14 (fd2bef44 .. 323bf50a); code changes since the previous review: d244c4e5 (production `quoted()` and test) and 323bf50a (test only)
- Uncommitted changes: none at session start (clean git status); this report is the only file written by this review

Commands run:
- `git log --oneline 3b0efeeb..HEAD`, `git diff --stat 3b0efeeb..HEAD`, `git diff 3b0efeeb..HEAD -- server/src/main/kotlin/ch/sonensei/cockpit/files`, `git diff 437656d2..HEAD -- server/`
- `cd server && ./gradlew test --tests '*NotesBackupContributorTests' --tests '*BackupRoundTripTest' ktlintCheck` -> BUILD SUCCESSFUL in 52s. Result files written by this run (2026-10-21T06:44Z): `NotesBackupContributorTests` 18 tests, 0 failures, 0 errors, 0 skipped; `BackupRoundTripTest` 1 test, 0 failures, 0 errors, 0 skipped; ktlintMainSourceSetCheck and ktlintTestSourceSetCheck passed.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of the earlier findings

- **CR-1 (Architecture): the notes module reads the core `link` table directly.** Accepted, not fixed; recorded as a task ASSUMPTION (dell26/agent-6, 2026-10-21 05:52). The query is read-only, runs in the backup's snapshot transaction and binds `ItemType.NOTE.name` as parameters (`NotesBackupContributor.kt:152-167`). Not raised again.
- **CR-2 (Quality): queries described as streamed loaded the whole result set.** Fixed. `streamed()` (`NotesBackupContributor.kt:148-149`) sets `fetchSize = 1000` for the note and revision queries; each stream is closed with `use`.
- **CR-3 (Correctness): front matter not valid YAML for C1 and other non-printable characters.** Fixed. `quoted()` (`NotesBackupContributor.kt:228-243`) escapes C0 (with `\n`/`\t` short forms), U+007F-U+009F, U+2028, U+2029, U+FFFE and U+FFFF as `\uXXXX`.
- **CR-4 (Quality): invisible literal U+2028/U+2029/U+FFFE characters in `quoted()` and its test.** Fixed in d244c4e5. The condition at `NotesBackupContributor.kt:237` now compares against U+2028, U+2029 and U+FFFE written as Kotlin `\uXXXX` escapes, like the neighbouring U+007F-U+009F range. The test data at `NotesBackupContributorTests.kt:226`, `:239` and `:241` also writes U+2028, U+2029, U+FFFE and U+FFFF as escapes. The diff `437656d2..HEAD` shows the condition changed only from literal to escaped form, so the behaviour is the same. 323bf50a adds a check that printable neighbours of the escaped ranges (space, `~`, NBSP, U+2027, U+202A, U+FFFD and a surrogate-pair emoji) are written raw and not as escapes (`NotesBackupContributorTests.kt:227-228`, `:252-255`), so an escape range made too wide would now fail the test. SnakeYAML still gives back the exact title and tags.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Checked without findings

- AC1, data: `data/notes.jsonl` holds every note, Trash included, ordered by id, with every `note` column and tags sorted from `note_tag`; `data/note_revisions.jsonl` holds every `note_revision` column ordered by note and number. Import writes notes and tags before revisions (FK on `note_revision.note_id`), keeps ids, times, versions and `deleted_at`, and indexes only notes outside the Trash through `indexNote`. Tests at `NotesBackupContributorTests.kt:60-94` and `:328-357`.
- AC1, mirror: one file per note outside the Trash at `readable/notes/YYYY-MM-DD-SLUG.md` (UTC creation day, NFD-normalized ASCII `[a-z0-9-]` slug of at most 60 characters, `note` fallback). Ordered by `created_at, id`, so the older note keeps the plain name; the shared `names` set prevents a numbered name from colliding with another note's own name. Front matter: id, display title, tags, links (`type:id`, both directions, each once, oldest first), created, updated, pinned, archived. Each `cockpit-file:UUID` reference (any case) becomes `../../files/PATH` with every segment percent-encoded; references to files missing from the backup stay unchanged. The test at `:275-296` resolves the link targets against the real `files/` zip entries.
- `FilesBackupContributor.paths()`: reuses the export's `folders()`/`files()` path assignment (no duplicated clash rules), returns early for an empty set, leaves out unknown ids, and is documented to run in the backup transaction. Test at `:311-324`.
- AC2: `NotesBackupFixture` seeds notes with an image, unicode tags, inline links, revisions (also from `restoreRevision`), an untitled empty note, a long note, pinned/archived notes and a Trashed note with a revision; `BackupRoundTripTest` passed in this review's run.
- Security and conventions: no logging, all SQL values bound, only constant SQL fragments concatenated, mirror file names limited to a date and `[a-z0-9-]` (no path traversal), JDBC restore keeps ids/times/versions as the KDoc explains, no schema changes, fictitious test data, no secrets, ktlint clean.
- Evidence on the task: both criteria are recorded with the commands they name, and this review's run reproduces them at HEAD 323bf50a. `reviews/backup-contrib-notes/quality.json` still has `reviewed_revision` d8340bd7 and lists only CR-1 and CR-2; the parent worker needs to add CR-3 and CR-4 with their dispositions and set the revision to the final reviewed hash before `done`. This is about the evidence file, not the code, so it is not a numbered finding.

## Review conclusion

No material issues found. CR-4 is fixed and covered by the extended escaping test; CR-2 and CR-3 stay fixed; CR-1 is accepted by recorded assumption. The focused tests (18 + 1) and `ktlintCheck` pass at HEAD 323bf50a.
