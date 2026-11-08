# Test Report

Date: 2026-11-08

Task: server-check-speed - Server check: make .taskcheck's server tests fast again

Scope: `git diff 8af08396db17f257978bbc5c770ba893ebd363cc..76d08ab2226c9e235b78cabb6717f2f2b62d2f31` on branch main (task commits d0d4eae74, 802620357, 76d08ab22, supplied by the parent worker; verified with `git log`), plus the two uncommitted test-file changes listed below.

Requirements:
- tasks/server-check-speed.md acceptance criteria (faster server `test`, no flakiness, CI still runs every server test, coverage that matters kept)
- AGENTS.md project notes, docs/ARCHITECTURE.md "Testing"/"Coverage" (as changed by the task)
- Parent instructions: check that the production crypto parameters stay covered, that the production code changes are tested, and that the tests made order-independent really are

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupSecretsCryptoTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupWorkDirCleanupTests.kt`

## Tests added or updated

Count: 3 added (0 existing tests changed by this helper)

- `BackupSecretsCryptoTests`: `a freshly loaded BackupSecrets encrypts with the default KDF, so production is not lowered like Spring tests`
- `BackupWorkDirCleanupTests`: `without a temp root the backup service works in the JVM temp dir and deletes its work dir there`
- `BackupWorkDirCleanupTests`: `without a temp root the restore service works in the JVM temp dir and deletes its work dir there`

Why these gaps were real:

1. The task changed `BackupSecrets.encrypt`'s default from `DEFAULT_KDF` to the mutable `encryptionKdf`. The adjusted test `the default KDF is 64 MiB, 3 passes, one lane and is what encrypt uses` now sets `encryptionKdf = DEFAULT_KDF` itself before encrypting, so it would still pass if the production initializer of `encryptionKdf` were changed to a weak value (for example the 8 KiB / 1 pass test KDF). It cannot simply read the field either, because `FastTestCrypto` lowers it in any test JVM that created a Spring context, depending on fork scheduling. The new test loads a fresh copy of `BackupSecrets` in an isolated `URLClassLoader` (main classes, Kotlin stdlib, Bouncy Castle; platform loader as parent), checks that the class really came from that loader, and asserts that both `DEFAULT_KDF` and the initial `encryptionKdf` are `Kdf(memoryKiB=65536, iterations=3, parallelism=1)`. This is deterministic and independent of test order and forks.
2. `BackupJobService.tempRoot` / `RestoreService.tempRoot` were added, and `BackupWorkDirCleanupTests` was switched to always set `tempRoot` to its own `@TempDir`. After that change, no test asserted the production path (`tempRoot == null`): work dir created in the JVM temp dir and deleted on `destroy()`. Spring tests execute that path but never check the cleanup. The new tests use services without `tempRoot` and identify their own work dir without listing diffs of the shared temp dir: the backup test via `zip(id).parent`, the restore test via the dir in `java.io.tmpdir` that contains the upload's unique `{reportId}.zip`. Other forks creating or deleting `cockpit-*` dirs concurrently cannot affect the result (a vanished foreign entry simply fails the `exists` filter).

## Review of the other task test changes (no change needed)

- `FastTestCryptoTests`: covers the production password encoder's parameters (`m=16384,t=2,p=1`, Argon2id) independently of Spring, and that hashes from the fast encoder and envelopes from the fast KDF are accepted by the production verification/decryption. Adequate.
- `BackupSecretsCryptoTests` (task change): restores the previous `encryptionKdf` in `finally`; the JVM has no JUnit in-fork parallelism (no `junit-platform.properties`), so the temporary global change cannot leak into a concurrently running test. A backup triggered from a cached Spring context in the same window would only be slower, not wrong.
- `BackupWorkDirCleanupTests` (task change): all listings now use the test's own `@TempDir`, and every service in the class gets `tempRoot = tmp`; no remaining listing of the shared JVM temp dir. Order- and fork-independent.
- `GoogleContactsSyncTests`: `reset()` now also runs `@AfterEach`, deleting links, accounts, all contacts and contact search documents, so contacts trashed by this class no longer show up in other classes' Trash. Its `@BeforeEach` keeps it independent of what ran earlier. Order-independent.
- `HabitEdgeCaseTests`: `reset()` runs from `@BeforeEach` (via `setUp`) and `@AfterEach` and now deletes every reminder, so the `fireDue() shouldBe 2` / `shouldBe 1` assertions no longer depend on due reminders left by other classes in the same fork. Order-independent.
- `config/application.properties`: `cockpit.data-dir=build/test-data/fork-${org.gradle.test.worker:0}` separates file blobs per fork; with one fork it falls back to `fork-0`. Not unit-testable beyond the suite runs below.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Production crypto parameters stay covered while Spring tests use cheap Argon2 (backup KDF) | `BackupSecretsCryptoTests` (all, including the new fresh-classloader test), `FastTestCryptoTests` backup KDF test | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.backup.BackupSecretsCryptoTests' --tests 'ch.sonensei.cockpit.support.FastTestCryptoTests'` | Not run: Gradle is denied to sub-agents here; to be run by the parent |
| Production password encoder parameters stay covered | `FastTestCryptoTests` | same as above | Not run: Gradle denied to sub-agents; parent runs it |
| Production change `tempRoot` (set): work dirs in the given root, cleaned on destroy | `BackupWorkDirCleanupTests` (existing, task-adjusted) | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.backup.BackupWorkDirCleanupTests'` | Not run: Gradle denied to sub-agents; parent runs it |
| Production change `tempRoot` (null, production default): work dirs in the JVM temp dir, cleaned on destroy | 2 new `without a temp root ...` tests in `BackupWorkDirCleanupTests` | same as above | Not run: Gradle denied to sub-agents; parent runs it |
| Order-independent tests (GoogleContactsSyncTests, HabitEdgeCaseTests, BackupWorkDirCleanupTests) and no flakiness | whole server suite with forks | `cd server && ./gradlew cleanTest check` three times in a row (parent is running this) | Not run by this helper: results to be supplied by the parent |
| `.taskcheck` passes | all parts | `sh scripts/check.sh` | Not run: Gradle denied to sub-agents; parent runs it |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.backup.BackupSecretsCryptoTests' --tests 'ch.sonensei.cockpit.backup.BackupWorkDirCleanupTests' --tests 'ch.sonensei.cockpit.support.FastTestCryptoTests'`

