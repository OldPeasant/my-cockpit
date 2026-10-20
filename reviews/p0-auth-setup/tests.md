# Test Report

Date: 2026-10-20

Task: p0-auth-setup - First-run setup: owner account, password hashing, TOTP enrollment

Scope: branch `main`, immutable base `bd8a0ab04a406983ebb528963d0eb234651a09a8`, measured HEAD
`2a44a4cd4cc428f56487559ca2afee143c6487a2` (range `bd8a0ab..HEAD`). Task code commits: `b0b88f7`
(implementation), `7d86858` (tests + first reports), `2a44a4c` (fix of review findings CR-1/CR-2); the
other commits in the range are task-state commits (verify/heartbeat/assume) of this and other tasks.
Task ownership: base and commits supplied by the parent worker, cross-checked against the task's
`touches`, its acceptance criteria and `reviews/p0-auth-setup/fixes.md`. Production files changed (all
added by this task): `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/**` (8 files),
`server/src/main/resources/db/migration/core/V202610201000__core_auth_user.sql`, `api/openapi.yaml`
(regenerated) and `web/src/app/api/generated/**` (generated client). This re-check focuses on the
final revision, in particular the pending-account replacement rule added in `2a44a4c`
(`SetupService.start` / `mayReplace`). Uncommitted inputs at measurement time: only the test file
listed below (no production changes in the working tree).

Requirements:
- `tasks/p0-auth-setup.md` acceptance criteria and recorded assumptions (including the 11:58 assumption
  that supersedes the replace rule: same username + password, or pending account older than 1 hour per
  the application Clock, else 409 "Setup is already in progress")
- `SPEC.md` §4.1 (setup flow, Argon2id, TOTP + 10 recovery codes, secrets at rest)
- `AGENTS.md` project notes (never log secrets at INFO or above, fictitious test data)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`
- `reviews/p0-auth-setup/review.md` (CR-1, CR-2) and `reviews/p0-auth-setup/fixes.md`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/SetupEdgeCaseTests.kt` (one test added in this
  re-check, uncommitted; nothing removed or weakened)

Reviewed without changes (as of HEAD `2a44a4c`): `SetupWebTests.kt` (CR-1 tests by the fixer),
`SetupEdgeCaseTests.kt` (CR-1 abandonment test, CR-2 single-row index test, and the two boundary tests
that now delete the pending row between posts; their assertions are unchanged), `TotpTests.kt`,
`RecoveryCodesTests.kt`, `Base32Tests.kt`, `SetupSecretHidingTests.kt`, `SecurityConfigurationTests.kt`.

## Tests added or updated

Count: 1 in this re-check (task total in `core.auth`: 49 tests).

- SetupEdgeCaseTests `the one-hour abandonment rule never reopens setup once the account is active`:
  activates the account, advances the `MutableClock` by 2 days, then posts setup with other credentials
  and with the owner's own credentials. Both answer 409 with detail "Setup is already complete"; the
  account row (id, username, password hash, encrypted secret, confirmation time) and the 10 recovery
  codes stay unchanged. Why: the new time-based rule must apply only to pending accounts. No existing test
  combined an active account with a clock past `ABANDONED_AFTER`, so a reordering of the checks in
  `start()` (abandonment before `isActive`) would have let anyone take over the owner account an hour
  after setup without any test failing.

Review of the replacement-rule tests. These cases were already covered at HEAD:
- same username + password within the hour replaces the account (new row id, old secret rejected, new
  secret confirms): SetupWebTests `starting again with the same username and password replaces the pending account`;
- other username (right password), right username with a wrong password, and both wrong: 409 with the
  same detail, the row and the recovery code hashes unchanged, the original secret still confirms:
  SetupWebTests `starting again with another username or a wrong password is a conflict and keeps the pending account`;
- boundary at exactly 1 h gives 409, 1 h + 1 s lets anyone replace the account, 1 row, 10 codes, new
  secret confirms: SetupEdgeCaseTests `a pending account older than one hour can be replaced by anyone, a younger one cannot`;
