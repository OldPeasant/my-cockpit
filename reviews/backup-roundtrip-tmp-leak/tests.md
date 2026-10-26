# Test Report

Date: 2026-10-26

Task: backup-roundtrip-tmp-leak - BackupRoundTripTest leaks a temp data dir per run

Scope: `git diff 2025f6562..2eac0d71d62d45e31e192c8945a92e9bf18fc001`. Task-owned commit: 2eac0d71d (test(backup): delete the round-trip data dir after the test class). The only other file in the range is `tasks/backup-roundtrip-tmp-leak.md` (claim commit 1f0a9f1c7, board state only). Branch main, working tree clean at the start of this run.

Requirements:
- tasks/backup-roundtrip-tmp-leak.md (acceptance criteria 1 to 3)
- docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md, AGENTS.md
- Parent instructions (scope, Gradle result and /tmp dir counts reported by the parent)

## Test files changed

None in this run. The task commit itself changes `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupRoundTripTest.kt` (git blob d18e0d9c88fa1cc57d985440b240bbf9d4b786cb at HEAD): it adds a companion `@JvmStatic @AfterAll fun deleteDataDir()` that calls `dataDir.toFile().deleteRecursively()`.

## Tests added or updated

Count: 0

The change is test infrastructure (a cleanup hook in a test class). A test of that hook would be artificial: it would have to run JUnit on `BackupRoundTripTest` from inside another test and inspect /tmp, which duplicates criterion 2's before/after count. The behavior is checked by that count instead. Review of the hook:
- `@AfterAll` on a `@JvmStatic` companion function compiles to a static method on `BackupRoundTripTest`; JUnit 5 runs class-level `@AfterAll` methods also when a test in the class failed, so criterion 1's "also when a test fails" holds.
- `deleteRecursively()` removes the directory and its content; it returns false instead of throwing on a partial failure, so cleanup can never turn a passing class into a failing one.
- The round-trip test body, `wipe()`, `@DynamicPropertySource` and the data dir creation are unchanged (diff only adds the import and the hook), so the round trip it tests is unchanged.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: data dir deleted after the test class, also on failure; round trip unchanged | `BackupRoundTripTest.deleteDataDir` (`@AfterAll`), `every contributor's data survives export, wipe and import unchanged` | `git show 2eac0d71d` (code inspection); round trip run by the parent: `server/gradlew -p server test --tests 'ch.sonensei.cockpit.backup.*'` | Pass (inspection + parent run; the failure path was not exercised by forcing a failing test) |
| AC2: backup tests pass, /tmp/cockpit-round-trip-data* count unchanged | all 14 classes in `ch.sonensei.cockpit.backup.*` | `server/gradlew -p server test --tests 'ch.sonensei.cockpit.backup.*'`, count via python glob before/after (run by the parent) | Pass: BUILD SUCCESSFUL, 176 tests, 0 failures; 10 dirs before, 10 after |
| AC3: `sh scripts/check.sh` passes | whole check | `sh scripts/check.sh` | Not run: not run by the test-writer (sub-agents cannot run Gradle here); the parent must run it |

## Focused test execution

Command:

`server/gradlew -p server test --tests 'ch.sonensei.cockpit.backup.*'` (run by the parent, not by this test-writer)

Result:

Pass (BUILD SUCCESSFUL, 14 classes / 176 tests, 0 failures; BackupRoundTripTest 1 test passed)

## Full test execution

Command:

`sh scripts/check.sh`

Result:

Not run by the test-writer (Gradle cannot run in the sub-agent sandbox). The parent runs it for AC3.

## .taskcheck

Result:

Not run by the test-writer (same reason); the parent runs it before `done`.

## Bugs found

None.

## Coverage

Status: not applicable - the task changes no production file.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no changed production lines).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: backup-roundtrip-tmp-leak; base 2025f6562 (from the parent, `git merge-base HEAD origin/main`); HEAD 2eac0d71d62d45e31e192c8945a92e9bf18fc001; task-owned commit 2eac0d71d; file scope from the task's `touches` and the commit diff.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 2eac0d71d, clean working tree; test file blob d18e0d9c88fa1cc57d985440b240bbf9d4b786cb.
- Exact included files; renamed/deleted/excluded files and reasons: `git diff --name-only 2025f6562..HEAD -- server/src/main web/src android/app/src/main` printed nothing. Excluded: `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupRoundTripTest.kt` (test code, not instrumented as production), `tasks/backup-roundtrip-tmp-leak.md` (board state).
- Provider/version, command, working directory, and test selection: no coverage run; JaCoCo (`cd server && ./gradlew test jacocoTestReport`) would report no task-scoped file.
- Run start/completion time and exit status: N/A (no run).
- Generated report paths and report hashes/run identifier: N/A (no report generated).
- Evidence outputs are fresh and inputs stayed unchanged: N/A; no coverage numbers are reported.
- Final run after latest test additions and production fixes: N/A (no test additions, no production fixes).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production file changed) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| none | - | No production code in scope |

## Unable to test

- AC1 "also when a test fails": not exercised by forcing a failure; it relies on JUnit 5's documented `@AfterAll` semantics. No follow-up needed.
- AC3 `sh scripts/check.sh`: not run by the test-writer; the parent must run it.
- Out of scope, possible follow-up: other test classes create a companion-object temp data dir the same way and contain no `@AfterAll`/`deleteRecursively` (grep): `MailBackupContributorTests`, `FilesBackupContributorTests`, `GtdBackupContributorTests`, `BackupSecretsTest`, `RestoreCliTests`, `CalendarBackupContributorTests`, `NotesBackupContributorTests`, `ContactsBackupContributorTests`, `BookmarksBackupContributorTests` (`cockpit-*-data` prefixes), and `SpaFallbackTests` (`cockpit-spa`). They probably leak a dir per run too, likely smaller than the ~61 MB round-trip dir; not verified here. The parent may want a separate task for them.

## Summary

No additional tests were necessary for the reviewed scope.
