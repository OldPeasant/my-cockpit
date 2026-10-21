# Test Report

Date: 2026-10-21

Task: backup-contrib-notes - Backup contributor: notes

Scope: base `1a44a931a3325cda7ce70225b8bcd9a9b9145e73` (`git merge-base HEAD origin/main`, supplied by the parent) .. HEAD
`171b71f8f951370034c5d65c35985cdbd43f6b95` (branch `main`). Task commits: `git log 1a44a931..HEAD`, 13 commits, all prefixed
`backup-contrib-notes:` or `quality:`. Production files from `git diff --name-only 1a44a931..HEAD -- server/src/main web/src android`:
`NotesBackupContributor.kt` (new) and `FilesBackupContributor.kt` (`paths()`, added lines 54-65). This run covers the final code
after CR-3 (`quoted()` escapes U+007F..U+009F, U+2028/U+2029, U+FFFE/U+FFFF as `\uXXXX`) and CR-4 (those characters written
as escapes in the source). Uncommitted input: this session's edit of `NotesBackupContributorTests.kt` (hash below). It replaces
the earlier report, which described the code before CR-3 and is stale.

Requirements:
- `tasks/backup-contrib-notes.md` acceptance criteria and recorded assumptions (mirror only for notes not in the Trash,
  UTC creation-day file date, slug rules, clash numbering, front matter fields as YAML, image link rewriting, `paths()` in the
  files contributor, links read from the core link table)
- `SPEC.md` section 10.2 (backup layout), section 14 (round-trip test)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`
- Parent instruction: check that the escaping test also shows that printable characters next to the escaped ranges stay raw

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributorTests.kt`

## Tests added or updated

Count: 1 (updated)

`front matter is valid YAML that gives back titles and tags with quotes, backslashes and control characters`: added a tag
built from the printable neighbours of every escaped range: plain space, U+007E (just below DEL), U+00A0 (just above C1),
U+2027 and U+202A (around U+2028/U+2029), U+FFFD (just below U+FFFE) and an emoji (U+1F600, the surrogate pair
U+D83D U+DE00). The test now asserts that the tag appears verbatim inside its quotes in the front matter, that none of
` `, `~`, ` `, `‧`, `‪`, `�`, `\ud83d`, `\ude00` appears in the file, and (as before) that
SnakeYAML parses the tag back to the same string. Before this change the test only proved that the forbidden characters
are escaped. An escape range that was too wide (for example `c >= '\u007f'`, or escaping each surrogate half) would have
passed the YAML round trip without being noticed. The existing 18 contributor tests and the round-trip fixture were reviewed
again for the final code. No other gaps.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| `data/notes.jsonl` with every note (Trash included), fields and sorted tags | `notes jsonl holds every note with its fields and sorted tags, the ones in the Trash included` | `cd server && ./gradlew test --tests '*NotesBackupContributorTests' --tests '*BackupRoundTripTest' jacocoTestReport ktlintCheck` | Pass |
| `data/note_revisions.jsonl` | `note revisions jsonl holds every revision, by note and number` | same | Pass |
| `readable/notes/YYYY-MM-DD-SLUG.md`, only notes not in the Trash, UTC creation day, slug rules, clash numbering | `each note not in the Trash is a Markdown file named after its creation day and title`, `notes with the same day and title get numbered names, the older one keeps the name`, `slugs are lower case ASCII words of at most 60 characters`, `slugs keep exactly 60 characters, cut longer ones and trim separators at both ends`, `the file date is the creation day in UTC, so the same title on another day keeps its name`, `a numbered name never overwrites a note whose own title gives that name` | same | Pass |
| Front matter with id, tags, links (both directions, each once, oldest first) | `front matter holds id, title, tags, links in both directions, times and state, then the body`, `front matter lists links from other item types to the note, but not links between other items`, `an untitled note uses its derived title and empty tag and link lists in the front matter`, `the body keeps a single final newline and an empty body leaves only the front matter` | same | Pass |
| Front matter is valid YAML: C0, DEL, C1, U+2028/U+2029, U+FFFE/U+FFFF escaped as `\uXXXX`, printable neighbours (space, U+007E, U+00A0, U+2027, U+202A, U+FFFD, emoji) stay raw (CR-3/CR-4) | `front matter is valid YAML that gives back titles and tags with quotes, backslashes and control characters` (updated) | same | Pass |
| Image links rewritten to `../../files/...`; unknown refs unchanged | `image references point to the file in the backup, other references stay`, `image links resolve to the exported file entries, also for nested folders, clashing names and upper-case ids` | same | Pass |
| `FilesBackupContributor.paths()` matches export paths, skips missing ids, empty input | `files paths returns the export path of existing files only, and nothing for no ids` | same | Pass |
| Restore keeps ids/times/versions/Trash state, reindexes search for notes not in the Trash | `import restores notes, tags and revisions with their ids, times, versions and Trash state`, `import indexes the notes not in the Trash for search, with the body as plain text` | same | Pass |
| Round-trip test passes with `NotesBackupFixture` (notes with images, tags, revisions) | `BackupRoundTripTest` (finds `NotesBackupFixture` on the classpath, Testcontainers Postgres) | same | Pass (1 test) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*NotesBackupContributorTests' --tests '*BackupRoundTripTest' jacocoTestReport ktlintCheck`