- concurrent setups with different usernames: exactly one account, losers get only a conflict:
  SetupEdgeCaseTests `concurrent setup requests ...` (passes with the new rule);
- single-row unique index (CR-2): SetupEdgeCaseTests `the database rejects a second app_user row`.

## Requirement coverage

Command A = `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'`;
Command B = `cd server && ./gradlew test --rerun jacocoTestReport` (full server suite, 595 tests);
Command C = `cd server && ./gradlew check`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `GET /setup/status` tells whether setup is needed | SetupWebTests `setup is required while no account exists`, `setup creates the account and confirming...` | A, B | Pass |
| AC1: `POST /setup` creates user; password >= 12 chars | SetupWebTests `password shorter than 12 characters...`; SetupEdgeCaseTests `password of exactly 12 and 256 characters is accepted, 257 is rejected` | A, B | Pass |
| AC1: username validation (assumption: 1-64, letters/digits/._@-) | SetupWebTests `...invalid usernames are rejected`; SetupEdgeCaseTests `username of 64 characters...is accepted`, `whitespace-only, slash and colon usernames are rejected` | A, B | Pass |
| AC1: malformed input is a 400 Problem, not 500 | SetupEdgeCaseTests `missing, null and malformed request bodies are 400 problems, not server errors` | A, B | Pass |
| AC1: Argon2id password hash | SetupWebTests `password is stored as Argon2id hash...` | A, B | Pass |
| AC1: TOTP secret + otpauth URI returned | SetupWebTests `setup creates the account...`; SetupEdgeCaseTests `otpauth URI percent-encodes the username in the label` | A, B | Pass |
| AC1: 10 recovery codes, stored hashed | RecoveryCodesTests (5); SetupWebTests `...recovery codes only hashed`; SetupEdgeCaseTests `stored recovery code hashes accept the code in any case and without dash` | A, B | Pass |
| AC1: `POST /setup/confirm-totp` activates with a valid code (+-1 step, spaces) | SetupWebTests `setup creates the account and confirming...`, `a wrong or expired code is rejected...`; SetupEdgeCaseTests `a code from the previous or next step...`, `a code two steps away is rejected`, `the confirmation time comes from the application clock`; TotpTests `verify rejects six-character codes with signs or punctuation` | A, B | Pass |
| Assumption (CR-1): pending account replaced only with same username + password | SetupWebTests `starting again with the same username and password replaces the pending account`, `starting again with another username or a wrong password is a conflict and keeps the pending account` | A, B | Pass |
| Assumption (CR-1): pending account older than 1 hour (per Clock) replaceable by anyone; exactly 1 h is not | SetupEdgeCaseTests `a pending account older than one hour can be replaced by anyone, a younger one cannot` | A, B | Pass |
| Assumption (CR-1): abandonment never applies to an active account | SetupEdgeCaseTests `the one-hour abandonment rule never reopens setup once the account is active` (new) | A, B | Pass |
| AC2: after a user exists every setup endpoint returns 409 (status stays 200, assumption) | SetupWebTests `every setup endpoint answers 409 once the account is active`; SetupEdgeCaseTests `setup endpoints stay closed after activation even with a fresh valid body`, `the one-hour abandonment rule never reopens setup...` | A, B | Pass |
| AC2: single user under concurrency (unique index), no 500s (CR-2) | SetupEdgeCaseTests `concurrent setup requests ...`, `concurrent confirmations ...`, `the database rejects a second app_user row` | A, B | Pass |
| AC2: TOTP secret stored encrypted | SetupWebTests `TOTP secret is stored encrypted` | A, B | Pass |
| AC3: RFC 6238 test vectors; tests compute codes from the secret | TotpTests `RFC 6238 test vectors pass` (+ all setup tests generate codes from the returned secret) | A, B | Pass |
| AC3: `./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` passes with Postgres via Testcontainers | all 49 `core.auth` tests | A | Pass |
| AC4: `OpenApiContractTest` passes without the flag (openapi.yaml regenerated in `2a44a4c`) | `OpenApiContractTest` (unchanged) | B | Pass. Regeneration with `UPDATE_OPENAPI=1` was not re-run by the test-writer (it rewrites a tracked file); the fixer reports running it. |
| Never log or echo secrets (AGENTS.md) | SetupEdgeCaseTests `setup never logs or echoes the password, TOTP secret or recovery codes`; SetupWebTests `responses never echo the password`; SetupSecretHidingTests (4) | A, B | Pass |
| Setup public and CSRF-exempt; rest protected | SecurityConfigurationTests (3) | A, B | Pass |

