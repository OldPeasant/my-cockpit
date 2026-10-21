# Fix Report

Date: 2026-10-21

Task: backup-secrets - Backup secrets with passphrase

Scope: current task. The parent approved review findings CR-4, CR-5, CR-6 and CR-7 from `reviews/backup-secrets/review.md` for fixing. CR-1, CR-2 and CR-3 were assigned elsewhere by the parent.

## Fixed findings

### CR-4 - An empty backup passphrase is rejected, although the code treats it as none

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/backup/api/BackupController.kt`
- `api/openapi.yaml` (regenerated, descriptions only)
- `web/src/app/api/generated/models/start-backup-request.ts` (regenerated, doc comment only)
- `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupSecretsTest.kt`

Problem:
`@Size(min = 12)` rejected `{"passphrase":""}` with 400 before the controller's `takeIf { it.isNotEmpty() }` could run. The restore endpoint accepts `""` as "no passphrase", so the two endpoints behaved differently.

Fix:
`StartBackupRequest` now normalizes an empty passphrase to `null` in its constructor. Bean Validation checks the normalized field, so `""` starts a backup without secrets. A non-empty passphrase still needs 12 to 256 characters. The dead `takeIf` in the controller is gone. Only empty counts as absent, as on the restore endpoint. A whitespace-only passphrase of 12 or more characters stays a real passphrase, so it is not silently dropped. The schema shape (`minLength 12`, `maxLength 256`) did not change. Only the descriptions now say that empty counts as none. `api/openapi.yaml` was regenerated with `UPDATE_OPENAPI=1` and the web client with `npm run api:gen`, as `api/README.md` describes.

Validation:
- Test `a backup started without a body, with an empty body object or an empty passphrase has no secrets` now includes `{"passphrase":""}` -> 202, and the zip has no `data/secrets.enc.json` - Pass
- The existing test `a backup passphrase shorter than 12 or longer than 256 characters is a 400` still passes - Pass
- `OpenApiContractTest` (within `./gradlew check`) - Pass

### CR-5 - Restore validation reads and hashes the whole zip three times

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/RestoreService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupService.kt`

Problem:
`RestoreService.validate` called `BackupService.hasSecrets(path)`. That opened and fully re-validated (re-hashed) the zip only to check whether one entry exists.

Fix:
`hasSecrets` now comes from the inspection's manifest: `manifest.files.any { it.path == BackupSecrets.PATH }`. This runs only when the inspection has no problems. In that case the inspection has already checked that every zip entry is listed in the manifest and that every listed file exists with a matching sha256, so the manifest agrees with the zip. `BackupService.hasSecrets` was removed because nothing else uses it.

Validation:
- The existing `BackupSecretsTest` tests for `hasSecrets` true and false in the restore report pass in `./gradlew check` - Pass

### CR-6 - Restore instructions don't say how to give the passphrase on the CLI

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupServiceTests.kt`

Problem:
The README written into each backup described `--restore=` but not `COCKPIT_RESTORE_PASSPHRASE`.

Fix:
The README's restore section now says: "To restore the secrets too, set the environment variable `COCKPIT_RESTORE_PASSPHRASE` to the backup's passphrase." The README test checks for `RestoreCli.PASSPHRASE_ENV` and `RestoreCli.OPTION`, so the README and the CLI constants cannot drift apart. The text has to be a literal in `backup.domain` because the domain layer must not depend on `backup.api`.

Docs: the restore CLI is documented only in `SPEC.md` §10.1. `docs/ARCHITECTURE.md` and `docs/` have no ops or restore section. `SPEC.md` is the product source of truth and was outside the files the parent named, so it was not edited. A one-line addition there is left to the parent or a human.

Validation:
- `the README explains the structure, the format version, how to restore, and lists the counts` - Pass

### CR-7 - Enrollment `confirm` counts as a failed login attempt even when enrollment is not required

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/TotpEnrollmentService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/TotpEnrollmentWebTests.kt`

Problem:
`throttle.tryBeginAttempt()` (which counts a failure up front) ran before `pendingAccount()`. A 409 therefore stayed counted as a failed login attempt.

Fix:
`pendingAccount()` now runs first, so the 409 is thrown before the throttle counts anything. Code checks while re-enrolling still go through the shared throttle as before.

Validation:
- New test `a confirm answered with 409 is no failed attempt and does not throttle the login`: `FREE_ATTEMPTS + 1` confirms answered with 409, then a wrong login gets 401, not 429 - Pass
- Regression check: with the old order put back temporarily, this test failed (`./gradlew test --tests '*TotpEnrollmentWebTests'` -> 13 tests, 1 failed). With the fix -> BUILD SUCCESSFUL
- The existing tests (`wrong codes share the login throttle ...`, `an account that does not need re-enrollment cannot replace its TOTP secret this way`) - Pass

## Not fixed

### CR-1 - Forced TOTP re-enrollment has no web client

Status:
Out of scope

Reason:
Task `web-totp-reenroll` covers it (parent decision).

### CR-2 - Password-only login after a restore is unrecorded

Status:
Out of scope

Reason:
The parent recorded it as an assumption in the task file. Its tests belong to the test-writer.

### CR-3 - Tampering, bounds and enrollment error paths are untested

Status:
Out of scope

Reason:
The parent assigned these tests to the test-writer.

## Validation summary

Focused tests:
- `./gradlew test --tests '*TotpEnrollmentWebTests'` - Pass. With the CR-7 change reverted: 1 failed, as expected.
- `UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'` - regenerated `api/openapi.yaml` (description changes only)

Full test suite:
- `cd server && ./gradlew check` (ktlint + all tests) - BUILD SUCCESSFUL: 2707 tests, 0 failures, 0 errors, 0 skipped
- `cd web && npm run check` - build complete. The bundle budget warning was already there and is unrelated.

Lint/type-check/build:
- `cd server && ./gradlew ktlintFormat`, then the ktlint checks inside `./gradlew check` - Pass

`.taskcheck`:
- Not run by the fixer (`scripts/check.sh`). Its server and web parts were run as listed above. The parent runs it before `done`.

Remaining unresolved findings:
0 of the four assigned (CR-4..CR-7). CR-1..CR-3 were handled outside this fixer run.

## Final status

All validated findings fixed and validation passed.
