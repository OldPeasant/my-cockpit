# Test Report

Date: 2026-10-25

Task: backup-contrib-bookmarks - Backup contributor: bookmarks

Scope: base `077ea2286587c79d050d529dd240316c9e822070` .. HEAD `e7a98bbe0f8232e38a2639b711069bdd82435bc4` (branch `main`, local). Task code commit `e7a98bbe0` (a rebase of `c553310e3` supplied by the parent; `git diff c553310e3 e7a98bbe0` touches only `tasks/*.md`, so the code is identical). Production file in scope: `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/backup/BookmarksBackupContributor.kt`. Excluded: `tasks/backup-contrib-bookmarks.md`, `tasks/backup-contrib-journal.md` (task metadata; the journal change belongs to another agent's claim). Uncommitted: one new test in `BookmarksBackupContributorTests.kt` (this report's addition).

Requirements:
- `tasks/backup-contrib-bookmarks.md` acceptance criteria and the recorded assumption (flat list, newest first, no Trash, comma in tag -> space, TOREAD for unread read-later, ICON_URI, no snapshot links)
- SPEC.md 8.4, 10.2 as cited by the implementation KDoc
- AGENTS.md project notes, docs/ARCHITECTURE.md "Coverage" (`./gradlew test jacocoTestReport`)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/backup/BookmarksBackupContributorTests.kt`

## Tests added or updated

Count: 1 added (7 tests in the class now; the 6 existing ones are unchanged).

Added: `the html falls back to the url for a blank title, drops a blank description and keeps every entry on one line`. It covers:
- a whitespace-only title (`" \t "`) falls back to the URL; a whitespace-only description (`" \r\n "`) produces no `<DD>`;
- CRLF, lone CR and LF in title and description each become exactly one space, and the file contains no `\r`;
- markup injection in the description (`</DL><DT><A HREF=...>`) is escaped: the importer still sees exactly 2 bookmarks and reads the text back literally;
- tags containing `"` and `<`/`>` are escaped in `TAGS` and read back intact, sorted.

