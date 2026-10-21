# Code Review

Date: 2026-10-21

Task: backup-contrib-notes - Backup contributor: notes

Scope: final code of the task, base 8da4e3773b8ef26e5a0991805bd32fc0f9eb964e to HEAD 97ca4bc8f27b8e83c28996aaf66570f37f7d8337 (`git log 8da4e377..HEAD`). This re-review replaces the earlier report at this path. Production files: `server/src/main/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributor.kt` (new) and `FilesBackupContributor.paths()` (`server/src/main/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributor.kt:54-64`, added). Test files: `server/src/test/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributorTests.kt` and `NotesBackupFixture.kt`. The commits after ae637302 change only `reviews/`.

Requirements:
- tasks/backup-contrib-notes.md: both acceptance criteria and all ASSUMPTION notes (Trash handling, UTC creation day, slug and clash rules, front matter fields, percent-encoded image paths, `FilesBackupContributor.paths`, read-only access to the core `link` table)
- SPEC.md §8.1 (note model, `cockpit-file:` image references, note history), §10.2 (zip layout, `data/notes.jsonl`, `data/note_revisions.jsonl`, `readable/notes/` mirror, BackupContributor), §12 and §14 (round-trip test)
- AGENTS.md project notes (module boundaries, JDBC/Flyway, logging, fictitious test data), docs/QUALITY.md
- reviews/backup-contrib-notes/fixes.md (earlier findings CR-1 and CR-2)

Git range:
- Branch: main
- HEAD: 97ca4bc8f27b8e83c28996aaf66570f37f7d8337
- Base/start: 8da4e3773b8ef26e5a0991805bd32fc0f9eb964e (equal to `git merge-base HEAD origin/main`)
- Commits reviewed: 9 (c1ec6bec, 3374e53c, 27f4062f, 0f0c2d4d, ae637302, f33c67a5, 34ea2a3a, 6d5dc79b, 97ca4bc8)
- Uncommitted changes: none before this report

Commands run:
- `git log 8da4e377..HEAD`, `git diff --stat 8da4e377..HEAD`, `git diff 8da4e377..HEAD -- .../FilesBackupContributor.kt`, `git diff ae637302..HEAD --stat` (only `reviews/` changed after the last test commit).
- I tried to run `./gradlew test --tests '*NotesBackupContributorTests' --tests '*BackupRoundTripTest'` and `./gradlew ktlintCheck` from `server/`, but this reviewer's sandbox denied permission to run them. I could not repeat the test or lint run in this session. The existing Gradle results in `server/build/test-results/test/` (timestamp 2026-10-21T06:08Z) show NotesBackupContributorTests with 18 tests and BackupRoundTripTest with 1 test, both with 0 failures, 0 errors and 0 skipped. The 18 matches the number of `@Test` methods in the current test file. The parent worker should treat these results as earlier evidence, not as a run done by this review.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of the earlier findings

- **CR-1 (Architecture): the notes module reads the core `link` table directly.** Accepted, not fixed. A task ASSUMPTION (dell26/agent-6, 2026-10-21 05:52) records this decision. The query is read-only, runs in the backup's repeatable-read snapshot and binds `ItemType.NOTE.name` as parameters (`NotesBackupContributor.kt:152-167`). The test at `NotesBackupContributorTests.kt:152-165` covers it. Not raised again.
- **CR-2 (Quality): queries described as streamed loaded the whole result set.** Fixed. `streamed()` (`NotesBackupContributor.kt:148-149`) sets `fetchSize = 1000` for the note queries and the revision query, in the same way as `FilesBackupContributor`. Each stream is closed with `use`, and the export runs inside `BackupService`'s transaction, so the PgJDBC cursor takes effect.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-3 | Front matter is not valid YAML for titles or tags with C1 control or other non-printable characters**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributor.kt:225-239`
    - Category: Correctness
    - Problem: `quoted()` escapes only the C0 controls and DEL (`c < ' ' || c == '\u007f'`). YAML's printable set excludes U+0080-U+0084, U+0086-U+009F, U+FFFE and U+FFFF, so a YAML parser must reject these characters even inside a double-quoted scalar. In addition, U+0085, U+2028 and U+2029 are line breaks in YAML 1.1 (for example SnakeYAML), so a parser folds them inside the scalar and returns a different title. The KDoc of `quoted()` and the test at `NotesBackupContributorTests.kt:224-239` claim that the front matter is valid YAML for any title or tag, but the test only uses C0 characters and DEL.
    - Why it matters: this affects the readable mirror only, since restore reads `data/`. A note titled with text that was decoded with the wrong charset (where C1 characters are common) gives a `.md` file whose front matter tools such as static-site generators or Obsidian cannot parse.
    - Suggested fix: escape every character outside the YAML printable set, plus U+0085, U+2028 and U+2029, as `\uXXXX`, and add one such character to the YAML test's tags.

