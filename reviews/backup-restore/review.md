# Code Review

Date: 2026-10-21

Task: backup-restore - Backup restore: validation, full replace, CLI

Scope: `git diff c64f99c12708f0a0f10ccd1cc90a2c5f2834d9ed..b02848f169291da14a8f14640c277dc16e91985d`, limited to the files changed by the task's commits (`git log --oneline c64f99c1..HEAD --grep '^backup-restore'`): f73fbc78 (implementation), 01b2bdcc (web and CLI tests), 37fa638d (regenerated api/openapi.yaml and web client), 0258a4f9 (more tests, first review), 74bc3820 (CR-1/CR-2 fixes of the first review), 9613e107 and b02848f1 (test-only additions, tests.md). These are the server `backup` package and its tests, `core/backupcontrib/UserBackupContributor.kt` and `UserRestoreWarningsTests.kt`, `CockpitServerApplication.kt`, the restore operations in `api/openapi.yaml`, and `web/src/app/api/generated/` restore files. The other 96 commits in the range are main merges (files module and others) or task-state commits and were not reviewed, except where they interact with restore. Reviewed revision: b02848f169291da14a8f14640c277dc16e91985d.

Requirements:
- tasks/backup-restore.md (five acceptance criteria and four recorded assumptions)
- SPEC.md §10.1
- docs/ARCHITECTURE.md and the AGENTS.md project notes (module boundaries, Problem Details, no user content in logs at INFO or above, generated OpenAPI)
- docs/QUALITY.md (report contract and freshness)
- Parent instructions: a full re-review on the final code. The P3 about double hashing from the earlier review was deferred on purpose.