Reviewed and judged sufficient as they were: the existing tests for all-field JSONL export (Trash included, sorted tags, unicode, multi-line), the empty export (no html file), the Firefox-style parse of the mirror (order, ties by id desc, ADD_DATE/LAST_MODIFIED, ICON_URI, TAGS with comma -> space, TOREAD only for unread read-later, missing title -> URL, no tags/icon/description), all-trashed -> no html, the import round trip of rows and tags (PENDING metadata included), and search re-indexing without trashed items.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `data/bookmarks.jsonl` with every bookmark | `bookmarks jsonl holds every bookmark with all fields and sorted tags, the ones in the Trash included`; `bookmarks jsonl is present and empty when there are no bookmarks` | `cd server && ./gradlew test --tests '*BookmarksBackupContributorTests' --tests '*BackupRoundTripTest' jacocoTestReport ktlintCheck` | Pass |
| AC1: `readable/bookmarks.html` in Netscape format, imports in Firefox (parse test) | `the html is a Netscape bookmark file that Firefox's importer reads back, newest first, without the Trash`; `the html falls back to the url for a blank title, drops a blank description and keeps every entry on one line` (new); `there is no html when every bookmark is in the Trash` | same | Pass (the import is simulated with jsoup following Firefox's `BookmarkHTMLUtils` structure. No real Firefox import was run.) |
| Restore keeps ids, times, versions, tags, Trash and metadata state; re-indexes search | `import restores every bookmark with its id, times, version, tags, Trash and metadata state`; `import indexes the bookmarks not in the Trash for search, the trashed ones not` | same | Pass |
| AC2: `BackupRoundTripTest` passes with `BookmarksBackupFixture` seeding bookmarks | `BackupRoundTripTest` (1 test) | same | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*BookmarksBackupContributorTests' --tests '*BackupRoundTripTest' jacocoTestReport ktlintCheck`

Result:

Pass. BUILD SUCCESSFUL. BookmarksBackupContributorTests 7/7 (0 failures, 0 errors, 0 skipped), BackupRoundTripTest 1/1. ktlint main and test source sets clean.

## Full test execution

Command:

`cd server && ./gradlew check` (2026-10-25T21:07:18+01:00 to 21:12:41+01:00)

Result:

Pass (BUILD SUCCESSFUL in 5m 22s).

## .taskcheck

Result:

Not run by the test-writer. `scripts/check.sh` also builds and tests web and android, which this task does not touch. Its server part (`./gradlew check`) passed above. The parent runs `.taskcheck` before `done`.

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. The file is entirely new in this task, so whole-file coverage equals changed-line coverage here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `backup-contrib-bookmarks`, base `077ea2286587c79d050d529dd240316c9e822070`, HEAD `e7a98bbe0f8232e38a2639b711069bdd82435bc4`. The scope comes from the parent's file list and `git diff --stat 077ea2286..HEAD`, matching the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 `BookmarksBackupContributor.kt` dce802a8da5bc5a32c5438e3297a08d6bd2fe3a491d01b3f63e08adfeef44e12; `BookmarksBackupContributorTests.kt` (with the uncommitted new test) aa049f724eb68036c80e630b1ed4078ba1afe7af1cb94ecfc9812e3c1d6d35ee; `BookmarksBackupFixture.kt` 2f74c70ae9add7bd3b621fcf3a59a9f1aff7bfcef4989d1ef2e18e0b17391685.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/backup/BookmarksBackupContributor.kt` (classes `BookmarksBackupContributor`, `BookmarksBackupContributor$Companion`, `BookmarkBackup`). There are no renames or deletions. Excluded: the test fixture and tests (test code) and `tasks/*.md` (metadata).
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin, project default version. Command `./gradlew test --tests '*BookmarksBackupContributorTests' --tests '*BackupRoundTripTest' jacocoTestReport ktlintCheck`, run in `server/`.
- Run start/completion time and exit status: JaCoCo session `dell26-2ac5aa56`, start 1792958788099, dump 1792958809829 (epoch ms, 2026-10-25 around 21:06 +01:00). Exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 5c00468420349a770c2a9258b1f89d7c160ef80f5386367b8e50f9696d64325d (the previous run's report had a08c68fc..., which shows this one was regenerated). Raw output is Git-ignored. A later `./gradlew check` reran the whole suite but those counters are not used here.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The test task re-executed after the test file changed, and the hashes above were taken after the run with no edits in between.
- Final run after latest test additions and production fixes: yes. There were no production changes.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Instructions (JaCoCo, used for statements) | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/backup/BookmarksBackupContributor.kt` | 707/712 (99.3%) | 32/32 (100%) | 35/35 (100%) | 105/105 (100%) |

Per class: `BookmarksBackupContributor` 392/397 instr, 6/6 branches, 73/73 lines. `$Companion` (`netscapeEntry`/`escape`) 222/222 instr, 26/26 branches, 16/16 lines; before the new test this was 218/222 instr and 22/26 branches, missing lines 171 and 172. `BookmarkBackup` 93/93 instr, 16/16 lines.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `BookmarksBackupContributor.kt:171-172` (blank title / blank description branches) | mirror readability: blank title -> URL, blank description -> no `<DD>` | New test `the html falls back to the url for a blank title, ...`. Now covered |
| `BookmarksBackupContributor.kt:109` (5 instructions in `.use { }`, the exceptional close path) | stream closing when the consumer throws | Justified gap: Kotlin `use` bytecode for the exception path. Testing it would need a failing writer and is not task behavior |

## Unable to test

- A real Firefox import of `readable/bookmarks.html` was not run (no browser automation for this server-side criterion). The criterion asks for a parse test, and that is what exists: a jsoup parse that follows Firefox's `BookmarkHTMLUtils` traversal (`DL` > `DT` > `A` attributes, following `DD`). No follow-up is needed unless a human wants a manual import check.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient (32/32 branches, 105/105 lines), and no production defects were found.
