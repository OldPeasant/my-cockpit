# Test Report

Date: 2026-10-21

Task: backup-contrib-notes - Backup contributor: notes

Scope: base `8da4e3773b8ef26e5a0991805bd32fc0f9eb964e` .. HEAD `97ca4bc8f27b8e83c28996aaf66570f37f7d8337` (branch `main`),
task commits from `git log 8da4e377..HEAD` (all prefixed `backup-contrib-notes:` or `quality:`, supplied by the parent).
Production changes only in c1ec6bec and 27f4062f (`git log 8da4e377..HEAD -- server/src/main`). Working tree clean at
measurement; no uncommitted inputs. This re-run replaces the earlier report, which was measured against a pre-rebase base
(`d8bf81f2`). The production and test sources are byte-identical to that run (same sha256, see Coverage).

Requirements:
- `tasks/backup-contrib-notes.md` acceptance criteria and recorded assumptions (mirror only for notes not in the Trash,
  UTC creation-day file date, slug rules, clash numbering, front matter fields, image link rewriting, `paths()` in files contributor,
  links read from the core link table)
- `SPEC.md` section 10.2 (backup layout), section 14 (round-trip test)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`

## Test files changed

None in this session. The task's existing test files were reviewed:

- `server/src/test/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributorTests.kt` (18 tests, added by the task commits)
- `server/src/test/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupFixture.kt` (round-trip `BackupFixture`)

## Tests added or updated

Count: 0

The existing 18 contributor tests plus the fixture cover every acceptance criterion, the recorded assumptions,
boundaries (60-character slug limit, UTC day boundary, empty/whitespace/emoji-only titles, empty body), escaping
(YAML control characters, quotes, backslashes, percent-encoded file paths with spaces/brackets), error-ish paths
(image ids with no file, clashing file names, upper-case ids), and 100% of the task's changed lines and branches
(see Coverage). Production code is unchanged since the previous review, and no meaningful gap was found that would justify a new test.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| `data/notes.jsonl` with every note (Trash included), fields and sorted tags | `notes jsonl holds every note with its fields and sorted tags, the ones in the Trash included` | `cd server && ./gradlew test --tests '*NotesBackupContributorTests' --tests '*BackupRoundTripTest' jacocoTestReport` | Pass |
| `data/note_revisions.jsonl` | `note revisions jsonl holds every revision, by note and number` | same | Pass |
| `readable/notes/YYYY-MM-DD-SLUG.md`, only notes not in the Trash, UTC creation day, slug rules, clash numbering | `each note not in the Trash is a Markdown file named after its creation day and title`, `notes with the same day and title get numbered names, the older one keeps the name`, `slugs are lower case ASCII words of at most 60 characters`, `slugs keep exactly 60 characters, cut longer ones and trim separators at both ends`, `the file date is the creation day in UTC, so the same title on another day keeps its name`, `a numbered name never overwrites a note whose own title gives that name` | same | Pass |
| Front matter with id, tags, links (both directions, each once, oldest first), valid YAML | `front matter holds id, title, tags, links in both directions, times and state, then the body`, `front matter lists links from other item types to the note, but not links between other items`, `front matter is valid YAML that gives back titles and tags with quotes, backslashes and control characters`, `an untitled note uses its derived title and empty tag and link lists in the front matter`, `the body keeps a single final newline and an empty body leaves only the front matter` | same | Pass |
| Image links rewritten to `../../files/...`; unknown refs unchanged | `image references point to the file in the backup, other references stay`, `image links resolve to the exported file entries, also for nested folders, clashing names and upper-case ids` | same | Pass |
| `FilesBackupContributor.paths()` matches export paths, skips missing ids, empty input | `files paths returns the export path of existing files only, and nothing for no ids` | same | Pass |
| Restore keeps ids/times/versions/Trash state, reindexes search for non-Trash notes | `import restores notes, tags and revisions with their ids, times, versions and Trash state`, `import indexes the notes not in the Trash for search, with the body as plain text` | same | Pass |
| Round-trip test passes with `NotesBackupFixture` (notes with images, tags, revisions) | `BackupRoundTripTest` (discovers `NotesBackupFixture` on the classpath, Testcontainers Postgres) | same | Pass (1 test) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*NotesBackupContributorTests' --tests '*BackupRoundTripTest' jacocoTestReport`

Result:

Pass. BUILD SUCCESSFUL in 1m 6s (`compileKotlin`, `compileTestKotlin` and `test` executed, not up-to-date).
JUnit XML (`server/build/test-results/test/`, written 2026-10-21 08:21:19 +0200): `NotesBackupContributorTests` 18 tests,
0 failures, 0 errors, 0 skipped; `BackupRoundTripTest` 1 test, 0 failures, 0 errors, 0 skipped.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Not run in this session: the sandbox's permission system denied the command. The previous test-writer run on the same
production and test sources (identical sha256) passed it: BUILD SUCCESSFUL, 228 suites, 2925 tests, 0 failures
(2026-10-21T05:00:13Z to 05:10:29Z). Main has moved since then (new base `8da4e377`), so the parent worker should
re-run `sh scripts/check.sh` on the merged result before `done`. The web and Android parts are not touched by this task.

