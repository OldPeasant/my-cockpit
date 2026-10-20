# Test Report

Date: 2026-10-20

Task: backup-core - Backup export: zip format and orchestration

Scope: `git diff 4ac1aec7..HEAD` on `main`. Immutable base `4ac1aec7046cbaeebd482b2a7738f5c621bea9fd` (the claim commit). Measured HEAD `d120b0b9787b569f5b0fe11e0fdebd87f5471633`. Task commits `a8100f5b5c9067c833be2456864ce18ef4622e6b` (implementation, tests, earlier reports), `ba5d00ea7e6472fbb2a8b98d8fe0f70c31dacb11` (drop a redundant cast in `ZipBackupReader`), and `72dff5ce2bf58ee4a94a713344ae0a5b9dc60332` (regenerated `api/openapi.yaml` and web client after merging main). The base and commits came from the parent worker. I checked them against `git log 4ac1aec7..HEAD`, which also lists the merge commits `764019f2` and `d120b0b9` of `origin/wip/backup-core`. The working tree was clean (no uncommitted or untracked files) before and after the measurement. This report replaces the earlier one, which described the pre-squash revision `887a256`.

Requirements:
- `tasks/backup-core.md` acceptance criteria 1-5 and its recorded assumptions (jobs in memory, one at a time, progress as `BACKUP` change events; CR-3 and CR-4 deferred to backup-restore)
- `SPEC.md` §10.2 (zip format v1, `BackupContributor`, dependency order, format-version validation), §12 (round-trip test), §14
- `docs/ARCHITECTURE.md` "Coverage"; `docs/QUALITY.md` (report contract); `api/README.md` (contract test)
- `reviews/backup-core/review.md` and `fixes.md` (CR-1: a job always ends FAILED; CR-2)

## Test files changed

None. The 8 backup suites under `server/src/test/kotlin/ch/sonensei/cockpit/backup/` (added by commit `a8100f5b`) were re-examined and run unchanged.

## Tests added or updated

Count: 0

Since the last report, the code changed in two ways:
- `ba5d00ea` replaced `mapper.readValue(it, BackupManifest::class.java) as BackupManifest?` with `mapper.readValue<BackupManifest?>(it, BackupManifest::class.java)` in `ZipBackupReader.readManifest`. The behavior this line affects (a manifest that is JSON `null` must be rejected as "not valid" rather than throw an NPE) is already pinned by `ZipBackupReaderValidationTests` `a manifest that is JSON null is not valid`. That test passes on HEAD.
- `72dff5ce` regenerated `api/openapi.yaml` and the web client. `OpenApiContractTest` checks the generated contract against the committed file and passes.

The existing 67 backup tests cover every acceptance criterion and the CR-1 fix (see the mapping below). Coverage is unchanged from the earlier revision, and every remaining gap is unreachable defensive code or compiler-generated code. Another test would add nothing meaningful. I did not add failing tests for CR-3 or CR-4: both are P3, need a hand-edited zip, and the recorded assumptions defer them to backup-restore.

## Requirement coverage