## Focused test execution

Command:

`cd server && ./gradlew ktlintFormat` then `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'`

Result:

Pass: 49 tests, 0 failures, 0 errors, 0 skipped (Base32Tests 4, RecoveryCodesTests 5,
SecurityConfigurationTests 3, SetupEdgeCaseTests 16, SetupSecretHidingTests 4, SetupWebTests 11,
TotpTests 6). `ktlintFormat` made no change to the new test.

## Full test execution

Command:

`cd server && ./gradlew test --rerun jacocoTestReport`, then `cd server && ./gradlew check`

Result:

Pass: 595 tests, 0 failures, 0 errors, 0 skipped (BUILD SUCCESSFUL). `check` BUILD SUCCESSFUL
(ktlint main + test source sets ran; `test` up to date from the same inputs as the `--rerun` run).
The known-flaky, unrelated `ch.sonensei.cockpit.core.persistence.ClockConfigurationTests` (microsecond
application clock compared with a nanosecond `Instant.now()`, see fixes.md) passed in this run.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` -> "== all checks passed" (tfp gate tests, server ktlint + tests, web
eslint + 56 Vitest tests + build, android ktlint + unit tests + lint).

## Bugs found

None. The replacement rule behaves as the recorded assumption describes; no test fails, none is
skipped or disabled.

## Coverage

Status: verified (fresh run on the final snapshot, exit 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to the whole-file line coverage below, because every production Kotlin
file in scope was added by this task (`git diff --name-status bd8a0ab..HEAD -- server/src/main` shows only
`A`), so every executable line is a changed line. Changed-branch coverage is not claimed beyond the
whole-file branch counters.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p0-auth-setup;
  base `bd8a0ab04a406983ebb528963d0eb234651a09a8`; HEAD `2a44a4cd4cc428f56487559ca2afee143c6487a2`;
  task code commits `b0b88f7`, `7d86858`, `2a44a4c` supplied by the parent and checked with `git diff --stat bd8a0ab..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): production = HEAD `2a44a4c` (clean),
  sha256 SetupService.kt `007984972c133043049e4a2ae0caf3f8991e70185bd9d4de49cbaa35a1291bd9`.
  Uncommitted: SetupEdgeCaseTests.kt `95044d3c66a5fa81b148d851b24c49939b12b7e5c8eb978a08f03ab2c9d3d14e`.
  Unchanged committed tests: SetupWebTests `f379040b...d73104`, TotpTests `92abb090...de09`,
  RecoveryCodesTests `25fe048a...dc78f`, SetupSecretHidingTests `7de8109d...37b8`, Base32Tests
  `45020797...4008`, SecurityConfigurationTests `83e9eeb0...6fe7735`. Hashes taken before and after the
  run; identical.