## .taskcheck

Result:

Not run by the test-writer (see above). The parent worker runs `sh scripts/check.sh` before `done`.

## Bugs found

None.

## Unable to test

- Nothing within the acceptance criteria. Behavior outside the task (links and files restore) belongs to other
  contributors and is exercised only indirectly by the round-trip test.

## Coverage

Status: verified.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. Method: JaCoCo XML `line` entries intersected with added lines of
`git diff 8da4e377..HEAD -- server/src/main` (266 added lines, including comments and blank lines). `NotesBackupContributor.kt` is a new
file, so all its executable lines are changed lines: 163/163 lines, 36/36 branches. `FilesBackupContributor.kt` added
lines 54-65 (`paths()`); executable lines 60, 61, 62: 3/3 lines covered (line 60: 2/2 branches covered). Total changed
lines: 166/166 (100%), changed-line branches 38/38 (100%).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `backup-contrib-notes`, base
  `8da4e3773b8ef26e5a0991805bd32fc0f9eb964e`, HEAD `97ca4bc8f27b8e83c28996aaf66570f37f7d8337`; base and commits supplied by the
  parent; production files from `git diff --name-only 8da4e377..HEAD -- server/src/main web/src android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): clean tree at HEAD `97ca4bc8`; sha256
  `NotesBackupContributor.kt` 4097d8ebedaecb3d50e0cd9fc3b1ddfe4285e07eef0e9eead76278f53acc2c0c,
  `FilesBackupContributor.kt` f3ac7a01bcad1a96bbe62fb8418db61d279bd06235c33fbfc385b95b27e8457c,
  `NotesBackupContributorTests.kt` d5edef511d523e68a5a361f4f502ed5e81474ff5025cc4d3a630a84af0907f77,
  `NotesBackupFixture.kt` f625f567313c41286660e59746c134c28f23430f9d7ff7d6c9b3f59327941983 (identical to the previous run).
- Exact included files; renamed/deleted/excluded files and reasons:
  `server/src/main/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributor.kt`,
  `server/src/main/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributor.kt`. No renames or deletions. Excluded:
  test files and `reviews/backup-contrib-notes/*` (not production code).
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (project config,
  Gradle 9.8.1); `./gradlew test --tests '*NotesBackupContributorTests' --tests '*BackupRoundTripTest' jacocoTestReport`
  in `server/`; selection: those two test classes only.
- Run start/completion time and exit status: 2026-10-21 about 06:20:10Z to 06:21:21Z, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`,
  sha256 7a0fac6d43438cc28e8a069220fd7f497b6f36587ab69197b352143af9ef1442, written 2026-10-21 08:21:20 +0200.
- Evidence outputs are fresh and inputs stayed unchanged: Gradle executed `compileKotlin`, `compileTestKotlin`, `test` and
  `jacocoTestReport` (not up-to-date) in this run; the report and the JUnit XML timestamps fall within the run; source
  hashes were checked before the run, and no files were edited during or after it.
- Final run after latest test additions and production fixes: yes (no test or production changes after the run).

Values below use covered/total (percentage). JaCoCo has no separate statement counter, so the Statements column shows INSTRUCTION.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributor.kt` | 1137/1147 (99.1%) | 36/36 (100%) | 54/54 (100%) | 163/163 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributor.kt` (whole file, mostly pre-existing) | 1077/1128 (95.5%) | 44/50 (88.0%) | 58/58 (100%) | 154/159 (96.9%) |
| Aggregate (summed counters, both files, no files missing or excluded) | 2214/2275 (97.3%) | 80/86 (93.0%) | 112/112 (100%) | 317/322 (98.4%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NotesBackupContributor.kt:67, 145` (5 instructions each, no line or branch missed) | Exception-path bytecode of Kotlin `use {}` closing the JDBC streams | Justified gap: compiler-generated close-on-throw path, no task behavior |
| `FilesBackupContributor.kt:51, 67, 71, 74, 75, 80, 81, 83, 84, 87, 88, 89, 205` | Pre-existing files import validation (missing/cyclic folders, missing or mismatching blobs) and blob `use {}` close path, not changed by this task | Out of task scope (owned and tested by the files backup task); the task's `paths()` lines 60-62 are fully covered |

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. No tests were added in this session. The
full `./gradlew check` / `.taskcheck` could not be run by the test-writer in this session (permission denied) and is left
to the parent worker.
