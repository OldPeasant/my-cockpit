# Code Review

Date: 2026-10-21

Task: backup-restore - Backup restore: validation, full replace, CLI

Scope: task commits 0693f545 (implementation), 021a22e4 (tests), 8a8fd075 (regenerated api/openapi.yaml and web client) on top of base e5a094bd25a191eb22291f520daebe26b85fc2ae. The review covers the production code in `server/src/main/kotlin/ch/sonensei/cockpit/backup/**`, `core/backupcontrib/UserBackupContributor.kt` and `CockpitServerApplication.kt`. Reviewed revision: 8a8fd0751523058733ba902753acd71d86dfd7f3.

Requirements:
- tasks/backup-restore.md (acceptance criteria and the four recorded assumptions)
- SPEC.md §10.1, §10.2 (secrets, BackupContributor), §4.2 conventions as summarized in api/README.md
- docs/ARCHITECTURE.md (data dir, env variables, conventions), docs/QUALITY.md (report contract)
- AGENTS.md "Project notes" (no user content in logs at INFO or above, Problem Details)
- Parent instructions (focus areas: full replace, stash/unstash, concurrency, security, CLI, ZipBackupReader validation, CR-3/CR-4 carried over from backup-core)

Git range:
- Branch: main
- HEAD: 8a8fd0751523058733ba902753acd71d86dfd7f3
- Base/start: e5a094bd25a191eb22291f520daebe26b85fc2ae
- Commits reviewed: 3

Review method and limits: Bash was not permitted in this review session, so `git diff` and `git log` could not be run. HEAD was resolved from `.git/refs/heads/main`. The changed production files were read in full at HEAD: RestoreService, RestoreController, RestoreCli, ZipBackupReader, BackupService, BackupJobService, BackupFormat, BackupContributor, UserBackupContributor, CockpitServerApplication. I also read the context they depend on (SetupService, ProblemDetailsAdvice, ApiExceptions, the generated web `upload-restore.ts`). I could not list the test files from commit 021a22e4 without directory listing, so the test review relies on the parent's recorded evidence and the test-writer's report. No test-coverage findings are raised for that reason. The Spring Security configuration (authentication and CSRF for `/api/v1/restore`) could not be located by path and was not verified here. The parent should confirm that the endpoints sit behind the same authenticated, CSRF-protected chain as `/api/v1/backups`.

Summary: 0 P0, 0 P1, 0 P2, 3 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A contributor's non-InvalidBackupException in restoreWarnings turns the upload into a 500 and crashes the CLI instead of producing a report**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/RestoreService.kt:119`, `server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib/UserBackupContributor.kt:91`
    - Category: Correctness
    - Problem: `validate` catches only `InvalidBackupException` around `backups.restoreWarnings(path)`. The canonical mapper (`BackupFormat.canonical`) does not enable Kotlin strict null checks, so a `data/user.json` whose `recoveryCodes` list contains a JSON `null` deserializes into a `List<RecoveryCodeBackup>` that holds a null element. `user.recoveryCodes.count { it.usedAt == null }` then throws a NullPointerException. `upload` deletes the file and rethrows, so the client gets the generic 500 from `ProblemDetailsAdvice` (logged at ERROR with the stack trace). The CLI never reaches `print(report)` and exits through an uncaught exception, not through its documented exit code 1 with a report. The same applies to any future contributor whose `restoreWarnings` fails with another runtime exception. This is the null-in-collection case of the CR-3 class deferred from backup-core: null jsonl lines and null manifest entries are handled now, but nulls nested in `json()` documents are not. Reaching this needs a hand-edited zip with a recomputed sha256, so the impact is low. Still, it breaks the recorded assumption that the upload "always answers 200 with a report".
    - Why it matters: an invalid backup should end in a clear validation report (acceptance criterion 3), not an unexpected server error. The confirm path is already safe: the NPE becomes `RestoreFailedException`, the transaction rolls back and the data directory is unstashed.
    - Suggested fix: in `validate`, also catch `RuntimeException` from `restoreWarnings` and turn it into a content-free problem such as "The backup's data is not valid". Alternatively, reject null elements in `ZipBackupReader.json` by enabling strict null checks on the restore mapper or by checking the tree. Add a regression test with `"recoveryCodes":[null]`.

