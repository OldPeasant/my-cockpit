# Test Report

Date: 2026-10-21

Task: backup-contrib-notes - Backup contributor: notes

Scope: base `3b0efeeb4508453483f15cc2bddff432b0ae40d4` (supplied by the parent) .. HEAD
`323bf50ae9a31271f809a44c7777730c9a04cb19` (branch `main`). Task commits: `git log 3b0efeeb..HEAD`, 14 commits, all prefixed
`backup-contrib-notes:` or `quality:`. Production files from `git diff --name-only 3b0efeeb..HEAD -- server/src/main web/src android`:
`NotesBackupContributor.kt` (new) and `FilesBackupContributor.kt` (`paths()`, added lines 54-65). The final code includes
CR-3 and CR-4 (front matter escaping of YAML-forbidden, line-break and invisible characters). No uncommitted source or test
changes in `server/` at measurement time (the only uncommitted file in the clone is `reviews/backup-contrib-notes/review.md`,
not a test input). This report replaces the earlier one, which described HEAD `171b71f8` / base `1a44a931` and had no
full-suite result.

Requirements:
- `tasks/backup-contrib-notes.md` acceptance criteria and recorded assumptions (mirror only for notes not in the Trash,
  UTC creation-day file date, slug rules, clash numbering, front matter fields as YAML, image link rewriting, `paths()` in the
  files contributor, links read from the core link table)
