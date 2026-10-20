# Fix Report

Date: 2026-10-21

Task: backup-restore - Backup restore: validation, full replace, CLI

Scope: findings CR-1 and CR-2 of `reviews/backup-restore/review.md`. CR-3 was deferred by the parent worker. Base commit 69c905745388ea54cce49ccbabf1bccb372ea894, task commits 991ae2a5, 3acad41f, 6d0473d6, 85dfdb7c.

## Fixed findings

### CR-1 - A contributor's non-InvalidBackupException in restoreWarnings turns the upload into a 500 and crashes the CLI

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/RestoreService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/backup/RestoreTestSupport.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/backup/RestoreWebTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/backup/RestoreCliTests.kt`

Problem:
`RestoreService.validate` caught only `InvalidBackupException` from `backups.restoreWarnings`. Any other runtime exception from a contributor reached `ProblemDetailsAdvice` as a generic 500, logged at ERROR with its message, and crashed the CLI before it printed the report.

Fix:
`validate` now also catches `RuntimeException` from `restoreWarnings`. It adds the fixed problem "The backup's data is not valid" to the report, logs only the exception class at WARN and the details at DEBUG. The upload still answers 200 with `valid: false` and no id. The CLI prints the report and exits with 1.

Note on the trigger named in the review: on the current code, `"recoveryCodes":[null]` in `data/user.json` (with a recomputed sha256) does not cause a NullPointerException. The canonical mapper rejects the null element, and `ZipBackupReader.json` reports it as "data/user.json is not valid". The requested regression tests for `[null]` were added with that observed result: the web test checks for a 200 report with `valid: false`, and the CLI test checks for exit 1 with the report. The general case (a contributor's `restoreWarnings` throwing another exception) still existed. It is covered by a new `failWarnings` switch on the test-only `FailingImportBackupContributor`, which throws an `IllegalStateException` whose message quotes "Secret title". The test checks that the report carries only the content-free problem, that the log contains the class name and that "Secret title" appears neither in the response nor in the log output.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.backup.*' --tests '*OpenApiContractTest'` - Pass (117 tests, 0 failures)

### CR-2 - Failures of the pre-restore export bypass the content-free logging

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/RestoreService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/backup/api/RestoreController.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/backup/api/RestoreCli.kt`
- `api/openapi.yaml` (regenerated)
- `server/src/test/kotlin/ch/sonensei/cockpit/backup/RestoreWebTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/backup/RestoreCliTests.kt`

Problem:
In `replace`, exceptions from `preRestoreBackup()` (every contributor's export) propagated unchanged. The REST path logged them at ERROR with the full message, which may quote user content. In the CLI they reached the JVM's default handler.

Fix:
`replace` wraps `preRestoreBackup()`. It logs only the exception class at WARN and the details at DEBUG, then throws the new content-free `PreRestoreBackupFailedException` ("The current data could not be backed up; nothing was changed"). `RestoreController` maps it to a 500 Problem Details response with that fixed detail. 500 fits because the failure is on the server's side, not in the uploaded backup (422 means "the backup could not be restored"). The 500 response is documented in the `confirmRestore` OpenAPI annotations. `api/openapi.yaml` was regenerated. `npm run api:gen` produced no change to the web client, because error responses are not generated into it. `RestoreCli` prints the message and returns exit code 1. Its KDoc lists the new case.

Tests: the existing web tests for a failing pre-restore export now also check the `application/problem+json` content type and the fixed `detail`. A new CLI test puts a file where `pre-restore/` belongs, so the pre-restore backup fails. It checks for exit 1, the message, that the database is unchanged and that nothing was stashed.

Validation:
- `cd server && UPDATE_OPENAPI=1 ./gradlew test --tests 'ch.sonensei.cockpit.backup.*' --tests '*OpenApiContractTest'` - Pass
- `cd web && npm run api:gen` - Pass (no diff)

## Not fixed

### CR-3 - Every upload hashes the whole backup twice while holding the RestoreService lock

Status:
Out of scope

Reason:
The parent worker deferred this finding explicitly.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` - Pass
- `cd server && UPDATE_OPENAPI=1 ./gradlew test --tests 'ch.sonensei.cockpit.backup.*' --tests '*OpenApiContractTest'` - Pass (117 tests, 0 failures, 0 errors)

Full test suite:
- `cd server && ./gradlew check` - Pass (2199 tests, 0 failures, 0 errors, 0 skipped)

Lint/type-check/build:
- ktlint and the compile run as part of `./gradlew check` - Pass

`.taskcheck`:
- `sh scripts/check.sh` - Pass ("all checks passed")

Remaining unresolved findings:
1 (CR-3, deferred by the parent worker)

## Final status

All validated findings fixed and validation passed.
