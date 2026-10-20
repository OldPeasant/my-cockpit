# Test Report

Date: 2026-10-20

Task: p0-crypto - Secret encryption at rest

Scope: `git diff b5ceb4b77dc7cecc8031b8533dc628b4e56d9f42..a1649dd70cf9cae9339d4bc34ae8cb4eb636f181` on `main`
(task commits 9621215, c596e4b, f19e6e4, a1649dd as given by the parent worker; `git log b5ceb4b..HEAD` lists exactly
these four, and the production changes match the task's `touches` plus the two resource files named in the task
assumptions). Re-run on the final code after fix CR-1 (property renamed to `cockpit.crypto.master-key`), plus the
uncommitted test change listed below. No uncommitted production changes.

Requirements:
- `tasks/p0-crypto.md` acceptance criteria and its recorded assumptions (stored format `version | nonce | ciphertext+tag`
  with the version byte as AAD, JSON/toString redaction as `******`, master key read through the placeholder
  `cockpit.crypto.master-key=${COCKPIT_MASTER_KEY:}` after CR-1)
- `SPEC.md` §4.1 (via the task goal), `docs/ARCHITECTURE.md` "Testing" and "Coverage", `docs/QUALITY.md`, `AGENTS.md`
- `reviews/p0-crypto/review.md` (CR-1) and `reviews/p0-crypto/fixes.md`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/crypto/MasterKeyStartupTests.kt` (one test added in this run, uncommitted)

## Tests added or updated

Count: 1 (the crypto package now has 31 tests: 30 committed at a1649dd plus this one)

- `MasterKeyStartupTests`: `outside tests the master key is read from the COCKPIT_MASTER_KEY variable`. It starts the real
  `SpringApplication` against Testcontainers Postgres with `--spring.config.location=classpath:/application.properties`
  (only the main configuration, as in a deployment, so the fictitious key from `src/test/resources/config` is not
  loaded) and a `StandardEnvironment` whose system environment contains a fictitious `COCKPIT_MASTER_KEY`. It asserts
  that `cockpit.crypto.master-key` resolves to that value and that a secret encrypted by the application's
  `SecretCipher` bean decrypts with a cipher built from that key.

Why: after the CR-1 rename the variable no longer relax-binds to the property; production depends solely on the
placeholder in the main `application.properties`. The existing tests only set the property directly, and the
CR-1 regression test proves the test key wins over the variable. Nothing proved the opposite direction (outside tests,
the variable actually reaches the cipher). This closes the gap listed under "Unable to test" in the previous report.

Existing tests were reviewed against the renamed property: `MasterKeyTests` and `MasterKeyStartupTests` use
`cockpit.crypto.master-key`; no test refers to the old `cockpit.master-key`. No existing test was changed, weakened or
disabled.

## Requirement coverage

Command for all rows: `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.crypto.*'`
(also run as part of `cd server && ./gradlew check jacocoTestReport`). Testcontainers Postgres was available and used.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `SecretCipher` AES-256-GCM, random nonce, versioned format | `SecretCipherTests`: `round trip returns the plaintext`, `ciphertext uses the versioned format and does not contain the plaintext`, `each encryption uses a fresh random nonce`, `unknown version, truncated and non-base64 input are rejected`, `key must be 32 bytes` | see above | Pass |
| AC1: key from `COCKPIT_MASTER_KEY` (base64, 32 bytes) | `MasterKeyTests`: `a base64 32-byte key is accepted`, `context with a valid key provides the cipher`; `MasterKeyStartupTests`: `application starts with a valid master key`, `outside tests the master key is read from the COCKPIT_MASTER_KEY variable` (new) | see above | Pass |
| AC1: startup fails with a clear message if missing/invalid | `MasterKeyTests`: `missing key fails with a clear message`, `invalid key fails without echoing the value`, `context fails to start without a key`, `context fails to start with an invalid key`, `failure analyzer explains how to fix the key`; `MasterKeyStartupTests` (real `SpringApplication.run` against Testcontainers Postgres): `application refuses to start without a master key and says why`, `application refuses to start with an invalid master key without logging it` | see above | Pass |
| AC1: "outside tests" (tests get a fictitious key, not overridable by an exported variable, CR-1) | `MasterKeyStartupTests.an exported COCKPIT_MASTER_KEY does not override the fictitious test key`; every `@SpringBootTest` in the suite starts with `src/test/resources/config/application.properties` | `cd server && ./gradlew check jacocoTestReport` | Pass |
| AC2: JPA converter stores ciphertext | `EncryptedSecretPersistenceTests`: `secret is stored as ciphertext and read back as plaintext`, `null secret stays null`; `EncryptedSecretConverterTests` (all 4) | see above | Pass |
| AC2: `toString` / Jackson never reveal the value | `EncryptedSecretTests`: `toString never reveals the value`, `Jackson serialization never reveals the value`; `EncryptedSecretPersistenceTests.application JSON mapper redacts the secret` | see above | Pass |
| AC3: round trip | `SecretCipherTests.round trip returns the plaintext`, persistence round trip, new startup test (bean cipher to key-derived cipher) | see above | Pass |
| AC3: tampered ciphertext rejected | `SecretCipherTests.tampered ciphertext is rejected` (nonce, body, tag bit flips), `EncryptedSecretPersistenceTests.stored secret written with another master key or tampered is not loaded` | see above | Pass |
| AC3: wrong key rejected | `SecretCipherTests.wrong key is rejected`, `EncryptedSecretConverterTests.column written with another master key is rejected`, persistence test above | see above | Pass |
| AC3: crypto test command passes, Postgres via Testcontainers where persistence is involved | whole `ch.sonensei.cockpit.core.crypto.*` selection: 31 tests in 6 suites, 0 failures, 0 errors, 0 skipped | see above | Pass |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.crypto.*'`

