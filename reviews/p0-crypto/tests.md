# Test Report

Date: 2026-10-20

Task: p0-crypto - Secret encryption at rest

Scope: `git diff 474e1eb49289d802c419d84beb306b607d0c8505..0e1300105ade72d5c1e68d0a05b29fb376e5dd71` on `main`
(task commits 50628ae, 0e13001, as given by the parent worker and matching the task's `touches`), plus the
uncommitted test changes listed below. No uncommitted production changes.

Requirements:
- `tasks/p0-crypto.md` acceptance criteria and its three recorded assumptions (property `cockpit.master-key`,
  stored format `version | nonce | ciphertext+tag` with the version byte as AAD, JSON/toString redaction as `******`)
- `SPEC.md` §4.1 (via the task goal), `docs/ARCHITECTURE.md` "Testing" and "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/crypto/EncryptedSecretConverterTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/crypto/EncryptedSecretPersistenceTests.kt` (test added)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/crypto/EncryptedSecretTests.kt` (test added)

## Tests added or updated

Count: 6 (the crypto package now has 29 tests, previously 23)

- `EncryptedSecretConverterTests`: `column value is ciphertext that converts back to the secret`,
  `the same secret is stored differently each time`, `null converts to null in both directions`,
  `column written with another master key is rejected` (fast unit tests, no Docker needed)
- `EncryptedSecretPersistenceTests`: `stored secret written with another master key or tampered is not loaded`
  (real Postgres: a row encrypted under a foreign key and a row with a flipped tag bit both fail to load with
  `SecretDecryptionException` in the cause chain, and no exception message contains the plaintext)
- `EncryptedSecretTests`: `a secret is never equal to its plain string`

Existing tests were not changed, weakened or disabled.

## Requirement coverage

Command for all rows: `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.crypto.*'`
(also run as part of `./gradlew check jacocoTestReport`). Testcontainers Postgres 16 was available and used.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `SecretCipher` AES-256-GCM, random nonce, versioned format | `SecretCipherTests`: `round trip returns the plaintext`, `ciphertext uses the versioned format and does not contain the plaintext`, `each encryption uses a fresh random nonce`, `unknown version, truncated and non-base64 input are rejected`, `key must be 32 bytes` | see above | Pass |
| AC1: key from `COCKPIT_MASTER_KEY` (base64, 32 bytes) | `MasterKeyTests`: `a base64 32-byte key is accepted`, `context with a valid key provides the cipher`; `MasterKeyStartupTests`: `application starts with a valid master key` | see above | Pass |
| AC1: startup fails with a clear message if missing/invalid | `MasterKeyTests`: `missing key fails with a clear message`, `invalid key fails without echoing the value`, `context fails to start without a key`, `context fails to start with an invalid key`, `failure analyzer explains how to fix the key`; `MasterKeyStartupTests` (real `SpringApplication.run` against Testcontainers Postgres): `application refuses to start without a master key and says why`, `application refuses to start with an invalid master key without logging it` | see above | Pass |
| AC1: "outside tests" (tests get a fictitious key) | every `@SpringBootTest` in the suite starts with `src/test/resources/config/application.properties` | `cd server && ./gradlew check` | Pass |
| AC2: JPA converter stores ciphertext | `EncryptedSecretPersistenceTests`: `secret is stored as ciphertext and read back as plaintext`, `null secret stays null`; `EncryptedSecretConverterTests` (all 4) | see above | Pass |
| AC2: `toString` / Jackson never reveal the value | `EncryptedSecretTests`: `toString never reveals the value`, `Jackson serialization never reveals the value`; `EncryptedSecretPersistenceTests`: `application JSON mapper redacts the secret` | see above | Pass |
| AC3: round trip | `SecretCipherTests.round trip returns the plaintext`, persistence round trip | see above | Pass |
| AC3: tampered ciphertext rejected | `SecretCipherTests.tampered ciphertext is rejected` (nonce, body, tag bit flips), `EncryptedSecretPersistenceTests.stored secret written with another master key or tampered is not loaded` | see above | Pass |
| AC3: wrong key rejected | `SecretCipherTests.wrong key is rejected`, `EncryptedSecretConverterTests.column written with another master key is rejected`, persistence test above | see above | Pass |
| AC3: crypto test command passes, Postgres via Testcontainers where persistence is involved | whole `ch.sonensei.cockpit.core.crypto.*` selection: 29 tests, 0 failures, 0 skipped | see above | Pass |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.crypto.*'`

Result:

Pass (BUILD SUCCESSFUL, 2026-10-20T10:52:07+02:00 to 10:52:17+02:00; 28 tests at that point, 0 failures/errors/skipped).
A first attempt at 10:51:32 failed with `java.io.EOFException` in the Gradle `:test` task because another
Gradle test run (a different agent process) was using the same `server/build` directory at the same time;
after that process finished, the identical command passed. Not a product defect.

## Full test execution

Command:

`cd server && ./gradlew check jacocoTestReport`

Result:

Pass (final run 2026-10-20T10:53:20+02:00 to 10:53:31+02:00, after the last test addition): ktlint main/test/scripts
clean, 50 server tests in 15 suites, 0 failures, 0 errors, 0 skipped (29 of them in `core.crypto`).

## .taskcheck

Result:

Not run: executing `sh scripts/check.sh` (the `.taskcheck`, which also runs web and Android checks) was denied
by this sub-agent's sandbox permissions. Its server step (`./gradlew check`) was run above and passed. The parent
worker must run `.taskcheck` itself.

## Bugs found

None. No test exposed behavior that contradicts the acceptance criteria or the recorded assumptions.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. All five files are new in this task (every line is an added
line), so whole-file coverage equals changed-line coverage for the Kotlin sources here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p0-crypto; base
  `474e1eb49289d802c419d84beb306b607d0c8505`; HEAD `0e1300105ade72d5c1e68d0a05b29fb376e5dd71`; commits 50628ae and
  0e13001 supplied by the parent worker, cross-checked with `git log 474e1eb..HEAD` (only those two) and the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 0e13001 plus uncommitted test files (sha256):
  `EncryptedSecretConverterTests.kt` 36b343d8..., `EncryptedSecretPersistenceTests.kt` 3a9558fa..., `EncryptedSecretTests.kt`
  ab85c666... (hash taken after the final edit, before the final run). Production files sha256: `CryptoConfiguration.kt`
  a5728a39..., `EncryptedSecretConverter.kt` 507ea064..., `EncryptedSecret.kt` d76c3f5f..., `MasterKey.kt` 1647f183...,
  `SecretCipher.kt` 0df0091f... (identical to HEAD; unchanged during the runs).
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/core/crypto/{CryptoConfiguration,EncryptedSecret,EncryptedSecretConverter,MasterKey,SecretCipher}.kt`.
  Excluded: `server/src/main/resources/META-INF/spring.factories` and `server/src/main/resources/application.properties`
  (configuration, not instrumentable; their behavior is exercised by `MasterKeyStartupTests`), `docs/ARCHITECTURE.md`
  (documentation), test sources and `src/test/resources`. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (Gradle 9.8.1,
  plugin default tool version, not pinned in `build.gradle.kts`); `./gradlew check jacocoTestReport` in `server/`; full server test suite.
- Run start/completion time and exit status: 2026-10-20T10:53:20+02:00 to 10:53:31+02:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (mtime 10:53:31.88, sha256 `332e6cdfeda3b073a0dd0dfeb3b4e6eff1af831c4c33d782f15118679373903b`), from `server/build/jacoco/test.exec`
  (mtime 10:53:31.37); HTML in `server/build/reports/jacoco/test/html/`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `:test` and `:jacocoTestReport` executed (not up-to-date)
  because the test sources changed; both artifacts were written inside the run window; no other Gradle process was running.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. JaCoCo counts
instructions (bytecode), not source statements; the Statements column shows the INSTRUCTION counter.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/crypto/CryptoConfiguration.kt` | 23/23 (100%) | N/A (0 branches) | 4/4 (100%) | 8/8 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/crypto/EncryptedSecret.kt` | 31/31 (100%) | 4/4 (100%) | 6/6 (100%) | 7/7 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/crypto/EncryptedSecretConverter.kt` | 34/34 (100%) | 4/4 (100%) | 3/3 (100%) | 5/5 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/crypto/MasterKey.kt` | 53/53 (100%) | 8/8 (100%) | 2/2 (100%) | 13/13 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/crypto/SecretCipher.kt` | 223/223 (100%) | 8/8 (100%) | 6/6 (100%) | 36/36 (100%) |
| **Aggregate (summed counters, all 5 scoped files measured, none missing)** | 364/364 (100%) | 24/24 (100%) | 21/21 (100%) | 69/69 (100%) |

Before the added `EncryptedSecretTests` case, `EncryptedSecret.kt` line 20 (`equals`) had 1 of 4 branches missed
(the "other is not an `EncryptedSecret`" branch, 23/24 branches overall). It is now covered.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| none | - | No uncovered lines or branches remain in the task scope. |

Coverage was not used as a goal in itself: the tests added target the wrong-key and tampering behavior at the
converter and persistence level (AC2/AC3), which the existing tests only checked on `SecretCipher` directly.

## Unable to test

- `.taskcheck` (`scripts/check.sh`) could not be executed by this helper (sandbox permission denied); the parent must run it.
- Production deployment with a real `COCKPIT_MASTER_KEY` from the environment variable: the tests set the
  `cockpit.master-key` property (as command-line args or test properties) rather than the environment variable itself.
  The mapping `cockpit.master-key=${COCKPIT_MASTER_KEY:}` in `application.properties` is standard Spring placeholder
  resolution; no follow-up needed unless the parent wants an explicit env-var test.

## Summary

Tests added and all validation passed (focused crypto tests and full `./gradlew check`); task-scoped coverage is
100% lines/branches for `ch.sonensei.cockpit.core.crypto`; no production defects found. `.taskcheck` was not run by
this helper and must be run by the parent worker.