All server commands were run from `server/`. The focused run is
`./gradlew cleanJacocoTestReport test --rerun --tests 'ch.sonensei.cockpit.backup.*' --tests '*OpenApiContractTest' jacocoTestReport ktlintCheck`, referred to as "focused run" below.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 `BackupContributor` interface (canonical JSON/JSONL export, readable mirrors, import) with dependency ordering | `BackupContributorRegistryTests` (6: dependency order with id tie-break, empty, duplicate/invalid/unknown ids, cycles); `BackupServiceTests` `exports data of every contributor in dependency order, then mirrors, in one read-only repeatable-read snapshot`, `import reads every contributor in dependency order in one transaction`, `a contributor without mirrors writes data only`; `ZipBackupFormatTests` `the reader reads back what the writer wrote`, `equal data gives byte-equal data files`, manifest counts tests | focused run | Pass |
| AC1 `BackupFixture` interface for tests | `BackupFixture.kt` (test source), implemented by `TestFixtureBackupFixture` and used by `BackupRoundTripTest` | focused run | Pass |
| AC2 `POST /api/v1/backup` starts an async job (202 + Location), one at a time | `BackupWebTests` `POST starts a backup that completes and downloads as a zip`, `a running backup is not downloadable and blocks a second one`, `a new backup discards the previous one`, `unknown and invalid ids give 404 and 400`, `anonymous requests are rejected`, `POST without CSRF token is rejected`; `BackupJobServiceTests` `only one backup runs at a time`, `unknown jobs are not found` | focused run | Pass |
| AC2 progress via SSE | `BackupEventsStreamTests` `progress of a running backup reaches the SSE stream before the export finishes` (embedded server on a random port, real `/api/v1/events`); `BackupWebTests` `progress is announced as BACKUP change events while the export runs`; `BackupJobServiceTests` `a job announces itself once, then every progress step and the completion, in order` | focused run | Pass |
| AC2 failed jobs end FAILED and never block later backups (CR-1) | `BackupJobServiceTests` `a failed export leaves no partial zip, ...`, `a contributor throwing an Error fails the job and does not block the next backup`, `a fatal VirtualMachineError still ends the job as FAILED, announces it, and the next backup runs`; `BackupWebTests` `a failed backup reports FAILED without details and cannot be downloaded`, `a failed backup leaves no zip behind and the next backup can start` | focused run | Pass |
| AC2 `GET /api/v1/backup/{id}/download` streams the zip: README.md, manifest.json (formatVersion, appVersion, createdAt, counts, sha256 per file), data/, files/, readable/ | `BackupWebTests` `the downloaded zip has a manifest with counts and the sha256 of every file, named like the job says`; `ZipBackupFormatTests` `writes data, files, readable, README and a manifest with counts and sha256 of every file`, `a file name with the backup time`; `BackupServiceTests` `the manifest has the format version, the creation time in whole seconds and the counts`, `the README explains the structure, the format version, how to restore, and lists the counts` | focused run | Pass |
| SPEC §10.2 format-version validation and reader integrity (sha256, size, unlisted, duplicate and unsafe entries; null manifest, the line `ba5d00ea` touched) | `ZipBackupReaderValidationTests` (18, including `a manifest that is JSON null is not valid` and `format versions other than 1 are rejected`); `BackupServiceTests` `an invalid backup is rejected before any contributor or transaction runs` | focused run | Pass |
| AC3 `BackupRoundTripTest` harness: seeds all fixtures, exports, wipes DB and data dir, imports, deep-compares canonical JSON and file hashes | `BackupRoundTripTest` `every contributor's data survives export, wipe and import unchanged` (Postgres via Testcontainers) | focused run | Pass |
| AC4 `./gradlew test --tests 'ch.sonensei.cockpit.backup.*'` passes (Testcontainers Postgres) | all 8 backup suites, 67 tests | focused run (2026-10-20T22:05:25 to 22:06:16+02:00) | Pass: 67 tests, 0 failures, 0 errors, 0 skipped |
| AC5 `OpenApiContractTest` passes without the flag; `api/openapi.yaml` regenerated | `ch.sonensei.cockpit.core.web.OpenApiContractTest` (compares the springdoc output with the committed `api/openapi.yaml`) | focused run (no `UPDATE_OPENAPI`) | Pass: 1 test, 0 failures. I did not regenerate the file; the committed one matches. |
| Lint | n/a | `./gradlew ktlintCheck` (in the focused run) | Pass |

## Focused test execution

Command:

`cd server && ./gradlew cleanJacocoTestReport test --rerun --tests 'ch.sonensei.cockpit.backup.*' --tests '*OpenApiContractTest' jacocoTestReport ktlintCheck`

Result:

Pass. BUILD SUCCESSFUL in 56s. 68 tests: 67 backup tests in 8 suites plus `OpenApiContractTest`, with 0 failures, 0 errors and 0 skipped (from `build/test-results/test/TEST-*.xml`). Per suite: `BackupContributorRegistryTests` 6, `BackupEventsStreamTests` 1, `BackupJobServiceTests` 7, `BackupRoundTripTest` 1, `BackupServiceTests` 11, `BackupWebTests` 12, `ZipBackupFormatTests` 11, `ZipBackupReaderValidationTests` 18, `OpenApiContractTest` 1. ktlintCheck passed.

## Full test execution

Command:

`sh scripts/check.sh` (`.taskcheck`: TFP gate tests, server ktlint and full test suite, web eslint, tests and build, android ktlint, unit tests and lint)

Result:

Pass. The run took 2026-10-20T22:07:07 to 22:10:55+02:00, about 3m48s. That is above the 2 min warning in AGENTS.md but within the 15 min `TASK_CHECK_TIMEOUT`. The server suite ran 1924 tests with 0 failures, 0 errors and 0 skipped. Web ran 43 test files and 668 tests, all passing. The script exit code was 0 and it printed "== all checks passed".

