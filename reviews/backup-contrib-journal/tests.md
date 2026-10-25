# Test Report

Date: 2026-10-25

Task: backup-contrib-journal - Backup contributor: journal and habits

Scope: immutable base `7f19d160fe030258f4257f77cad69f8ae903e7ad` .. measured HEAD
`d07a56f9e2e705086c8148c0ebac255948987890` (task commit `c39677daa` plus merge `d07a56f9e`), on branch `main`.
`git diff --stat 7f19d160f HEAD` lists exactly two files, both added by the task:
`server/src/main/kotlin/ch/sonensei/cockpit/journal/backup/JournalBackupContributor.kt` (production, 369 lines) and
`server/src/test/kotlin/ch/sonensei/cockpit/journal/backup/JournalBackupFixture.kt` (test fixture). Uncommitted at
measurement time: only the new, untracked test file listed below.

Requirements:
- `tasks/backup-contrib-journal.md` acceptance criteria 1 and 2
- `SPEC.md` §8.5, §10.2 as summarized in the contributor's KDoc; `docs/ARCHITECTURE.md` "Coverage"
- `docs/QUALITY.md`; conventions from sibling tests `NotesBackupContributorTests`, `GtdBackupContributorTests`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/journal/backup/JournalBackupContributorTests.kt` (new)

## Tests added or updated

Count: 16 (all in `JournalBackupContributorTests`, a `@SpringBootTest` against its own database
`journal_backup_contributor`, rows inserted with JDBC, all data fictitious)

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 `data/journal.jsonl`: every entry (Trash included), fields, sorted tags | `journal jsonl holds every entry with its fields and sorted tags, the ones in the Trash included`; `images of trashed entries stay in data but are not resolved in any mirror` | `cd server && ./gradlew test --tests '*JournalBackupContributorTests'` | Pass |
| AC1 `data/habits.json` (archived included, all settings) and `data/habit_checks.jsonl` (by habit, date) | `habits json holds every habit with its settings, archived ones included, and checks are by habit and date` | same | Pass |
| AC1 empty install: empty data files, header-only CSV, no mirrors, import of it | `an empty journal exports empty data files, a header-only habits csv and no entry mirrors, and imports again` | same | Pass |
| AC1 `readable/journal/<yyyy>/<date>.md` per active entry, Trash excluded | `each entry not in the Trash is a Markdown file under its year, named after its date`; `a trashed entry and an active entry on the same date give the active one's mirror only` | same | Pass |
| AC1 mirror front matter (id, date, mood, tags, links both directions each once, times) and body | `front matter holds id, date, mood, tags, links in both directions each once, and times, then the body`; `links between two entries appear in both mirrors with the journal_entry type`; `an entry without mood, tags or links has null and empty lists, and an empty body leaves only the front matter` | same | Pass |
| AC1 image references rewritten to `../../../files/<encoded path>` that exists in the archive; unknown files kept | `image references point to the file in the backup three levels up, missing files keep their reference` | same | Pass |
| AC1 YAML quoting of tags (quotes, backslashes, control chars, NEL/LS/PS, nonchars) | `front matter is valid YAML that gives back tags with quotes, backslashes and control characters` | same | Pass |
| AC1 `readable/habits.csv`: row per check by name (case-insensitive) then date, habits without checks one row with empty date/count, archived included | `habits csv has one row per check by habit name and date, habits without checks get one row without date` | same | Pass |
| AC1 CSV RFC 4180 quoting and formula guard | `habit names with commas, quotes, line breaks or formula starts are quoted and defused in the csv`; `csv fields are quoted per RFC 4180 and formula starts get a leading apostrophe` | same | Pass |
| Restore keeps ids, times, versions, Trash state, reminder id, weekdays | `import restores entries, tags, habits and checks with their ids, times, versions and Trash state` | same | Pass |
| Restore re-indexes habits and non-trashed entries for search | `import indexes habits and the entries not in the Trash for search` | same | Pass |
| AC2 round-trip with this module's `BackupFixture` | `BackupRoundTripTest` (existing) | `cd server && ./gradlew test --tests '*BackupRoundTripTest' --tests '*JournalBackupContributorTests' --rerun` | Pass (1 + 16 tests) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*JournalBackupContributorTests'`, then
`cd server && ./gradlew test --tests '*BackupRoundTripTest' --tests '*JournalBackupContributorTests' --rerun`;
`cd server && ./gradlew ktlintCheck`

Result:

Pass (16/16 new tests; round-trip 1/1; ktlintCheck BUILD SUCCESSFUL). Two tests were corrected during writing
(test errors, not production defects): a duplicate manual link violated the `link_manual_pair` index (the second
direction is now a `SYNC` link, as in the notes tests), and the search index stores a habit's absent body as `""`.

## Full test execution

Command:

`cd server && ./gradlew test --rerun jacocoTestReport`

Result:

Pass: 4457 tests, 0 failures, 0 errors, 0 skipped (from `server/build/test-results/test/*.xml`), BUILD SUCCESSFUL, exit 0.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` -> TFP gate tests 20 OK, server ktlint + tests, web eslint + tests (3453 passed) + build,
android ktlint + unit tests + lint, "all checks passed", exit 0.

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file coverage, because the only production file in scope was added in full by
the task (every executable line is a changed line): LINE 222/222 (100%). Changed-branch coverage was not measured
separately; the branch counts below are for the whole (entirely new) file.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `backup-contrib-journal`; base
  `7f19d160fe030258f4257f77cad69f8ae903e7ad`; HEAD `d07a56f9e2e705086c8148c0ebac255948987890`; task commits
  `c39677daa` + merge `d07a56f9e` as supplied by the parent and confirmed by `git diff --stat 7f19d160f HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 `eb68c1ca7765...e3ba88`
  (`JournalBackupContributor.kt`), `b7fc0b850642...e48965` (`JournalBackupContributorTests.kt`, untracked),
  `838aabc8b55e...6e7236` (`JournalBackupFixture.kt`); all three re-verified unchanged after the run.
- Exact included files; renamed/deleted/excluded files and reasons: included
  `server/src/main/kotlin/ch/sonensei/cockpit/journal/backup/JournalBackupContributor.kt`. Excluded
  `JournalBackupFixture.kt` (test code). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (default
  tool version of Gradle 9.8.1); `./gradlew test --rerun jacocoTestReport` in `server/`; full server test suite.
- Run start/completion time and exit status: 2026-10-25T21:39:57+01:00 to 2026-10-25T21:46:11+01:00, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (written 21:46:11, sha256 `2e022401cdd2f9d33eab3c1a9a9e62347ed988d5452ce22ad36707c7bbae9b34`), HTML beside it.
- Evidence outputs are fresh and inputs stayed unchanged: yes (`--rerun` forced test execution, the report timestamp
  matches the end of the run, and the snapshot hashes match).
- Final run after latest test additions and production fixes: yes (no production fixes; no test changes after the run).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/journal/backup/JournalBackupContributor.kt` | 1636/1651 instructions (99.1%) | 72/74 (97.3%) | 71/71 (100%) | 222/222 (100%) |

Aggregate = the single file above (no other production files in scope; none missing or unmeasured). JaCoCo reports
bytecode instructions, not statements.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `JournalBackupContributor.kt:72`, `:200` (5 instructions each) | Exception path of Kotlin `Stream.use {}` closing the stream | Justified gap: compiler-generated cleanup on failure, not task behavior |
| `JournalBackupContributor.kt:99` (1 of 2 branches) | `.orEmpty()` when `data/habits.json` is missing | Justified gap: every export of this contributor writes `habits.json` (also when empty, tested); a missing file would only occur in hand-edited archives |
| `JournalBackupContributor.kt:161` (1 of 2 branches) | Compiler-generated default-argument branch of `bufferedWriter` | Justified gap: no behavior |

## Unable to test

- Streaming with more than `FETCH_SIZE` (1000) rows and memory behavior for very large journals: not tested here
  (a long entry is covered by the round-trip fixture); no follow-up required for the acceptance criteria.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
