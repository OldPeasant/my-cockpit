# Fix Report

Date: 2026-10-20

Task: backup-core - Backup export: zip format and orchestration

Scope: current task; findings CR-1 and CR-2 from `reviews/backup-core/review.md`. Report rewritten for the current revision (code at bc4ad499bb4bf7224e6ec162811d399ffe27d133); no further code changes were needed.

## Fixed findings

### CR-1 — A backup job can stay RUNNING forever and block every later backup

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupJobService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupJobServiceTests.kt`

Problem:
`run()` caught only `Exception`. An `Error` from a contributor (for example `NotImplementedError` thrown by Kotlin's not-implemented function), or an `IOException` from `Files.deleteIfExists` inside the catch block, left the job RUNNING. After that, `start()` answered 409 until the server restarted.

Fix:
`run()` now catches `Throwable`. Deleting the temp file is wrapped in `runCatching`, so it can no longer skip the FAILED update. The job is always marked FAILED with `finishedAt`. A fatal `VirtualMachineError` is rethrown after the job has been marked. The logging is unchanged: the class name at WARN, the full exception only at DEBUG. Verified at HEAD in `BackupJobService.kt` lines 118-126.

Regression tests in `BackupJobServiceTests.kt`:
- `a contributor throwing an Error fails the job and does not block the next backup` (its contributor calls Kotlin's not-implemented function, which throws NotImplementedError): asserts the job becomes FAILED with `finishedAt`, `zip()` returns 409, and a new backup then starts and completes.
- `a fatal VirtualMachineError still ends the job as FAILED, announces it, and the next backup runs`: asserts FAILED with `finishedAt`, no file name, the FAILED update event is published, `zip()` returns 409, and the executor still runs the next backup to completion.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.backup.BackupJobServiceTests'` — Pass (7 tests, 0 failures, 0 errors, 0 skipped)

### CR-2 — KDoc points to a symbol that does not exist

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/backup/infra/ZipBackupWriter.kt`

Problem:
The class KDoc linked to `[CanonicalJson]`, which does not exist.

Fix:
The link now points to `[BackupFormat.canonical]` (line 22). `BackupFormat` is already imported in that file. No reference to `CanonicalJson` remains under `server/src/main/kotlin/ch/sonensei/cockpit/backup`.

Validation:
- `cd server && ./gradlew ktlintCheck` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.backup.BackupJobServiceTests'` — Pass (7 tests, 0 failures, 0 errors, 0 skipped)

Full test suite:
- Not re-run in this pass (no code changed since the earlier fix pass, in which `sh scripts/check.sh` passed); the parent worker runs `.taskcheck` before `done`.

Lint/type-check/build:
- `cd server && ./gradlew ktlintCheck` — Pass

`.taskcheck`:
- Not run in this pass (no code changes; parent worker runs it)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