Result:

Not run: Gradle commands are denied for sub-agents in this environment (known limitation, stated by the parent). The parent must run this command (and `./gradlew ktlintCheck` for the two edited files) and record the result. The new tests have not been compiled or executed by this helper.

## Full test execution

Command:

`cd server && ./gradlew cleanTest check` (3 runs, with the default fork count)

Result:

Not run by this helper; the parent is running it and must rerun it after these test additions, since its current runs started before them.

## .taskcheck

Result:

Not run (Gradle denied to sub-agents); parent runs `sh scripts/check.sh`.

## Bugs found

None. No production defect was found by inspection. Note for the parent (not a defect): `tempRoot` only takes effect when set before the first use of the work dir (it is read by the lazy initializer); all current callers set it right after construction.

## Coverage

Status: unavailable - Gradle (and with it JaCoCo) cannot be run by this helper; to be measured by the parent.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: server-check-speed; base 8af08396db17f257978bbc5c770ba893ebd363cc; HEAD 76d08ab2226c9e235b78cabb6717f2f2b62d2f31; commits d0d4eae74, 802620357, 76d08ab22 from the parent, confirmed with `git log 8af08396d..HEAD` and `git diff --stat`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 76d08ab22 plus uncommitted `BackupSecretsCryptoTests.kt` sha256 7e3a52550c1e83c2ff1062bce847d947b80118a476c01cadd03a2e3200127a0a and `BackupWorkDirCleanupTests.kt` sha256 8c446f1064b28dc5ab26ffe06e9ed0c122dd88b16c4dd1402a377b2762471e3a.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupSecrets.kt`, `.../backup/domain/BackupJobService.kt`, `.../backup/domain/RestoreService.kt`. Excluded: `server/build.gradle.kts` (build configuration), `docs/ARCHITECTURE.md` (documentation), all `server/src/test/**` files (tests and test support). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin, version as configured); command for the parent: `cd server && ./gradlew test jacocoTestReport`; full server `test` selection; report at `server/build/reports/jacoco/test/jacocoTestReport.xml`.
- Run start/completion time and exit status: not run.
- Generated report paths and report hashes/run identifier: none.
- Evidence outputs are fresh and inputs stayed unchanged: not applicable, no run.
- Final run after latest test additions and production fixes: no - not measurable by this helper.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupSecrets.kt` | N/A (not measured) | N/A (not measured) | N/A (not measured) | N/A (not measured) |
| `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupJobService.kt` | N/A (not measured) | N/A (not measured) | N/A (not measured) | N/A (not measured) |
| `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/RestoreService.kt` | N/A (not measured) | N/A (not measured) | N/A (not measured) | N/A (not measured) |

Expected by inspection (to be confirmed by the parent's JaCoCo run): both branches of the `tempRoot?.let { ... } ?: ...` work-dir initializers are now executed by assertion-backed tests (set: existing cleanup tests; null: the two new tests and every Spring backup/restore test); the `encryptionKdf` initializer and the default argument of `encrypt` are executed by `BackupSecretsCryptoTests`.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `BackupSecrets.kt`: `encryptionKdf` initial value (production default) | Production must encrypt with 64 MiB / 3 passes; previously only asserted after the test set it | Added fresh-classloader test |
| `BackupJobService.kt` / `RestoreService.kt`: `tempRoot == null` branch | Production work dirs in the JVM temp dir must be deleted on destroy | Added 2 tests |

Follow-up: the parent runs `cd server && ./gradlew test jacocoTestReport` after these test additions and records the per-file counters from `server/build/reports/jacoco/test/jacocoTestReport.xml` (or `.../html/index.html`) for the three files above in its evidence.

## Unable to test

- Execution of all tests, lint and coverage: Gradle is denied to sub-agents here; the parent must run the commands above. Needs follow-up by the parent (not a separate task).
- Wall-time halving and "3 green runs in a row" are suite-level measurements, not unit-testable; covered by the parent's timed `./gradlew cleanTest check` runs.
- CI running every server test (`.github/workflows/ci.yml`): needs a CI run; not executable here.

## Summary

Tests added; they and the existing suite still have to be run by the parent worker (Gradle is not available to this helper). No production defects found. Coverage for the three changed production files is to be measured by the parent with `cd server && ./gradlew test jacocoTestReport`.