- Exact included files: the 8 Kotlin files under `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/`.
  Excluded: `V202610201000__core_auth_user.sql` (SQL, not instrumentable by JaCoCo; exercised by Flyway
  in every Postgres test, single-row index tested directly), `api/openapi.yaml` and
  `web/src/app/api/generated/**` (generated artifacts). No renamed or deleted files.
  `UserAccountRepository.kt` is a Spring Data interface with no executable bytecode: present in the
  report without counters, shown as N/A (not counted as covered).
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco`
  plugin (Gradle 9.8.1 default tool version, no override); `./gradlew test --rerun jacocoTestReport` in
  `server/`; full `test` task (all non-`external` tests, 595).
- Run start/completion time and exit status: 2026-10-20 12:00:32 to 12:01:02 +02:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (mtime 12:01:02.287, sha256 `1188b8660661ec904b706a7e80954ae1e613a6f8345342a94326e030526523b6`,
  last sessioninfo `dell26-c4a7d6b4` start 1792490436938), HTML in `.../jacoco/test/html/`.
- Evidence outputs are fresh and inputs stayed unchanged: the previous report had mtime 11:51:34 (pre-fix
  revision); `--rerun` forced the test task and the new report mtime is after the run start; counters for
  SetupService.kt changed (26 branches vs 16 before the fix), so the report reflects the final code.
- Final run after latest test additions and production fixes: yes (after fix commit `2a44a4c` and the test
  added in this re-check).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.
Statements = JaCoCo INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `core/auth/api/SetupController.kt` | 97/97 (100%) | N/A | 17/17 (100%) | 28/28 (100%) |
| `core/auth/domain/Base32.kt` | 172/172 (100%) | 14/14 (100%) | 2/2 (100%) | 25/25 (100%) |
| `core/auth/domain/RecoveryCodes.kt` | 92/92 (100%) | 4/4 (100%) | 5/5 (100%) | 6/6 (100%) |
| `core/auth/domain/SetupService.kt` | 310/313 (99.0%) | 25/26 (96.2%) | 16/16 (100%) | 50/50 (100%) |
| `core/auth/domain/Totp.kt` | 169/169 (100%) | 8/8 (100%) | 7/7 (100%) | 17/17 (100%) |
| `core/auth/domain/UserAccount.kt` | 101/126 (80.2%) | 6/6 (100%) | 13/20 (65.0%) | 23/27 (85.2%) |
| `core/auth/infra/SecurityConfiguration.kt` | 76/76 (100%) | N/A | 6/6 (100%) | 13/13 (100%) |
| `core/auth/infra/UserAccountRepository.kt` | N/A | N/A | N/A | N/A |
| **Aggregate (summed counters, 8 files; excluded: SQL migration, openapi.yaml, generated web client)** | 1017/1045 (97.3%) | 57/58 (98.3%) | 66/73 (90.4%) | 162/166 (97.6%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `core/auth/domain/SetupService.kt:84` (1 of 26 branches: `pending.createdAt ?: return false`) | Pending account without `createdAt` is never replaceable | Justified gap: `createdAt` is set by JPA auditing on every insert (non-null in the schema), so a persisted pending account always has it; the null branch is a defensive fallback that fails closed (409). Not reachable through the API without corrupting the entity. |
| `core/auth/domain/SetupService.kt` (ordering of `isActive` vs abandonment) | Abandonment must not apply to an active account | Covered: new test `the one-hour abandonment rule never reopens setup once the account is active` |
| `core/auth/domain/UserAccount.kt:24,26,28` | Generated setters of `username`, `passwordHash`, `totpSecret` (`var`), not used by the setup flow | Justified gap: no task behavior changes these fields; later tasks (password change, TOTP reset) will exercise them. The getters are now covered through `mayReplace`. |
| `core/auth/domain/UserAccount.kt:57,59,62,63` | Getters of `RecoveryCode.user` / `codeHash`, `usedAt` getter/protected setter | Justified gap: reading and consuming recovery codes belongs to the login/recovery task; stored hashes are verified through SQL in SetupWebTests and SetupEdgeCaseTests. |

## Unable to test

- OpenAPI regeneration (`UPDATE_OPENAPI=1 ...`) was not re-run by the test-writer (it rewrites a tracked
  file); the fixer reports running it after CR-1, and the contract test without the flag passes here.
  The parent should keep its own evidence for that half of AC4.
- The generated web client (`web/src/app/api/generated/**`) is generated code; it compiles in the
  `.taskcheck` web build. No behavioral tests were added (no consumer exists in this task).
- Response-time equality of the two failed replacement checks (the password hash is always computed) is
  not tested: a timing assertion would be flaky. The identical 409 detail is tested.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production
defects found.
