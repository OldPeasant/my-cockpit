# Test Report

Date: 2026-10-26

Task: backup-round-trip-tmp-leak - BackupRoundTripTest leaks a 61 MB cockpit-round-trip-data temp dir per run, filling /tmp (Disk quota exceeded)

Scope: `git diff 20862ca863abb1e45d8169eca7566ca4970e7f9a..ae683aed699fceb94948c0d8139a8c1aad291450` (base = claim commit, task commit ae683aed6, HEAD at time of testing = ae683aed6, working tree clean). The BackupRoundTripTest fix itself landed earlier on main (commit 8215e1cc7) with the same pattern and was inspected at HEAD.

Requirements:
- `tasks/backup-round-trip-tmp-leak.md` acceptance criteria 1 and 2, plus the recorded assumption (12 classes, production work dirs left as is)
- `AGENTS.md` (test data, scratch files in `.tmp/`)

## Test files changed

None by the test-writer. The task commit itself changes 12 test files (test infrastructure only):

- `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupSecretsTest.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/backup/RestoreCliTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/backup/RestoreWebTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/backup/BookmarksBackupContributorTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/backup/CalendarBackupContributorTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/backup/ContactsBackupContributorTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/spa/SpaFallbackTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributorTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/backup/GtdBackupContributorTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/journal/backup/JournalBackupContributorTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/backup/MailBackupContributorTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributorTests.kt`

## Tests added or updated

Count: 0

No additional tests were written. The change is cleanup code in test classes; a unit test asserting that a test's own `@AfterAll` deletes a directory would only restate the four added lines. The meaningful evidence is behavioral: a full server test run with the test JVM's `java.io.tmpdir` redirected into the clone, followed by counting leftovers there. The parent worker runs that (see below).

### Static audit of temp-dir creation in `server/src/test`

Searched `server/src/test` for `createTempDirectory`, `createTempFile`, `createTempDir(`, `java.io.tmpdir`, `"/tmp`, `deleteOnExit`:

| Location | Kind | Cleanup |
|---|---|---|
| `BackupRoundTripTest.kt:151` `cockpit-round-trip-data` | companion, `Files.createTempDirectory` | `@AfterAll deleteRecursively` (8215e1cc7) |
| `BackupSecretsTest.kt:530`, `RestoreWebTests.kt:656`, `RestoreCliTests.kt:395` | companion data dirs | `@AfterAll deleteRecursively` (this task) |
| `{Bookmarks,Calendar,Contacts,Files,Gtd,Journal,Mail,Notes}BackupContributorTests.kt` `cockpit-*-backup-data` | companion data dirs | `@AfterAll deleteRecursively` (this task) |
| `SpaFallbackTests.kt:47` `cockpit-spa` | companion fake web root | `@AfterAll deleteRecursively` (this task) |
| `*BackupContributorTests.kt` `Files.createTempFile(work, "backup-"/"edited-", ".zip")` (8 files) | inside `work` | `work` is a JUnit `@TempDir lateinit var work: Path` in every one of these classes, cleaned by JUnit |
| `HeaderSyncDovecotTests.kt:68` `"/tmp/90-test.conf"` | path inside a Testcontainers container | not on the host; no leak |

All 13 companion-object temp dirs in `server/src/test` now have a matching `@AfterAll` (each file contains one `@AfterAll` and one `deleteRecursively`). No other server test creates a host temp dir/file outside JUnit `@TempDir`.

Remaining temp-dir sources reached by tests are in production code (out of this test-only task's scope, covered by the task's recorded assumption):

- `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupJobService.kt:49` `Files.createTempDirectory("cockpit-backups")`
- `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/RestoreService.kt:70` `Files.createTempDirectory("cockpit-restores")`

Per the assumption, their contents are removed on shutdown and only empty directories remain; the parent's leftover count in `.tmp/testtmp` will show whether that holds (expect only empty `cockpit-backups*` / `cockpit-restores*` dirs, if any). `BlobStore`/`ChunkStore`/`ThumbnailStore` create temp files in a directory they are given (under the data dir), not in `java.io.tmpdir`.

Observation (no defect): each class's data dir is registered via its own `@DynamicPropertySource`, so the Spring context cache key differs per class and no later class reuses a context whose data dir was deleted by an earlier class's `@AfterAll`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: round-trip data dir deleted after the class; full `./gradlew test` leaves no new `cockpit-round-trip-data*` in the temp dir | `BackupRoundTripTest` `@AfterAll` (8215e1cc7) + full server suite | Parent: `server/gradlew -p server test --rerun-tasks --init-script .tmp/tmpdir.gradle`, then list `.tmp/testtmp` | Not run by test-writer: sub-agents cannot run Gradle here; the parent runs it and records the result |
| AC2: other server tests that create large temp files/dirs clean them up | Static audit above (13/13 companion dirs have `@AfterAll`; per-test zips live in JUnit `@TempDir`) + same full run | grep of `server/src/test` (above); parent's full run + leftover count | Static audit: Pass. Runtime: Not run by test-writer (parent runs it) |

## Focused test execution

Command:

None (Gradle cannot be run by sub-agents in this environment).

Result:

Not run

## Full test execution

Command:

`server/gradlew -p server test --rerun-tasks --init-script .tmp/tmpdir.gradle` (run by the parent worker)

Result:

Not run by the test-writer; the parent records the result and the leftover count in `.tmp/testtmp`.

## .taskcheck

Result:

Not run (Gradle unavailable to sub-agents; the parent runs `sh scripts/check.sh`).

## Bugs found

None.

## Coverage

Status: not applicable - the task changes no production code (diff 20862ca86..ae683aed6 touches only 12 files under `server/src/test` and the task file), so the task coverage scope is empty. No percentage is reported.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no production lines changed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: backup-round-trip-tmp-leak; base 20862ca863abb1e45d8169eca7566ca4970e7f9a; HEAD ae683aed699fceb94948c0d8139a8c1aad291450; task commit ae683aed6 as supplied by the parent and confirmed with `git diff --stat`
- Source/test snapshot identifier (including uncommitted inputs): ae683aed699fceb94948c0d8139a8c1aad291450, clean tree (only this report added, untracked)
- Exact included files; renamed/deleted/excluded files and reasons: no production files; the 12 test files are excluded as tests; `tasks/backup-round-trip-tmp-leak.md` excluded as task metadata
- Provider/version, command, working directory, and test selection: none run (empty scope)
- Run start/completion time and exit status: N/A
- Generated report paths and report hashes/run identifier: N/A
- Evidence outputs are fresh and inputs stayed unchanged: N/A
- Final run after latest test additions and production fixes: N/A (no coverage run; no production code in scope)

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production files in scope) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| N/A | N/A | No production code changed |

## Unable to test

- Runtime confirmation that no temp dirs are left behind (AC1/AC2): needs a Gradle run, which sub-agents cannot execute here. The parent worker runs the full server suite with `java.io.tmpdir` redirected to `.tmp/testtmp` and counts leftovers; that result is the evidence for both criteria.

## Summary

No additional tests were necessary for the reviewed scope. A static audit found that every host temp dir created by `server/src/test` is now cleaned up, either by an `@AfterAll` in the companion object or by JUnit `@TempDir`. The parent worker's full-suite run with a redirected tmpdir provides the runtime evidence.