## .taskcheck

Result:

Pass (see "Full test execution").

## Bugs found

None. No test exposed a production defect in the backup module.

Observation, not a defect: at JVM shutdown after the focused run, one Spring context logged "Shutdown phase ... ends with 1 bean still running after timeout of 30000ms: [webServerGracefulShutdown]". After `BackupEventsStreamTests` closes its client stream, the server-side SSE emitter is still registered, and graceful shutdown waits up to 30 s for it. This only adds time at the end of the test JVM and does not change any result. Core event-stream tests may share it. It does not need fixing for this task.

## Coverage

Status: verified. This is a fresh JaCoCo run on a stable, clean snapshot of HEAD `d120b0b9`, with no production or test changes before, during or after the run.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: the same as whole-file line coverage, 426/429 lines (99.3%). Method: `git diff --name-status 4ac1aec7..HEAD -- server/src/main` shows all 7 scoped files as added (`A`), so every executable line JaCoCo reports in them is an added line. Changed-branch coverage is not inferred from this; the branch counters are listed below.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `backup-core`. Base `4ac1aec7046cbaeebd482b2a7738f5c621bea9fd` and commits `a8100f5b`, `ba5d00ea` and `72dff5ce` were supplied by the parent. Measured HEAD is `d120b0b9787b569f5b0fe11e0fdebd87f5471633`. The file scope comes from `git diff --name-status 4ac1aec7..HEAD -- server/src/main web/src android/app/src/main` and matches the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `d120b0b9`, with a clean working tree (`git status --short` was empty before and after). The sha256 of `git ls-files -s server/src/main/kotlin/ch/sonensei/cockpit/backup server/src/test/kotlin/ch/sonensei/cockpit/backup` is `17d614409b169f90a7bf4b5db2d4dc7ab0a6694a26570ba8214124e8f578408e`, and it was the same before and after the run.
- Exact included files: the 7 server files in the table below. Excluded: the 7 `web/src/app/api/generated/**` files (4 added, 3 modified; generated OpenAPI client code per ARCHITECTURE.md and api/README.md), the generated `api/openapi.yaml`, the docs under `reviews/**`, and test sources. There are no renamed or deleted files. The JaCoCo report also lists `backup/infra/_Collections.kt` and `Comparisons.kt`. These are Kotlin stdlib inline-function source entries, not task files, and they have no counters.
- Provider/version, command, working directory, and test selection: JaCoCo agent `org.jacoco:org.jacoco.agent:0.8.15` (Gradle `jacoco` plugin, version from `./gradlew dependencies --configuration jacocoAgent`). The command was `./gradlew cleanJacocoTestReport test --rerun --tests 'ch.sonensei.cockpit.backup.*' --tests '*OpenApiContractTest' jacocoTestReport ktlintCheck`, run in `server/`. Test selection: the 8 backup suites (67 tests) plus `OpenApiContractTest`.
- Run start/completion time and exit status: tests started 2026-10-20T22:05:25+02:00 (first suite timestamp), and the report was written at 22:06:16+02:00. BUILD SUCCESSFUL, 68 tests, 0 failures.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` (sha256 `a7ba1b04303b219f08ca06423dfcfb94a042493e0448db0b68e2a7b6ae823cda`, mtime 2026-10-20 22:06:16.02+02:00, JaCoCo sessioninfo id `dell26-147159a9`); `server/build/jacoco/test.exec` (sha256 `3133a40660f1ac23a928fdccf6484cf3439b5e6d85b4b79c381537ecbc9dd638`, mtime 22:05:42+02:00). The later `.taskcheck` run overwrites these Git-ignored files, so the hashes identify the coverage run, not the files currently on disk.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `cleanJacocoTestReport` and `test --rerun` forced fresh execution and a new report. The snapshot hash and the clean `git status` were the same before and after the run.
- Final run after latest test additions and production fixes: yes. No tests were added, and the run was on HEAD `d120b0b9`, which contains the CR-1/CR-2 fixes and `ba5d00ea`.

Values below are covered/total (percentage). "Statements" are JaCoCo INSTRUCTION counters, and "Functions" are METHOD counters. All counters are provider-native, taken per `<sourcefile>` element.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/backup/api/BackupController.kt` | 168/195 (86.2%) | N/A (0/0) | 15/16 (93.8%) | 54/55 (98.2%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupContributor.kt` | 194/219 (88.6%) | 14/14 (100.0%) | 6/10 (60.0%) | 24/26 (92.3%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupFormat.kt` | 201/201 (100.0%) | 18/18 (100.0%) | 16/16 (100.0%) | 26/26 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupJobService.kt` | 593/615 (96.4%) | 14/16 (87.5%) | 33/33 (100.0%) | 77/77 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupService.kt` | 475/475 (100.0%) | 3/4 (75.0%) | 19/19 (100.0%) | 74/74 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/backup/infra/ZipBackupReader.kt` | 612/620 (98.7%) | 51/52 (98.1%) | 17/17 (100.0%) | 91/91 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/backup/infra/ZipBackupWriter.kt` | 475/480 (99.0%) | 15/16 (93.8%) | 31/32 (96.9%) | 80/80 (100.0%) |
| **Aggregate (summed counters; all 7 scoped files measured, none missing)** | 2718/2805 (96.9%) | 115/120 (95.8%) | 137/143 (95.8%) | 426/429 (99.3%) |

The aggregate excludes only the generated web client files and `api/openapi.yaml` listed above. All in-scope server files were instrumented and are in the report. The counters match the earlier revision exactly. The `ba5d00ea` change on `ZipBackupReader.kt:93` is fully covered.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `BackupJobService.kt:124, 146, 153` (catch side of `runCatching { Files.deleteIfExists(...) }`) | Deleting a temp zip fails (I/O error); CR-1 guarantees that FAILED is still set | Left uncovered. A failing `deleteIfExists` cannot be triggered without injecting a filesystem: the work dir is a private lazy temp dir, and the path is derived from an internal UUID. `runCatching` cannot skip the `update` that follows, so the risk is low. A test would need a production seam. |
| `BackupJobService.kt:96` (`zip == null` while the state is COMPLETED) | Defensive check | Not reachable: `zips[id]` is set before the COMPLETED update. |
| `BackupJobService.kt:144` (`filter { state != RUNNING }`, false branch) | Discarding must never remove a RUNNING job | Not reachable through `start()`, which throws 409 first while a job is RUNNING. `only one backup runs at a time` covers the 409. |
| `BackupService.kt:36` (`implementationVersion ?: "dev"`, non-null branch) | `appVersion` taken from the jar manifest | Only reachable from a packaged jar; tests run from classes. The `dev` fallback is asserted by the manifest tests. Not material. |
| `BackupContributor.kt:102, 107` (reified inline `json` / `jsonLines` helpers) | Typed reader helpers | They are called in `ZipBackupFormatTests`, `BackupServiceTests` and `TestFixtureBackupContributor`, but Kotlin inlines their bodies at each call site, so JaCoCo never records hits on the declaring lines. This is a tool limitation, not a test gap, and it explains the 4 missed functions. |
| `BackupController.kt:125, 139-144` (synthetic members of the `BackupJobResponse` data class) | None: compiler-generated | Not material. The response shape is asserted by `BackupWebTests` and the OpenAPI contract test. |
| `ZipBackupReader.kt:34, 42` (exception paths of the inlined `use`/`useLines`) | Closing streams when Jackson throws mid-read | Not material: compiler-generated try/finally. Malformed lines are the deferred CR-3 (backup-restore). |
| `ZipBackupWriter.kt:29` (default-argument branch of `out.buffered()`), `:141` (companion `DATA_NAME` getter) | None: synthetics | Not material. Data-name validation is asserted by `unsafe paths, invalid names and duplicates are rejected`. |

## Unable to test

- A failing `Files.deleteIfExists` inside `BackupJobService` (see the coverage table). It cannot be triggered without a production seam for the filesystem. No follow-up is needed unless the parent wants an injectable work directory.
- `appVersion` from a packaged jar manifest: only observable on a `bootJar` build. No follow-up is needed for this task.
- CR-3 (malformed or JSON-`null` `.jsonl` lines) and CR-4 (null entries in the manifest's `files[]`) were not tested. The recorded assumptions defer them to backup-restore, which owns the restore validation report.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. No tests were added on this revision. The existing 67 backup tests already pin the only behavior-relevant change since the last report (`ba5d00ea`, a JSON-`null` manifest). The 67 backup tests, `OpenApiContractTest`, `ktlintCheck` and `.taskcheck` (1924 server tests, 668 web tests) pass on HEAD `d120b0b9`. Coverage of the 7 task production files is 2718/2805 instructions (96.9%), 115/120 branches (95.8%) and 426/429 lines (99.3%). The remaining gaps are unreachable defensive branches or compiler-generated code.
