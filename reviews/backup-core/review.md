# Code Review

Date: 2026-10-20

Task: backup-core - Backup export: zip format and orchestration

Scope: `git diff 4ac1aec7046cbaeebd482b2a7738f5c621bea9fd..a63c5ada`. Task code commits a8100f5b, ba5d00ea, 72dff5ce, merged as d120b0b9787b569f5b0fe11e0fdebd87f5471633 (final code revision). a63c5ada only adds reviews/backup-core/tests.md. Committed changes only; the working tree was clean. Generated files (`api/openapi.yaml`, `web/src/app/api/generated/`) were checked for consistency, not reviewed line by line. This report replaces the earlier review of the pre-squash revision 30ea3b6.

Requirements:
- tasks/backup-core.md: acceptance criteria 1-5 and the recorded assumptions (jobs and zips in memory/temp dir, one job at a time, BACKUP change events on /api/v1/events, passphrase and secrets.enc.json left to later tasks; CR-3 and CR-4 deferred to backup-restore)
- SPEC.md §10.1, §10.2, §12 (backup round-trip test), §4.2
- docs/ARCHITECTURE.md (packages `<module>.{api,domain,infra}`, code conventions, test infrastructure)
- AGENTS.md project notes (generated files, logging rules, API first, Problem Details)
- docs/QUALITY.md (report contract)

Git range:
- Branch: main
- HEAD: a63c5ada
- Base/start: 4ac1aec7046cbaeebd482b2a7738f5c621bea9fd
- Commits reviewed: 6 (a8100f5b, ba5d00ea, 764019f2 merge, 72dff5ce, d120b0b9 merge, a63c5ada reports only)

Commands run:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.backup.*' --tests '*OpenApiContractTest'` -> BUILD SUCCESSFUL; JUnit XML: 68 tests, 0 failures, 0 errors, 0 skipped (BackupContributorRegistryTests 6, BackupEventsStreamTests 1, BackupJobServiceTests 7, BackupRoundTripTest 1, BackupServiceTests 11, BackupWebTests 12, ZipBackupFormatTests 11, ZipBackupReaderValidationTests 18, OpenApiContractTest 1). Postgres via Testcontainers.
- `python tasks.py verify backup-core` (read-only listing) -> criteria #1-#5 are not yet recorded; the parent worker records them.

Summary: 0 P0, 0 P1, 0 P2, 3 P3

Acceptance criteria check:
1. `BackupContributor` (id, `dependsOn`, `export`, `readable`, `import`) with `BackupDataWriter`/`BackupReadableWriter`/`BackupDataReader`; `BackupContributorRegistry` orders by dependencies (Kahn, deterministic, fails on duplicate/invalid ids, unknown deps, cycles). `BackupFixture` in test sources. Met.
2. `POST /api/v1/backup` -> 202 + Location, async single-thread job, progress as BACKUP change events published outside the snapshot transaction (covered over the real SSE stream by BackupEventsStreamTests); `GET /api/v1/backup/{id}` and `/download` (FileSystemResource stream, attachment file name `cockpit-backup-<time>.zip`). Zip contains README.md, manifest.json (formatVersion, appVersion, createdAt, counts, sha256 and size per file), data/, files/, readable/. 409/404/400 as Problem Details; anonymous and CSRF-less requests rejected (BackupWebTests). Met.
3. `BackupRoundTripTest`: finds every `BackupFixture` on the test classpath, fails while a contributor has none, seeds, exports, truncates every table and empties the data dir, checks the wipe really removed the data, imports, exports again and deep-compares canonical JSON per data file and the sha256 of every blob. Runs on its own database in the shared Testcontainers Postgres. Met.
4. Focused backup suite passes (see Commands run). Met.
5. `api/openapi.yaml` contains the three operations and `BackupJobResponse`; the generated web client matches; `OpenApiContractTest` passes without the flag. Met.

Architecture: code stays inside `backup.{api,domain,infra}`; the domain service uses the infra zip reader/writer the same way other modules' domain services use their infra (e.g. gtd, files). Contributors are discovered as beans, no access to other modules' repositories. Logging on failure records only the exception class at WARN (no user content). No dependency or build-file changes.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-3 | A JSON `null` line in a `.jsonl` file reaches the contributor as null; malformed lines surface as raw Jackson errors** (carried over, re-validated on the current revision)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/backup/infra/ZipBackupReader.kt:43`
    - Category: Correctness
    - Problem: `consumer(mapper.readValue(it, type))` passes a `null` result to a consumer typed `(T) -> Unit` with `T : Any`, and a malformed line throws a `JacksonException` instead of an `InvalidBackupException`.
    - Why it matters: only reachable with a hand-edited zip whose sha256 entries were also updated; the import transaction rolls back, so no data is corrupted, but the user gets no readable validation problem.
    - Suggested fix: in backup-restore (which owns the validation report), map null/malformed records to `InvalidBackupException` with the data name and line number. Already deferred via recorded assumption.

- [ ] **CR-4 | A manifest with null entries in `files[]` fails with a raw NullPointerException** (carried over, re-validated on the current revision)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/backup/infra/ZipBackupReader.kt:110`
    - Category: Correctness
    - Problem: `manifest.files.associateBy { it.path }` dereferences list elements that Jackson may have deserialized as null; the NPE escapes `open` (the zip is closed) instead of `InvalidBackupException("manifest.json is not valid")`.
    - Why it matters: hand-edited zips only; restore would report an internal error instead of a validation problem.
    - Suggested fix: in backup-restore, reject null elements (and other structural problems) as `InvalidBackupException`. Already deferred via recorded assumption.

- [ ] **CR-5 | Shutdown during a running backup leaves the partial zip and the temp work directory behind**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupJobService.kt:150`
    - Category: Quality
    - Problem: `destroy()` deletes only the zips in `zips`, i.e. those of completed jobs. The target of a running job (`workDir.resolve("$id.zip")`, line 101) is only registered on success, and the daemon backup thread is interrupted by `shutdownNow()` without waiting, so its partial zip stays on disk; the `cockpit-backups*` work directory (line 49) is never removed, so one directory per application start accumulates in the system temp dir.
    - Why it matters: a partial backup holds a copy of user data (including the password hash once user data is contributed) outside the data directory after the app stops. Impact is limited: the directory is created owner-only by `Files.createTempDirectory`, and container temp dirs are usually ephemeral.
    - Suggested fix: in `destroy()`, after `shutdownNow()`, delete every `*.zip` in `workDir` (or the whole directory recursively) if it was initialized, regardless of job state.

## Review conclusion

No material issues found. The three P3 findings are non-blocking; CR-3 and CR-4 are already deferred to backup-restore by recorded assumptions, CR-5 can be fixed or accepted by the parent worker. Acceptance criteria #1-#5 are met by the current code and tests, but their evidence still has to be recorded with `tasks.py verify` before `done`.