- [ ] **CR-2 | Failures of the pre-restore export bypass the content-free logging that the rest of `replace` applies**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/RestoreService.kt:146`, `:172`
    - Category: Security
    - Problem: `replace` wraps import failures carefully. The comment at line 154 says "Contributors' messages may quote user content", so only the exception class is logged at WARN and details go to DEBUG, and `BackupJobService.run` does the same for exports. `preRestoreBackup()`, however, runs `backups.export(...)` (every contributor's `export` and `readable`) outside that guard. An exception there propagates unchanged out of `confirm` into `ProblemDetailsAdvice.handleUnexpected`, which runs `log.error("Unhandled exception in REST request", ex)` with the full message and stack trace. In the CLI it reaches the JVM's default handler.
    - Why it matters: AGENTS.md forbids logging note contents and similar user data at INFO or above. The pre-restore export runs the same contributor code as a normal backup, which the module treats as able to quote user content.
    - Suggested fix: wrap `preRestoreBackup()` like the import, logging the class name at WARN and the details at DEBUG. Throw a dedicated, content-free exception, for example a `RestoreFailedException` variant meaning "the current data could not be backed up; nothing was changed", and map it to a Problem Details response in `RestoreController` and to exit code 1 in `RestoreCli`.

- [ ] **CR-3 | Every upload hashes the whole backup twice while holding the RestoreService lock**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/RestoreService.kt:111`, `:120`; `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupService.kt:97-100`
    - Category: Quality
    - Problem: `validate` first calls `ZipBackupReader.inspect`, which reads every entry and computes its sha256. It then calls `backups.restoreWarnings(path)`, which uses `ZipBackupReader.open` and repeats the full validation over every blob before any contributor reads `user.json`. `confirm` then validates a third time in `BackupService.replace`. `upload` is `@Synchronized`, so the upload copy and both full hash passes run under the monitor that `confirm` also needs. Backups contain all file blobs (SPEC.md §8.2 allows files up to 5 GB by default).
    - Why it matters: upload latency doubles for large backups, with no gain in safety. The re-validation in `confirm` is justified because the file sits on disk between the calls. The second pass during upload is not.
    - Suggested fix: let `restoreWarnings` work on an already validated reader. One option is for `ZipBackupReader.inspect` to return the open reader, or the manifest that `open` can trust, when there are no problems. Another is to give `BackupService` an overload that takes a `ZipBackupReader`.

## Verified without findings

- Full replace: `BackupService.replace` runs `wipeDatabase` (one `TRUNCATE ... CASCADE` over every table of `current_schema()` except `flyway_schema_history`, identifiers quoted) and all contributor imports in one `TransactionTemplate` transaction. A failure, including one at commit, rolls back the truncate.
- Data directory: the stash moves every entry except `pre-restore/` and `.restore-*` into `.restore-<uuidv7>`, rolls back partial moves, and on any `Throwable` from the import deletes what was written and moves the stash back. The stash is deleted only after a successful commit. The pre-restore zip goes to `${cockpit.data-dir}/pre-restore/` with a collision-free name, and a partly written zip is removed when the export fails.
- Concurrency: `upload` and `confirm` are `@Synchronized`. `confirm` checks the pending id under the lock and runs `replace` inside `BackupJobService.exclusive`, whose shared monitor makes `start()` wait, while a running backup yields 409. The lock order is always RestoreService, then BackupJobService, so there is no deadlock. A 409 keeps the upload pending, and any other outcome uses it up as documented.
- Paths and uploads: upload ids are server-generated UUIDv7s and confirm takes a typed `UUID`. Temp files live in a `createTempDirectory` directory (0700 on POSIX) and are deleted on invalid uploads, new uploads, confirm and shutdown. Zip entry paths are checked with `requireSafePath` and an allowed-prefix list, and duplicate and unlisted entries are reported.
- ZipBackupReader: the format version is checked on the JSON tree before mapping, so a manifest of another version reports "Unsupported format version". Null or non-object `files` entries and non-numeric `counts` are rejected (the CR-4 carry-over). Null and malformed jsonl lines report the file and line number without the content (the CR-3 carry-over for jsonl). Not a zip, a missing manifest and a sha256 or size mismatch each produce a problem.
- Messages and logs: report problems name only zip paths and fixed sentences. Warnings contain no username, hashes or secrets. Import failures log only the exception class at WARN. The 422 Problem Details response carries a fixed `detail` and a `problems` list.
- CLI: `--restore=` is detected before `runApplication`. Missing file gives 2, an invalid backup or failed import gives 1, success gives 0. `--server.port=-1` replaces any given port. The context is closed through `use` on every return. "Setup skipped" follows from `SetupService.isSetupRequired()` on the restored account. The parent recorded running the jar against Postgres 16 (exit 0, then `setupRequired:false`, and exit 1 for an invalid zip).
- OpenAPI: operations `uploadRestore` and `confirmRestore` are documented with 200, 400, 404, 409 and 422 (`ProblemDetail`), and the generated web client matches (`POST /api/v1/restore`, body `application/zip`). Only `application/zip` is documented, although the server also accepts `application/octet-stream`. That is harmless and not raised as a finding.

## Review conclusion

No material issues found. The three P3 findings are non-blocking improvements. Two items could not be checked in this session because Bash was unavailable: the authentication and CSRF setup of `/api/v1/restore`, and the content of the task's test files. The parent worker should confirm both.