- `SPEC.md` section 10.2 (backup layout), section 14 (round-trip test)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`

## Test files changed

- None in this run. The task's test files (`server/src/test/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributorTests.kt`,
  `server/src/test/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupFixture.kt`) are committed in the task commits and were
  reviewed again against the final code.

## Tests added or updated

Count: 0

The earlier test-writer pass already extended the escaping test for CR-3/CR-4 (printable neighbours of every escaped range
stay raw). The 18 contributor tests and the round-trip fixture cover every acceptance criterion and the recorded assumptions;
coverage below shows no uncovered task behavior, so no further tests were needed.

## Requirement coverage

Command for all rows (run from the repo root): `cd server && ./gradlew test --tests '*NotesBackupContributorTests' --tests '*BackupRoundTripTest' jacocoTestReport ktlintCheck --console=plain`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| `data/notes.jsonl` with every note (Trash included), fields and sorted tags | `notes jsonl holds every note with its fields and sorted tags, the ones in the Trash included` | focused command above | Pass |
| `data/note_revisions.jsonl` | `note revisions jsonl holds every revision, by note and number` | focused command above | Pass |
| `readable/notes/YYYY-MM-DD-SLUG.md`, only notes not in the Trash, UTC creation day, slug rules, clash numbering | `each note not in the Trash is a Markdown file named after its creation day and title`, `notes with the same day and title get numbered names, the older one keeps the name`, `slugs are lower case ASCII words of at most 60 characters`, `slugs keep exactly 60 characters, cut longer ones and trim separators at both ends`, `the file date is the creation day in UTC, so the same title on another day keeps its name`, `a numbered name never overwrites a note whose own title gives that name` | focused command above | Pass |
| Front matter with id, tags, links (both directions, each once, oldest first) | `front matter holds id, title, tags, links in both directions, times and state, then the body`, `front matter lists links from other item types to the note, but not links between other items`, `an untitled note uses its derived title and empty tag and link lists in the front matter`, `the body keeps a single final newline and an empty body leaves only the front matter` | focused command above | Pass |
| Front matter is valid YAML; forbidden/line-break/invisible characters escaped, printable neighbours stay raw (CR-3/CR-4) | `front matter is valid YAML that gives back titles and tags with quotes, backslashes and control characters` | focused command above | Pass |
| Image links rewritten to `../../files/...`; unknown refs unchanged | `image references point to the file in the backup, other references stay`, `image links resolve to the exported file entries, also for nested folders, clashing names and upper-case ids` | focused command above | Pass |
| `FilesBackupContributor.paths()` matches export paths, skips missing ids, empty input | `files paths returns the export path of existing files only, and nothing for no ids` | focused command above | Pass |
| Restore keeps ids/times/versions/Trash state, reindexes search for notes not in the Trash | `import restores notes, tags and revisions with their ids, times, versions and Trash state`, `import indexes the notes not in the Trash for search, with the body as plain text` | focused command above | Pass |
| Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with `NotesBackupFixture` (notes with images, tags, revisions) | `BackupRoundTripTest` / `every contributor's data survives export, wipe and import unchanged(Path)` (Testcontainers Postgres) | focused command above | Pass (1 test, 0 skipped) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*NotesBackupContributorTests' --tests '*BackupRoundTripTest' jacocoTestReport ktlintCheck --console=plain`

(The repo-root form `./server/gradlew -p server ...` was denied by the sandbox; the `cd server` form was used instead, same tasks
and selection.)

Result:

Pass. BUILD SUCCESSFUL in 38s, exit 0, finished 2026-10-21 08:56:12 +0200 (about 08:55:34 to 08:56:12 +0200). `test` and
`jacocoTestReport` were executed; compile and ktlint tasks were up-to-date against the unchanged sources (ktlint up-to-date
means its last run on these exact inputs found no violations). JUnit XML in `server/build/test-results/test/` (written
08:56:11 +0200): `NotesBackupContributorTests` 18 tests, 0 failures, 0 errors, 0 skipped; `BackupRoundTripTest` 1 test,
0 failures, 0 errors, 0 skipped.

## Full test execution

Command:

`sh scripts/check.sh` (= `.taskcheck`; includes `cd server && ./gradlew check`, the web check and the Android checks), run by
the parent worker on HEAD `323bf50a`.

Result:

Pass, as reported by the parent worker: exit 0, output ending in "== all checks passed". Not run by the test-writer. The
JUnit XML of that run (e.g. `NotesBackupContributorTests` written 08:54:06 +0200) could not be counted afterwards: the focused
run above regenerated `server/build/test-results/test/`, which now holds only the two focused classes. The per-test counts of
the full run are therefore not reproduced here; the parent's exit status is the evidence.

## .taskcheck

Result:

Pass (run by the parent worker on HEAD `323bf50a`, exit 0, "== all checks passed"; see "Full test execution").

## Bugs found

None.

## Coverage

Status: verified.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. Method: JaCoCo XML `line` entries intersected with the added lines of
`git diff 3b0efeeb..HEAD -- server/src/main`. `NotesBackupContributor.kt` is a new file, so all its executable lines are
changed lines: 164/164 lines, 46/46 branches. `FilesBackupContributor.kt` added lines 54-65 (`paths()`); executable lines
60, 61, 62: 3/3 covered (line 60: 2/2 branches, 0 missed instructions on all three). Total changed lines 167/167 (100%),
branches on changed lines 48/48 (100%).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `backup-contrib-notes`, base
  `3b0efeeb4508453483f15cc2bddff432b0ae40d4`, HEAD `323bf50ae9a31271f809a44c7777730c9a04cb19`. The parent supplied the base
  and the task commits (`git log 3b0efeeb..HEAD`). Production files come from
  `git diff --name-only 3b0efeeb..HEAD -- server/src/main web/src android` and match the task's touches plus the recorded
  `paths()` assumption.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `323bf50a`, no uncommitted inputs under `server/`;
  sha256 `NotesBackupContributor.kt` 1d4f4bd4d8f35e956765cbc57ab007299597de5c1ca03809b62e67b83c34c304,
  `FilesBackupContributor.kt` f3ac7a01bcad1a96bbe62fb8418db61d279bd06235c33fbfc385b95b27e8457c,
  `NotesBackupContributorTests.kt` e250a0a22f24cf9907569a17929ec8546adcf3f98fa24936688a72da3f7293eb,
  `NotesBackupFixture.kt` f625f567313c41286660e59746c134c28f23430f9d7ff7d6c9b3f59327941983.
- Exact included files; renamed/deleted/excluded files and reasons:
  `server/src/main/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributor.kt`,
  `server/src/main/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributor.kt`. No renames or deletions. Excluded:
  test files and `reviews/backup-contrib-notes/*` (not production code).
- Provider/version, command, working directory, and test selection: JaCoCo 0.8.15.202606040825 through the project's Gradle
  `jacoco` plugin (Gradle 9.8.1); `./gradlew test --tests '*NotesBackupContributorTests' --tests '*BackupRoundTripTest'
  jacocoTestReport ktlintCheck --console=plain` in `server/`; selection: those two test classes only.
- Run start/completion time and exit status: 2026-10-21 about 08:55:34 to 08:56:12 +0200, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`,
  sha256 88f76d5520146118b59a68ef245298142cfef18d1949faf86a8e89b6e75d3454, written 2026-10-21 08:56:12 +0200; execution
  data `server/build/jacoco/test.exec` written 08:56:11 +0200.
- Evidence outputs are fresh and inputs stayed unchanged: `test` and `jacocoTestReport` executed in this run (not up-to-date),
  and the exec file, report and JUnit XML timestamps fall within the run. Source hashes after the run match HEAD and the
  hashes recorded for the same files in the previous pass.
- Final run after latest test additions and production fixes: yes (CR-3/CR-4 and the latest test update are in HEAD
  `323bf50a`; nothing changed after the run).

Values below use covered/total (percentage). JaCoCo has no statement counter, so the Statements column shows INSTRUCTION.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributor.kt` | 1156/1166 (99.1%) | 46/46 (100%) | 54/54 (100%) | 164/164 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributor.kt` (whole file, mostly pre-existing) | 1077/1128 (95.5%) | 44/50 (88.0%) | 58/58 (100%) | 154/159 (96.9%) |
| Aggregate (summed counters, both files, no files missing or excluded) | 2233/2294 (97.3%) | 90/96 (93.8%) | 112/112 (100%) | 318/323 (98.5%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NotesBackupContributor.kt:67, 145` (some instructions missed, no line or branch missed) | Exception-path bytecode of Kotlin `use {}` closing the JDBC streams | Justified gap: compiler-generated close-on-throw path, no task behavior |
| `FilesBackupContributor.kt:51, 67, 71, 74, 75, 80, 81, 83, 84, 87, 88, 89, 205` | Pre-existing files import validation (missing/cyclic folders, missing or mismatching blobs) and blob `use {}` close path, not changed by this task | Out of task scope (owned and tested by the files backup task); the task's `paths()` lines 60-62 are fully covered |

## Unable to test

- Nothing within the acceptance criteria. Behavior outside the task (links and files restore) belongs to other
  contributors and is exercised only indirectly by the round-trip test.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. Focused tests (19) and the round-trip test pass on
HEAD `323bf50a` with fresh JaCoCo coverage; the full `.taskcheck` passed in the parent's run. The parent worker must commit
this report.