Result:

Pass (BUILD SUCCESSFUL, 2026-10-20T10:58:21+02:00 to 10:58:31+02:00). Results: `EncryptedSecretConverterTests` 4,
`SecretCipherTests` 7, `EncryptedSecretTests` 4, `MasterKeyStartupTests` 5, `MasterKeyTests` 7,
`EncryptedSecretPersistenceTests` 4; 31 tests, 0 failures, 0 errors, 0 skipped. ktlint over the test source set
ran and was clean.

## Full test execution

Command:

`cd server && ./gradlew check jacocoTestReport`

Result:

Pass (BUILD SUCCESSFUL, 2026-10-20T10:58:43+02:00 to 10:58:54+02:00, after the last test addition): ktlint main/test/scripts
clean, 52 server tests in 15 suites, 0 failures, 0 errors, 0 skipped (31 of them in `core.crypto`). No other Gradle
process was running (confirmed by the parent worker and `pgrep`).

## .taskcheck

Result:

Not run: `sh scripts/check.sh` (also runs the web and Android checks) was not executed by this helper; earlier attempts
needed sandbox approval. Its server step (`./gradlew check`) was run above and passed. The parent worker must run
`.taskcheck` itself.

## Bugs found

None. No test exposed behavior that contradicts the acceptance criteria, the recorded assumptions or the CR-1 fix.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. All five Kotlin files are new in this task (every line is an added
line), so whole-file coverage equals changed-line coverage for the Kotlin sources here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p0-crypto; base
  `b5ceb4b77dc7cecc8031b8533dc628b4e56d9f42`; HEAD `a1649dd70cf9cae9339d4bc34ae8cb4eb636f181`; commits 9621215, c596e4b,
  f19e6e4, a1649dd supplied by the parent worker, cross-checked with `git diff --stat b5ceb4b..HEAD` and the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD a1649dd plus one uncommitted test file,
  `MasterKeyStartupTests.kt` sha256 `603119fe44767d33766b7b4979545a593d077668141d1cb3b266b113378b276d` (taken before
  the full run and re-checked after it, unchanged). Production sha256 (identical to HEAD, unchanged during the runs):
  `CryptoConfiguration.kt` 1d134b04..., `EncryptedSecretConverter.kt` 507ea064..., `EncryptedSecret.kt` d76c3f5f...,
  `MasterKey.kt` 1647f183..., `SecretCipher.kt` 0df0091f...
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/core/crypto/{CryptoConfiguration,EncryptedSecret,EncryptedSecretConverter,MasterKey,SecretCipher}.kt`.
  Excluded: `server/src/main/resources/META-INF/spring.factories` and `server/src/main/resources/application.properties`
  (configuration, not instrumentable by JaCoCo; their behavior is exercised by `MasterKeyStartupTests`, including the
  new env-var test for the placeholder), `docs/ARCHITECTURE.md` and `reviews/p0-crypto/*` (documentation), test sources
  and `src/test/resources`. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (Gradle 9.8.1,
  plugin default tool version, not pinned in `build.gradle.kts`); `./gradlew check jacocoTestReport` in `server/`; full
  server test suite.
- Run start/completion time and exit status: 2026-10-20T10:58:43+02:00 to 10:58:54+02:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (mtime 10:58:54.29, sha256 `578bed21fb2a6ef10bd13e45e387e93efd4573ea3ff3f63bf0dfb67333c653ab`), from
  `server/build/jacoco/test.exec` (mtime 10:58:53.77); HTML in `server/build/reports/jacoco/test/html/`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `:test` and `:jacocoTestReport` executed (not up-to-date);
  both artifacts were written inside the run window; source hashes were identical before and after.
- Final run after latest test additions and production fixes: yes (after fix CR-1 at a1649dd and after this run's test addition).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. JaCoCo counts
instructions (bytecode), not source statements; the Statements column shows the INSTRUCTION counter.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/crypto/CryptoConfiguration.kt` | 23/23 (100%) | N/A (0 branches) | 4/4 (100%) | 8/8 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/crypto/EncryptedSecret.kt` | 31/31 (100%) | 4/4 (100%) | 6/6 (100%) | 7/7 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/crypto/EncryptedSecretConverter.kt` | 34/34 (100%) | 4/4 (100%) | 3/3 (100%) | 5/5 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/crypto/MasterKey.kt` | 53/53 (100%) | 8/8 (100%) | 2/2 (100%) | 13/13 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/crypto/SecretCipher.kt` | 223/223 (100%) | 8/8 (100%) | 6/6 (100%) | 36/36 (100%) |
| **Aggregate (JaCoCo package counters for `ch.sonensei.cockpit.core.crypto`, all 5 scoped files measured, none missing)** | 364/364 (100%) | 24/24 (100%) | 21/21 (100%) | 69/69 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| none | - | No uncovered lines or branches remain in the task scope. |

Coverage was already 100% before this run's addition; the new test was added for behavior JaCoCo cannot see (the
property placeholder in `application.properties` that connects `COCKPIT_MASTER_KEY` to the cipher after CR-1), not to
raise a number.

## Unable to test

- `.taskcheck` (`scripts/check.sh`) was not executed by this helper; the parent must run it.
- The real OS environment variable of a deployed process: the new test injects `COCKPIT_MASTER_KEY` through
  `StandardEnvironment.getSystemEnvironment()` (the same source Spring reads OS variables from) instead of launching a
  separate JVM with a modified environment. No follow-up needed.

## Summary

Tests added and all validation passed (focused crypto tests and full `./gradlew check`); task-scoped coverage at
a1649dd plus one added test is 100% instructions/branches/methods/lines for `ch.sonensei.cockpit.core.crypto`; no
production defects found. `.taskcheck` was not run by this helper and must be run by the parent worker.