Git range:
- Branch: main
- HEAD: b02848f169291da14a8f14640c277dc16e91985d
- Base/start: c64f99c12708f0a0f10ccd1cc90a2c5f2834d9ed
- Commits reviewed: 7 task commits (103 commits in the range in total)
- Uncommitted changes: none at the start of the review

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Every upload hashes the whole backup twice while holding the RestoreService lock (carried over, deferred)**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/RestoreService.kt:115`, `:124`; `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupService.kt:97-100`
    - Category: Quality
    - Problem: `validate` first runs `ZipBackupReader.inspect`, which hashes every entry. It then calls `backups.restoreWarnings(path)`, which goes through `ZipBackupReader.open` and validates and hashes every entry again before any contributor reads `data/user.json`. `upload` is `@Synchronized`, so both passes run under the monitor that `confirm` also needs. The parent deferred this finding on purpose. It is listed again only because the code is unchanged.
    - Why it matters: upload latency doubles for backups with large file blobs, with no gain in safety.
    - Suggested fix: when `inspect` finds no problems, run the contributors' `restoreWarnings` on a reader that is already validated. Either let `inspect` return the open reader, or add a `BackupService.restoreWarnings(ZipBackupReader)` overload.

## Verified without findings

- **What changed since the previous review:** `git diff 08e2156f HEAD` shows no change to any task production file (backup package, `UserBackupContributor.kt`, `CockpitServerApplication.kt`). The changes since then are test-only (`RestoreWebTests`, `RestoreCliTests`, `ZipBackupReaderValidationTests`) plus `reviews/`. The production code at HEAD is identical to the revision `2d37483d` that `tests.md` names (`git diff 2d37483d HEAD -- server/src/main` is empty for the task files).
- **Criterion 1 (upload and confirm):** `POST /api/v1/restore` takes a raw `application/zip` or `application/octet-stream` body. It always returns 200 with a report: format version, app version, createdAt, counts, problems (sha256 mismatches by path) and warnings. The data is not touched, and only a valid upload is kept. `POST /api/v1/restore/{id}/confirm` writes `${cockpit.data-dir}/pre-restore/cockpit-backup-....zip` with a collision-free name and removes a partly written file. It then stashes the data directory's contents in `.restore-<uuid>` and runs `TRUNCATE ... CASCADE` (every table in the current schema but `flyway_schema_history`, identifiers quoted) plus all contributor imports in one `importTransaction`. On failure it rolls back and moves the stash back. Tests cover the report, the replace, session wipe, rollback of the database and the blobs, pre-restore name collisions, and the pre-restore zip round-tripping back to the replaced data (new in b02848f1).
- **Earlier CR-1/CR-2 fixes still hold:** contributor failures in `restoreWarnings` become the fixed problem "The backup's data is not valid". A failing pre-restore export becomes `PreRestoreBackupFailedException`, which maps to a 500 Problem Details with a fixed detail through a controller-local `@ExceptionHandler`. Both paths log only the exception class at WARN and the details at DEBUG.
- **Security:** both endpoints sit under the authenticated `/api/**` chain (no permitAll matcher covers them) and need the CSRF token. New tests check that anonymous requests get 401 and requests without CSRF get 403, keep the pending upload and change no data. Upload ids are server-generated UUIDv7s bound as typed `UUID` (a non-UUID id gives 400). Zip entry names are checked with `requireSafePath` and an allowed-prefix list, and duplicate or unlisted entries are reported. Problems, warnings and logs carry only zip paths, line numbers, class names and fixed sentences. No secrets or credentials are in the diff, and the test data is fictitious.
- **Criterion 2 (CLI):** `main` detects `--restore=` before `runApplication`. `RestoreCli` replaces any `--server.port` with `-1`, validates, prints the report, replaces the data, closes the context through `use` and returns 0 (restored), 1 (invalid backup, failed import or failed pre-restore backup) or 2 (no file). "Setup skipped" follows from `SetupService.isSetupRequired()`. `RestoreCliTests` cover each exit code, a restore over existing data with its pre-restore copy, and a data directory that does not exist yet. `tests.md` also records a manual run of the real bootJar against its own Postgres container.
- **Criterion 3 (rejections):** web tests cover a corrupt zip, a wrong format version, a sha256 mismatch and an empty body. Each gives `valid: false`, no id and unchanged data. `ZipBackupReaderValidationTests` adds a corrupt deflated manifest, rejected the same way by `open` and `inspect`. The format version is checked on the JSON tree before mapping.
- **Concurrency:** `upload` and `confirm` are `@Synchronized`. `confirm` runs in `BackupJobService.exclusive`, so a running backup gives 409 and `start()` waits during a restore. The lock order is always RestoreService, then BackupJobService. A failing or broken upload discards the previous pending upload, which matches the recorded assumption ("a new upload ... discards it") and is pinned by a test.
- **Interaction with merged main code:** the files module merged since the last review keeps its state under `${cockpit.data-dir}/blobs` and `${cockpit.data-dir}/uploads` and in database tables. Restore stashes and truncates these like everything else, so a failed restore restores them too. The files module has no backup contributor yet, so a confirmed restore replaces its rows without the backup containing them. That gap belongs to the planned `backup-contrib-files` task, not to this one.
- **Criterion 5 (OpenAPI):** `uploadRestore` and `confirmRestore` are in `api/openapi.yaml` with the `application/zip` body and 200, 400, 404, 409, 422 and 500 responses. The generated `upload-restore.ts` / `confirm-restore.ts` and response models match the paths, the body type and the schemas.
- **Architecture:** the changes stay in `backup/`, apart from the small `UserBackupContributor` and `CockpitServerApplication` edits that the task notes record. Other modules are reached only through the `BackupContributor` interface. No migration was added.
- **Evidence:** `python tasks.py verify backup-restore` shows all five criteria still unverified. That is expected before the parent records evidence (AGENTS.md quality gate, step 7). `tests.md` reports 132 backup-package tests passing plus `OpenApiContractTest`, and notes that the `UPDATE_OPENAPI=1` regeneration was not re-run by the test-writer. The parent must record evidence for criterion 5 accordingly. The `tests.md` Scope line names commit hashes from an earlier history (9e473389 ... a49052d7, merged as 2d37483d) that are not ancestors of the current HEAD. The code under test is the same, as shown above, so the parent should cite the current revision in `quality.json`. This review did not re-run the suites.

## Review conclusion

No material issues found.
