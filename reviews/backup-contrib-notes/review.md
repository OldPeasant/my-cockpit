# Code Review

Date: 2026-10-21

Task: backup-contrib-notes - Backup contributor: notes

Scope: final code of the task at HEAD 5ceb75bfff8eabfa33c289080e95d66df37b1f53 (branch main), the task commits since the merge base with origin/main. This re-review replaces the earlier report at this path and covers the CR-3 fix in `quoted()`. Production files: `server/src/main/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributor.kt` (new) and `FilesBackupContributor.paths()` (`server/src/main/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributor.kt:54-64`, added). Test files: `server/src/test/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributorTests.kt` and `NotesBackupFixture.kt`.

Requirements:
- tasks/backup-contrib-notes.md: both acceptance criteria and all ASSUMPTION notes (Trash handling, UTC creation day, slug and clash rules, front matter fields, percent-encoded image paths, `FilesBackupContributor.paths`, read-only access to the core `link` table)
- SPEC.md §8.1 (notes, `cockpit-file:` image references, history) and §10.2 (zip layout, `data/notes.jsonl`, `data/note_revisions.jsonl`, `readable/notes/` mirror, BackupContributor, round-trip test)
- AGENTS.md project notes (module boundaries, JDBC/Flyway, logging, fictitious test data), docs/QUALITY.md
- reviews/backup-contrib-notes/fixes.md (earlier findings CR-1, CR-2, CR-3)

Git range:
- Branch: main
- HEAD: 5ceb75bfff8eabfa33c289080e95d66df37b1f53
- Base/start: merge base of HEAD and origin/main (origin/main is at 1a44a931a3325cda7ce70225b8bcd9a9b9145e73; the earlier review resolved the merge base to 8da4e3773b8ef26e5a0991805bd32fc0f9eb964e). This reviewer could not recompute it, see "Commands run".
- Commits reviewed: the task commits up to 5ceb75bf; the latest code change is 1297dcda (CR-3), 5ceb75bf changes only `reviews/`
- Uncommitted changes: none reported in the session's initial git status (clean)

Commands run:
- Bash, and with it `git` and `./gradlew`, was denied in this reviewer's sandbox. I could not run `git diff`, `git merge-base` or `cd server && ./gradlew test --tests '*NotesBackupContributorTests' --tests '*BackupRoundTripTest' ktlintCheck` in this session. The review is based on reading the current files at HEAD.
- Existing Gradle results in `server/build/test-results/test/`: `TEST-ch.sonensei.cockpit.notes.backup.NotesBackupContributorTests.xml` (timestamp 2026-10-21T06:24:50Z) has 18 tests, 0 failures, 0 errors, 0 skipped, including the extended YAML test; `TEST-ch.sonensei.cockpit.backup.BackupRoundTripTest.xml` (2026-10-21T06:24:23Z) has 1 test, 0 failures. These match the CR-3 run described in fixes.md. The parent worker should treat them as earlier evidence, not as a run done by this review.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of the earlier findings

