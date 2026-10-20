# Test Report

Date: 2026-10-20

Task: p0-auth-setup - First-run setup: owner account, password hashing, TOTP enrollment

Scope: branch `main`, immutable base `bd8a0ab04a406983ebb528963d0eb234651a09a8`, measured HEAD
`2a2664ddfb570dc7dc7dcfeca8479800d9b48133` (range `bd8a0ab..HEAD`; the only code commit is `2a2664d`,
the rest are task-state commits). Task ownership: base and commit supplied by the parent worker and
cross-checked against the task's `touches` and acceptance criteria. Production files changed (all added):
`server/src/main/kotlin/ch/sonensei/cockpit/core/auth/**` (8 files),
`server/src/main/resources/db/migration/core/V202610201000__core_auth_user.sql`, `api/openapi.yaml`
(regenerated) and `web/src/app/api/generated/**` (generated client). Uncommitted inputs at measurement
time: only the test files listed below (no production changes in the working tree).

Requirements:
- `tasks/p0-auth-setup.md` acceptance criteria and recorded assumptions
- `SPEC.md` §4.1 (setup flow, Argon2id, TOTP + 10 recovery codes, secrets at rest)
- `AGENTS.md` project notes (never log secrets at INFO or above, fictitious test data)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/RecoveryCodesTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/SetupSecretHidingTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/SetupEdgeCaseTests.kt` (new, Spring + Postgres
  Testcontainer, fixed `MutableClock` via the existing `MutableClockTestConfiguration`)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/TotpTests.kt` (one test added, nothing removed)

Existing `SetupWebTests`, `Base32Tests`, `SecurityConfigurationTests` were not changed.

## Tests added or updated

Count: 23 (RecoveryCodesTests 5, SetupSecretHidingTests 4, SetupEdgeCaseTests 13, TotpTests 1).
The `core.auth` package now runs 45 tests (before: 22).

## Requirement coverage

Command A = `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'`;
Command B = `cd server && ./gradlew test --rerun jacocoTestReport` (full server suite, 591 tests).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `GET /setup/status` tells whether setup is needed | SetupWebTests `setup is required while no account exists`, `setup creates the account and confirming...` | A, B | Pass |
| AC1: `POST /setup` creates user; password >= 12 chars | SetupWebTests `password shorter than 12 characters...`; SetupEdgeCaseTests `password of exactly 12 and 256 characters is accepted, 257 is rejected` | A, B | Pass |
| AC1: username validation (assumption: 1-64, letters/digits/._@-) | SetupWebTests `...invalid usernames are rejected`; SetupEdgeCaseTests `username of 64 characters...is accepted`, `whitespace-only, slash and colon usernames are rejected` | A, B | Pass |
| AC1: malformed input is a 400 Problem, not 500 | SetupEdgeCaseTests `missing, null and malformed request bodies are 400 problems, not server errors` | A, B | Pass |
| AC1: Argon2id password hash | SetupWebTests `password is stored as Argon2id hash...` | A, B | Pass |
| AC1: TOTP secret + otpauth URI returned | SetupWebTests `setup creates the account...`; SetupEdgeCaseTests `otpauth URI percent-encodes the username in the label` | A, B | Pass |
| AC1: 10 recovery codes, stored hashed | RecoveryCodesTests (5 tests: count/format, no look-alikes, uniqueness, normalize); SetupWebTests `...recovery codes only hashed`; SetupEdgeCaseTests `stored recovery code hashes accept the code in any case and without dash` | A, B | Pass |
| AC1: `POST /setup/confirm-totp` activates with a valid code (+-1 step drift, spaces) | SetupWebTests `setup creates the account and confirming...`, `a wrong or expired code is rejected...`; SetupEdgeCaseTests `a code from the previous or next step and a code with spaces confirm the account`, `a code two steps away is rejected`, `the confirmation time comes from the application clock`; TotpTests `verify rejects six-character codes with signs or punctuation` | A, B | Pass |
| AC2: after a user exists every setup endpoint returns 409 (status stays 200, assumption) | SetupWebTests `every setup endpoint answers 409 once the account is active`; SetupEdgeCaseTests `setup endpoints stay closed after activation even with a fresh valid body` | A, B | Pass |
| AC2: single user under concurrency (unique index), no 500s | SetupEdgeCaseTests `concurrent setup requests leave exactly one account and fail only with a conflict`, `concurrent confirmations activate the account once and fail only with a conflict` | A, B (and 2 extra `--rerun` runs of SetupEdgeCaseTests) | Pass |
| AC2: TOTP secret stored encrypted | SetupWebTests `TOTP secret is stored encrypted` | A, B | Pass |
| AC3: RFC 6238 test vectors; tests compute codes from the secret | TotpTests `RFC 6238 test vectors pass` (+ all setup tests generate codes from the returned secret) | A, B | Pass |
| AC3: `./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` passes with Postgres via Testcontainers | all 45 `core.auth` tests | A | Pass |
| AC4: `OpenApiContractTest` passes without the flag | `OpenApiContractTest` (unchanged) | B | Pass (1/1). The regeneration with `UPDATE_OPENAPI=1` was not re-run by the test-writer. |
| Never log or echo secrets (AGENTS.md) | SetupEdgeCaseTests `setup never logs or echoes the password, TOTP secret or recovery codes`; SetupWebTests `responses never echo the password`; SetupSecretHidingTests (4 tests: `toString` of request/response/enrollment/entities, single activation) | A, B | Pass |
| Setup is public and CSRF-exempt; rest stays protected | SecurityConfigurationTests (3, unchanged) | A, B | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'`