Result:

Pass. BUILD SUCCESSFUL in 56s (2026-10-21 06:29:19Z to 06:30:16Z, exit 0). `compileTestKotlin`, `test`,
`runKtlintCheckOverTestSourceSet` and `jacocoTestReport` were executed; `compileKotlin` was up-to-date against the unchanged
production source. JUnit XML (`server/build/test-results/test/`, written 08:30:14 +0200): `NotesBackupContributorTests`
18 tests, 0 failures, 0 errors, 0 skipped; `BackupRoundTripTest` 1 test, 0 failures, 0 errors, 0 skipped. ktlint: no
violations (main and test source sets).

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

FULL_CHECK_RESULT

## .taskcheck

Result:

Not run by the test-writer: `sh scripts/check.sh` covers all three parts and the parent worker runs it on the merged result
before `done`. The web and Android parts are not touched by this task.

## Bugs found

None.

## Coverage

Status: verified.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. Method: JaCoCo XML `line` entries intersected with the added lines of
`git diff 1a44a931..HEAD -- server/src/main`. `NotesBackupContributor.kt` is a new file, so all its executable lines are
changed lines: 164/164 lines, 46/46 branches. `FilesBackupContributor.kt` added lines 54-65 (`paths()`); executable lines
60, 61, 62: 3/3 covered (line 60: 2/2 branches). Total changed lines 167/167 (100%), branches on changed lines 48/48 (100%).
All branches of the escape condition in `quoted()` (line 237) are covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `backup-contrib-notes`, base
  `1a44a931a3325cda7ce70225b8bcd9a9b9145e73`, HEAD `171b71f8f951370034c5d65c35985cdbd43f6b95`. The parent supplied the base
  and the commits. Production files come from `git diff --name-only 1a44a931..HEAD -- server/src/main web/src android` and
  match the task's touches plus the recorded `paths()` assumption.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `171b71f8` plus one uncommitted test edit; sha256
  `NotesBackupContributor.kt` 1d4f4bd4d8f35e956765cbc57ab007299597de5c1ca03809b62e67b83c34c304,
  `FilesBackupContributor.kt` f3ac7a01bcad1a96bbe62fb8418db61d279bd06235c33fbfc385b95b27e8457c,
  `NotesBackupContributorTests.kt` e250a0a22f24cf9907569a17929ec8546adcf3f98fa24936688a72da3f7293eb (uncommitted),
  `NotesBackupFixture.kt` f625f567313c41286660e59746c134c28f23430f9d7ff7d6c9b3f59327941983.
- Exact included files; renamed/deleted/excluded files and reasons:
  `server/src/main/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributor.kt`,
  `server/src/main/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributor.kt`. No renames or deletions. Excluded:
  test files and `reviews/backup-contrib-notes/*` (not production code).
- Provider/version, command, working directory, and test selection: JaCoCo through the project's Gradle `jacoco` plugin
  (Gradle 9.8.1, OpenJDK 21.0.12.1); `./gradlew test --tests '*NotesBackupContributorTests' --tests '*BackupRoundTripTest'
  jacocoTestReport ktlintCheck` in `server/`; selection: those two test classes only.
- Run start/completion time and exit status: 2026-10-21 06:29:19Z to 06:30:16Z, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`,
  sha256 cb4e459dce1639dafd05c21102279444ce7e47d12545caf83563b5c2500d41b5, written 2026-10-21 08:30:16 +0200.
- Evidence outputs are fresh and inputs stayed unchanged: `test` and `jacocoTestReport` were executed in this run, and the
  report and JUnit XML timestamps fall within the run. The source hashes taken before the run (06:29:02Z) matched those taken after it.
- Final run after latest test additions and production fixes: yes (CR-3/CR-4 are in HEAD; no test or production changes after the run).

Values below use covered/total (percentage). JaCoCo has no statement counter, so the Statements column shows INSTRUCTION.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributor.kt` | 1156/1166 (99.1%) | 46/46 (100%) | 54/54 (100%) | 164/164 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributor.kt` (whole file, mostly pre-existing) | 1077/1128 (95.5%) | 44/50 (88.0%) | 58/58 (100%) | 154/159 (96.9%) |
| Aggregate (summed counters, both files, no files missing or excluded) | 2233/2294 (97.3%) | 90/96 (93.8%) | 112/112 (100%) | 318/323 (98.5%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NotesBackupContributor.kt:67, 145` (5 instructions each, no line or branch missed) | Exception-path bytecode of Kotlin `use {}` closing the JDBC streams | Justified gap: close-on-throw path generated by the compiler, no task behavior |
| `FilesBackupContributor.kt:51, 67, 71, 74, 75, 80, 81, 83, 84, 87, 88, 89, 205` | Pre-existing files import validation (missing/cyclic folders, missing or mismatching blobs) and blob `use {}` close path, not changed by this task | Out of task scope (owned and tested by the files backup task); the task's `paths()` lines 60-62 are fully covered |

## Unable to test

- Nothing within the acceptance criteria. Behavior outside the task (links and files restore) belongs to other
  contributors and is exercised only indirectly by the round-trip test.

## Summary

Tests added and all validation passed. One test updated (escaping boundaries); no production defects found. The parent
worker must commit the updated test file together with this report.