## Checked without findings

- AC1, data: `data/notes.jsonl` contains every note, including those in the Trash, in id order. Each note has every column of `note` plus its tags sorted from `note_tag`. `data/note_revisions.jsonl` contains every `note_revision` column, ordered by note and number. On import, notes and their tags are written before revisions, which satisfies the FK on `note_revision.note_id`. Ids, times, versions and `deleted_at` are kept. Only notes outside the Trash are re-indexed, using `indexNote` (the same title and plain-text body that `NoteService` writes). The test at lines 323-334 confirms this.
- AC1, mirror: there is one file per note outside the Trash, at `readable/notes/YYYY-MM-DD-SLUG.md`. The date is the UTC creation day. The slug is NFD-normalized with marks dropped, lower-case `[a-z0-9-]`, at most 60 characters, with `note` as the fallback. Notes are processed by `created_at, id`, so the older note keeps the plain name. The shared `names` set prevents a numbered name from overwriting a note whose own title produces that name (test at lines 209-222). The front matter holds id, title (the display title), tags, links (`type:id`, both directions, each once, oldest first), created, updated, pinned and archived. Every `cockpit-file:UUID` reference (any case) becomes `../../files/PATH`, with each segment percent-encoded and spaces written as `%20`. References to files that are not in the backup stay unchanged. The test at lines 252-273 resolves the encoded targets against the actual `files/` entries in the zip.
- `FilesBackupContributor.paths()`: it reuses the export's private `folders()` and `files()` path assignment, so the clash rules are not duplicated. It returns early for an empty set, leaves out unknown ids and is documented to run in the backup transaction. Both readable passes and `paths()` run in the same repeatable-read snapshot, so the paths match the exported blobs. Files in the Trash are exported too, so links to them resolve. The test at lines 288-301 compares the result with the exported entries.
- AC2: `NotesBackupFixture` seeds data through `NoteService`: an image added with `addImage`, unicode tags and tags that differ only in case, revisions (one created by `restoreRevision`), an inline link, an untitled empty note, a long archived note, a pinned note and a trashed note with a revision. `BackupRoundTripTest` finds it through classpath scanning and fails while any contributor has no fixture. No `dependsOn` is needed: `note_tag` and `note_revision` reference only `note`, and links and files are restored by their own contributors.
- Security and conventions: nothing is logged, and note contents are never logged. All SQL values are bound as parameters. The constant SQL fragments contain no user input. Mirror file names contain only a date and `[a-z0-9-]`, so path traversal is not possible. Restore uses JDBC to keep ids, times and versions, as the KDoc explains. There are no schema changes. Test data is fictitious. No secrets appear in the code or tests.
- Evidence on the task: both criteria are recorded with the commands named in the criteria. Of these, this review could only confirm the existing Gradle result files (see "Commands run").

## Review conclusion

No material issues found. One optional low-priority finding (CR-3, readable mirror only). This reviewer could not repeat the focused tests or `ktlintCheck` because permission was denied. The parent worker must make sure a current run of both is recorded.