Result:

Pass: 45 tests, 0 failures, 0 skipped (after one correction of my own test: an "invalid" password
of exactly 12 characters was actually valid). Concurrency tests were re-run twice more with
`./gradlew test --rerun --tests 'ch.sonensei.cockpit.core.auth.SetupEdgeCaseTests'`: Pass both times.

## Full test execution

Command:

`cd server && ./gradlew test --rerun jacocoTestReport`, then `cd server && ./gradlew check`

Result:

Pass: 42 suites, 591 tests, 0 failures, 0 errors, 0 skipped; `check` (ktlint main + test source sets,
test up to date from the same inputs) BUILD SUCCESSFUL.

Note: the first two attempts failed with Gradle's
`NoSuchFileException: build/test-results/test/binary/in-progress-results-generic.bin`, caused by another
Gradle test run (the concurrently running code-reviewer) in the same `server/build`. Retrying after it
finished passed; this was not a test failure.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` exit 0 ("all checks passed": server ktlint + tests, web lint + 56 Vitest
tests + build, android ktlint + unit tests + lint).

## Bugs found

None. No production defect was found; no test is failing, skipped or disabled.

## Unable to test

- OpenAPI regeneration (`UPDATE_OPENAPI=1 ...`) was not re-run by the test-writer (it rewrites a
  tracked file); the parent should keep its own evidence for that half of AC4. The contract test
  without the flag passes.
- The generated web client (`web/src/app/api/generated/**`) is generated code; it compiles in the
  `.taskcheck` web build, no behavioural tests were added (no consumer exists in this task).
- The migration SQL is not instrumented by JaCoCo; it is exercised by Flyway in every Postgres test,
  Hibernate `ddl-auto=validate` checks the mapping, and the single-row unique index is exercised by the
  concurrent-setup test.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production
defects found.

## Coverage

Status: verified (fresh run on the final test snapshot, exit 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to the whole-file line coverage below, because every production Kotlin
file in scope was added by this task (`git diff --name-status bd8a0ab..HEAD -- server/src/main`
shows only `A`), so every executable line is a changed line. Changed-branch coverage is not separately
claimed beyond the whole-file branch counters.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p0-auth-setup;
  base `bd8a0ab04a406983ebb528963d0eb234651a09a8`; HEAD `2a2664ddfb570dc7dc7dcfeca8479800d9b48133`;
  task code commit `2a2664d` supplied by the parent and checked with `git diff --name-status bd8a0ab..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): production = HEAD `2a2664d` (clean);
  uncommitted test files, sha256: RecoveryCodesTests `25fe048a...dc78f`, SetupEdgeCaseTests
  `349089d9...4e41861`, SetupSecretHidingTests `7de8109d...7b8`, TotpTests `92abb090...de09`
  (unchanged: SetupWebTests `1b95708a...59a3`, Base32Tests `45020797...4008`, SecurityConfigurationTests
  `83e9eeb0...6fe7735`). Hashes taken before and after the run; identical.
- Exact included files: the 8 Kotlin files under `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/`.
  Excluded: `V202610201000__core_auth_user.sql` (SQL, not instrumentable by JaCoCo; exercised by Flyway),
  `api/openapi.yaml` and `web/src/app/api/generated/**` (generated artifacts). No renamed or deleted files.
  `UserAccountRepository.kt` is a Spring Data interface without executable bytecode: present in the
  report with no counters, shown as N/A (not counted as covered).
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco`
  plugin (Gradle 9.8.1 default tool version, no override in `build.gradle.kts`);
  `./gradlew test --rerun jacocoTestReport` in `server/`; full `test` task (all non-`external` tests).
- Run start/completion time and exit status: 2026-10-20 11:51:05 to 11:51:34 +02:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (mtime 11:51:34.695, sha256 `9bc2ea3d4de50cd262a7495166f41f3c6693b2d8f595bfc03a4faac27b8a9d62`,
  last sessioninfo `dell26-3e5420e5` start 1792489869284), HTML in `.../jacoco/test/html/`.
- Evidence outputs are fresh and inputs stayed unchanged: `--rerun` forced the test task; the report
  mtime is after the run start; it includes the TotpTests change made just before (TotpTests 6 tests,
  `Totp.kt` branches moved from 7/8 to 8/8 versus the previous run).
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.
Statements = JaCoCo INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `core/auth/api/SetupController.kt` | 97/97 (100%) | N/A | 17/17 (100%) | 28/28 (100%) |
| `core/auth/domain/Base32.kt` | 172/172 (100%) | 14/14 (100%) | 2/2 (100%) | 25/25 (100%) |
| `core/auth/domain/RecoveryCodes.kt` | 92/92 (100%) | 4/4 (100%) | 5/5 (100%) | 6/6 (100%) |
| `core/auth/domain/SetupService.kt` | 254/254 (100%) | 16/16 (100%) | 12/12 (100%) | 43/43 (100%) |
| `core/auth/domain/Totp.kt` | 169/169 (100%) | 8/8 (100%) | 7/7 (100%) | 17/17 (100%) |
| `core/auth/domain/UserAccount.kt` | 95/126 (75.4%) | 6/6 (100%) | 11/20 (55.0%) | 21/27 (77.8%) |
| `core/auth/infra/SecurityConfiguration.kt` | 76/76 (100%) | N/A | 6/6 (100%) | 13/13 (100%) |
| `core/auth/infra/UserAccountRepository.kt` | N/A | N/A | N/A | N/A |
| **Aggregate (summed counters, 8 files; excluded: SQL migration, openapi.yaml, generated web client)** | 955/986 (96.9%) | 48/48 (100%) | 60/69 (87.0%) | 153/159 (96.2%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `core/auth/domain/Totp.kt:37` (1 of 8 branches, previous run) | code with a character below `'0'` must be rejected | Added TotpTests `verify rejects six-character codes with signs or punctuation`; now 8/8 |
| `core/auth/domain/UserAccount.kt:24,26` | generated setters of `username` / `passwordHash` (`var`), not used by the setup flow | Justified gap: no task behavior changes these fields; later tasks (password change) will exercise them |
| `core/auth/domain/UserAccount.kt:57,59,62,63` | generated getters of `RecoveryCode.user` / `codeHash` and `usedAt` getter/protected setter | Justified gap: reading/consuming recovery codes belongs to the login/recovery task; stored hashes are verified through SQL in SetupWebTests and SetupEdgeCaseTests |
