# Code Review

Date: 2026-10-21

Task: backup-restore - Backup restore: validation, full replace, CLI

Scope: `git diff 69c905745388ea54cce49ccbabf1bccb372ea894..08e2156f2453eb8b394cab0b9bac23ec045ba0f9`. The task's commits are bfc30e24 (implementation), 5cb13fec (tests), 9c2e563c (regenerated api/openapi.yaml and web client), c5ab8e1d (more tests, first review) and 08e2156f (fixes for CR-1 and CR-2 of the first review). The other commits in the range only touch `tasks/` (heartbeats, claims and assumptions of other tasks) and were not reviewed. Reviewed revision: 08e2156f2453eb8b394cab0b9bac23ec045ba0f9.

Requirements:
- tasks/backup-restore.md (five acceptance criteria and four recorded assumptions)
- SPEC.md §10.1 and §10.2
- docs/ARCHITECTURE.md and the AGENTS.md project notes (module boundaries, Problem Details, no user content in logs at INFO or above, generated OpenAPI)
- docs/QUALITY.md (report contract)
- Parent instructions: re-review after the CR-1/CR-2 fixes, check auth in `core/auth/infra/SecurityConfiguration.kt` and logging of user content. CR-3 of the first review was deferred on purpose.

Git range:
- Branch: main
- HEAD: 08e2156f2453eb8b394cab0b9bac23ec045ba0f9
- Base/start: 69c905745388ea54cce49ccbabf1bccb372ea894
- Commits reviewed: 5 task commits (40 commits in the range in total; the other 35 touch only `tasks/`)
- Uncommitted changes: none at the start of the review

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Every upload still hashes the whole backup twice while holding the RestoreService lock (carried over, deferred)**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/RestoreService.kt:116`, `:123`; `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupService.kt:97-100`
    - Category: Quality
    - Problem: `validate` first runs `ZipBackupReader.inspect`, which hashes every entry. It then calls `backups.restoreWarnings(path)`, which goes through `ZipBackupReader.open` and validates and hashes every entry again before any contributor reads `data/user.json`. `upload` is `@Synchronized`, so both passes run under the monitor that `confirm` also needs. This is the same as CR-3 of the first review. The parent deferred it on purpose, and it is listed again here only because it still exists in the current code.
    - Why it matters: upload latency doubles for backups with large file blobs (SPEC.md §8.2), with no gain in safety.
    - Suggested fix: when `inspect` finds no problems, run `restoreWarnings` on a reader that is already validated. Either let `inspect` return the open reader, or add a `BackupService.restoreWarnings(ZipBackupReader)` overload.

## Verified without findings

- **Criterion 1 (upload and confirm):** `POST /api/v1/restore` takes a raw `application/zip` or `application/octet-stream` body. It always returns 200 with a report: format version, app version, createdAt, counts from the manifest, problems (sha256 mismatches by path) and warnings. The data is not touched. `POST /api/v1/restore/{id}/confirm` first writes `${cockpit.data-dir}/pre-restore/cockpit-backup-....zip` with a collision-free name and removes a partly written zip. It then stashes the data directory and runs `TRUNCATE ... CASCADE` (every table but `flyway_schema_history`, identifiers quoted) plus all contributor imports in one `TransactionTemplate` on the shared transaction manager. On any failure it rolls back and moves the stash back.
- **CR-1 fix from the first review:** `RestoreService.validate` turns a contributor's `RuntimeException` from `restoreWarnings` into the fixed problem "The backup's data is not valid". It logs only the class name at WARN and the details at DEBUG. Tests cover this in `RestoreWebTests` ("a contributor failing on its warnings ...", which checks that "Secret title" is absent from the response and the log) and in the `[null]` recovery code cases in both the web and CLI tests.
- **CR-2 fix from the first review:** a failing `preRestoreBackup()` is wrapped, logs only the class at WARN, and throws `PreRestoreBackupFailedException`. The controller maps it to a 500 Problem Details with a fixed detail through a local `@ExceptionHandler`, so the request does not reach `ProblemDetailsAdvice.handleUnexpected` and its ERROR log. The 500 response is in `api/openapi.yaml`. The CLI returns 1. The web and CLI tests cover it, including the log check.
- **Security:** `/api/**` requires authentication in `SecurityConfiguration` (no permitAll matcher covers `/api/v1/restore`), and the CSRF double-submit applies to both POSTs. Tests check that anonymous requests get 401 and requests without a CSRF token get 403, with the pending upload kept and no data changed. Upload ids are server-generated UUIDv7s, and confirm binds a typed `UUID` (a non-UUID id gives 400). Zip entry names are checked with `requireSafePath` plus an allowed-prefix list. Duplicate and unlisted entries are reported. Problems and warnings contain only zip paths, line numbers and fixed sentences. `UserBackupContributor.restoreWarnings` names neither the owner nor any hash or secret, and a test checks this. Import failures log only the class name at WARN. Blob names in the data directory are UUIDs (`BlobStore`), so the paths that appear in the stash and unstash logs carry no user content.
- **Criterion 2 (CLI):** `main` detects `--restore=` before `runApplication`. `RestoreCli` replaces any `--server.port` with `-1`, validates, prints the report, replaces the data and exits 0 (restored), 1 (invalid backup, failed import or failed pre-restore backup) or 2 (no file). The context is closed through `use`. "Setup skipped" comes from `SetupService.isSetupRequired()` on the restored owner account. `RestoreCliTests` runs the CLI against its own Postgres database and checks each exit code, that data is unchanged on failure, and that `isSetupRequired()` is false after a restore.
- **Criterion 3 (rejections):** `RestoreWebTests` covers a corrupt zip, a wrong format version, a sha256 mismatch and an empty upload. Each gives `valid: false`, a problem, no id and unchanged data. The format version is read from the JSON tree before mapping, so a manifest of another version reports "Unsupported format version", not "not valid".
- **Concurrency:** `upload` and `confirm` are `@Synchronized`. `confirm` runs inside `BackupJobService.exclusive`, so a running backup gives 409 and keeps the upload, and `start()` waits during a restore. The lock order is always RestoreService, then BackupJobService, so the two cannot deadlock.
- **Criterion 5 (OpenAPI):** `uploadRestore` and `confirmRestore` are in `api/openapi.yaml` with 200, 400, 404, 409, 422 and 500. The generated web client (`upload-restore.ts`, `confirm-restore.ts`, models) matches the paths, the `application/zip` body and the response schemas.
- **Architecture:** the changes stay in `backup/` apart from the small edits to `UserBackupContributor` and `CockpitServerApplication` that the task's notes record. The module talks to others only through the `BackupContributor` interface. No migration was added.
- **Evidence:** `python tasks.py verify backup-restore` shows all five criteria as not yet verified. That is expected at this point in the workflow (AGENTS.md quality gate, step 7), and the parent still has to record evidence for each criterion. I did not re-run the test suites in this read-only review. The test results quoted here come from `reviews/backup-restore/fixes.md`: 117 backup and OpenAPI tests passed, `./gradlew check` passed and `.taskcheck` passed.

## Review conclusion

No material issues found. The only remaining finding (CR-1, the double hashing on upload) is a P3 performance item that the parent worker deferred on purpose.