- **CR-1 (Architecture): the notes module reads the core `link` table directly.** Accepted, not fixed. Recorded as a task ASSUMPTION (dell26/agent-6, 2026-10-21 05:52). The query is read-only, runs in the backup's snapshot transaction and binds `ItemType.NOTE.name` as parameters (`NotesBackupContributor.kt:152-167`); covered by `NotesBackupContributorTests.kt:152-165`. Not raised again.
- **CR-2 (Quality): queries described as streamed loaded the whole result set.** Fixed. `streamed()` (`NotesBackupContributor.kt:148-149`) sets `fetchSize = 1000` for the note queries and the revision query, the same as `FilesBackupContributor`; each stream is closed with `use`.
- **CR-3 (Correctness): front matter not valid YAML for C1 and other non-printable characters.** Fixed. `quoted()` (`NotesBackupContributor.kt:228-243`) now writes C0 controls (except `\n` and `\t`, which get their short escapes), U+007F-U+009F (DEL, C1, NEL U+0085), U+2028, U+2029, U+FFFE and U+FFFF as `\uXXXX`. Characters outside the BMP are kept as surrogate pairs and are in YAML's printable set. The test at `NotesBackupContributorTests.kt:224-255` asserts each escape appears in the raw front matter and that SnakeYAML gives back the exact title and tags. Its KDoc now names the escaped set. See CR-4 for a readability point in the fix.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-4 | Invisible literal characters in the CR-3 escape condition and its test**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributor.kt:237`
    - Category: Quality
    - Problem: the condition compares against U+2028, U+2029 and U+FFFE as raw characters inside the char literals (`c == '...' || c == '...' || c >= '...'`), while the neighbouring range uses escapes (`'\u007f'..'\u009f'`). In most editors, diff views and terminals these render as a plain space or as nothing, so the line reads like `c == ' ' || c == ' '`. The test data has the same pattern at `NotesBackupContributorTests.kt:237` (`"ls ps "`) and `:239` (`"nonchar"` followed by U+FFFE U+FFFF). The behaviour is correct: the test asserts the ` `, ` `, `￾` and `￿` escapes in the output and passed.
    - Why it matters: a maintainer cannot see which characters are escaped. An editor or formatter that normalizes or strips line or paragraph separators, or someone who "fixes" what looks like a duplicate `' '` comparison, would silently change the escaping. In the worst case every space in titles would be escaped.
    - Suggested fix: write `' '`, `' '` and `'￾'` as Kotlin escapes in the condition, and `"ls ps "` and `"nonchar￾￿"` in the test.

## Checked without findings

- AC1, data: `data/notes.jsonl` contains every note, Trash included, in id order, with every `note` column and its tags sorted from `note_tag`. `data/note_revisions.jsonl` contains every `note_revision` column, ordered by note and number. Import writes notes and tags before revisions (FK on `note_revision.note_id`), keeps ids, times, versions and `deleted_at`, and re-indexes only notes outside the Trash through `indexNote`.
- AC1, mirror: one file per note outside the Trash at `readable/notes/YYYY-MM-DD-SLUG.md`, date = UTC creation day, slug NFD-normalized ASCII `[a-z0-9-]`, at most 60 characters, `note` as fallback. Ordered by `created_at, id`, so the older note keeps the plain name; the shared `names` set prevents a numbered name from overwriting another note's own name. Front matter: id, display title, tags, links (`type:id`, both directions, each once, oldest first), created, updated, pinned, archived. Every `cockpit-file:UUID` reference (any case) becomes `../../files/PATH` with each segment percent-encoded (`%20` for spaces); references to files not in the backup stay unchanged. The test at lines 268-289 resolves the targets against the real `files/` zip entries.
- `FilesBackupContributor.paths()`: reuses the export's `folders()` and `files()` path assignment (no duplicated clash rules), returns early for an empty set, leaves out unknown ids, and is documented to run in the backup transaction so the paths match the exported blobs. Test at lines 304-317.
- AC2: `NotesBackupFixture` seeds notes with an image, tags and revisions; `BackupRoundTripTest` passed in the existing result file (see "Commands run").
- Security and conventions: no logging, all SQL values bound, constant SQL fragments only, mirror file names limited to a date and `[a-z0-9-]` (no path traversal), JDBC restore keeps ids, times and versions as the KDoc explains, no schema changes, fictitious test data, no secrets.
- Evidence on the task: both criteria are recorded with the commands the criteria name. `reviews/backup-contrib-notes/quality.json` still lists only CR-1 and CR-2 and a `reviewed_revision` of d8340bd7; the parent worker needs to add CR-3 (and CR-4) and update the revision before `done`.

## Review conclusion

No material issues found. One optional low-priority finding (CR-4, readability of the CR-3 fix). CR-1 is accepted by recorded assumption; CR-2 and CR-3 are fixed. This reviewer could not run git, the focused tests or `ktlintCheck` because Bash was denied, so the parent worker must make sure a current run of both is recorded.
