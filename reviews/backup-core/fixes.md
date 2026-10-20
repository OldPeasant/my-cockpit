# Fix Report

Date: 2026-10-20

Task: backup-core - Backup export: zip format and orchestration

Scope: current task; findings CR-1 and CR-2 from `reviews/backup-core/review.md`

## Fixed findings

### CR-1 — A backup job can stay RUNNING forever and block every later backup

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupJobService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupJobServiceTests.kt`

Problem:
`run()` caught only `Exception`. An `Error` from a contributor (for example `NotImplementedError` from `TODO()`), or an `IOException` from `Files.deleteIfExists` inside the catch block, left the job RUNNING. After that, `start()` answered 409 until the server restarted.

Fix:
`run()` now catches `Throwable`. Deleting the temp file is wrapped in `runCatching`, so it can no longer skip the FAILED update. The job is always marked FAILED. A fatal `VirtualMachineError` is rethrown after the job has been marked. The logging is unchanged: the class name at WARN, the full exception only at DEBUG. Added the regression test `a contributor throwing an Error fails the job and does not block the next backup`. Its contributor calls `TODO()`. The test asserts that the job becomes FAILED with `finishedAt`, that `zip()` returns 409, and that a new backup then starts and completes.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.backup.*'` — Pass (BackupJobServiceTests 6/6, including the new test; all 8 backup suites, 66 tests, 0 failures)

### CR-2 — KDoc points to a symbol that does not exist

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/backup/infra/ZipBackupWriter.kt`

Problem:
The class KDoc linked to `[CanonicalJson]`, which does not exist.

Fix:
The link now points to `[BackupFormat.canonical]`. `BackupFormat` is already imported in that file.

Validation:
- `cd server && ./gradlew ktlintCheck` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` — no changes needed beyond the edits
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.backup.*'` — Pass (66 tests, 0 failures, 0 skipped)

Full test suite:
- `sh scripts/check.sh` (`.taskcheck`, lint and unit tests of all parts) — Pass ("all checks passed")

Lint/type-check/build:
- `cd server && ./gradlew ktlintCheck` — Pass
- `./gradlew detekt` — Not applicable (the server project has no detekt task)

`.taskcheck`:
- Pass

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
