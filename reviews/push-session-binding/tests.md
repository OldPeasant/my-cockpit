# Test Report

Date: 2026-10-26

Task: push-session-binding - Bind Web Push subscriptions to the login session

Scope: `98053b81c713e30fe85c7b431960a6d142f23f3e..ed147c713ed3c1c1f4ea219f052e5cd6b70ca05c` on branch main, limited to `server/` and `api/`. Task-owned commits: 99ff0179c (implementation), 7fe4c21c3 (test-writer round 1: PushSessionLifecycleTests), ed147c713 (fix for CR-1/TW-1: FK violation on subscribe answers 401 Problem Details). Ownership: base and commits supplied by the parent worker, cross-checked against the task's `touches`, acceptance criteria and `reviews/push-session-binding/fixes.md`. This is round 2, on the final code.

Requirements:
- `tasks/push-session-binding.md` (acceptance criteria, notes and recorded assumptions)
- `SPEC.md` §4.1 / §4.2 (Problem Details) / §4.6 as referenced by the task, `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- `reviews/push-session-binding/fixes.md` (CR-1 fix intent)
- Parent instructions (test-writer cannot run Gradle; parent runs tests and coverage)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/push/PushSessionLifecycleTests.kt` (round 1, committed in 7fe4c21c3)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/push/PushServiceSubscribeFailureTests.kt` (round 2, new, uncommitted, NOT yet run)

Reviewed, unchanged: `PushWebTests.kt` including the fixer's new test `subscribing with a session that ended during the request gets 401 and stores nothing` (real deleted session in Postgres, 401 Problem Details, nothing stored, an existing subscription is not moved to the ended session). It is meaningful and exercises the real FK path end to end. Also `PushSessionBindingTests.kt`, `PushTestSessions.kt`, `PushDeliveryTests.kt`, the WebPushCrypto tests. No existing test was weakened.

## Tests added or updated

Count: 13 in total for the task by the test-writer (10 in round 1, 3 in round 2).

Round 1 (`PushSessionLifecycleTests`, 10 tests): session id on the principal for password, cookie and passkey login; device token has no session and gets 403; remember-device lifetime boundary; expired-session cleanup on a new login cascades subscriptions; expired cookie cannot subscribe (401); upsert version behavior for same/new session; all subscriptions of one session end with it; sliding expiry keeps pushes.

Round 2 (`PushServiceSubscribeFailureTests`, 3 unit tests with MockK, as in `PushDispatcherFailureTests`), closing the one gap of the fix that the integration test cannot reach (the non-FK branch of `if (!isForeignKeyViolation(e)) throw e`):

1. `a foreign key violation, deep in the causes, answers 401 and looks nothing up` - SQLSTATE 23503 two causes below the DataIntegrityViolationException gives ErrorResponseException 401 with detail "The login session has ended", and no `findByEndpoint` follows on the aborted transaction.
2. `any other integrity violation is rethrown unchanged, not turned into 401` - SQLSTATE 23505 is rethrown as the same instance (an unrelated DB error must not look like a logout).
3. `an integrity violation without any SQL state is rethrown` - no SQLException in the cause chain.

Test data is fictitious (`push.example.test`, fixed UUID and clock). Only the repository is mocked; the classification logic under test is the real `PushService`.

## Requirement coverage

Results below were run by the parent on ed147c713 (the test-writer cannot run Gradle). The round-2 tests were added after that run and have not been executed.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `push_subscription` references `auth_session` with on delete cascade | PushSessionBindingTests (logout, revoked sessions, account deletion); PushSessionLifecycleTests (one session's subscriptions all end; expired-session cleanup on login) | `./gradlew test --tests 'ch.sonensei.cockpit.core.push.*' --tests '*OpenApiContractTest'` (parent, ed147c713) | Pass (127 tests, 0 failures) |
| AC1: PUT stores the current session (session id in CockpitPrincipal) | PushSessionBindingTests `subscribing stores the session of the request`; PushSessionLifecycleTests 1, 2, 4; PushWebTests | same | Pass |
| AC1 (assumption): no browser session (device token) gets 403 Problem Details | PushWebTests; PushSessionLifecycleTests 3 | same | Pass |
| AC1: re-subscribe from a new session moves the subscription | PushSessionBindingTests; PushSessionLifecycleTests 8 | same | Pass |
| CR-1/TW-1 fix: session ended during subscribe answers 401 Problem Details, nothing stored, existing subscription not moved | PushWebTests `subscribing with a session that ended during the request gets 401 and stores nothing` | same | Pass |
| CR-1/TW-1 fix: only FK violations become 401, other integrity errors are rethrown | PushServiceSubscribeFailureTests (3 tests) | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.push.PushServiceSubscribeFailureTests'` | Not run: added after the parent's run; Gradle blocked for the test-writer, parent must run it |
| CR-1 fix: 401 documented in the API contract | OpenApiContractTest; `updateOpenApi` regenerated `api/openapi.yaml`; `npm run api:gen` | parent, ed147c713 | Pass (no change in web generated client) |
| AC2: no pushes to subscriptions of expired sessions; they are deleted | PushSessionBindingTests (idle timeout, exact boundary, expired cookie reuse); PushSessionLifecycleTests 5, 6, 10 | first command | Pass |
| AC3: push tests pass, with tests for logout, idle expiry and revoked session | all of `core.push.*` | first command | Pass |
| Note: PushService uses WebPushEncryption, RFC 8291 vector test kept | PushDeliveryTests, WebPushCryptoRfc8291Tests | first command | Pass |

## Focused test execution

Command:

`./gradlew test --tests 'ch.sonensei.cockpit.core.push.*' --tests '*OpenApiContractTest'` (run by the parent on ed147c713, in `server/`)

Result:

Pass - 127 tests, 0 failures. Hibernate logs the FK violation at WARN (`org.hibernate.orm.jdbc.error`); no ERROR from ProblemDetailsAdvice.

Pending: `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.push.PushServiceSubscribeFailureTests' ktlintCheck` for the round-2 file (not run).

## Full test execution

Command:

`cd server && ./gradlew check jacocoTestReport` (run by the parent on ed147c713)

Result:

Pass - BUILD SUCCESSFUL, 5127 tests, 0 failures, ktlint OK. Does not include `PushServiceSubscribeFailureTests`.

## .taskcheck

Result:

Not run by the test-writer (`sh scripts/check.sh` invokes Gradle). The parent runs it before `done`.

## Bugs found

None. TW-1 from round 1 (subscribe racing a logout answered 500) is fixed in ed147c713 and covered by the PushWebTests 401 test and the round-2 unit tests.

## Coverage

Status: verified for ed147c713 without the round-2 tests (parent's JaCoCo run); the round-2 tests are not yet measured.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: push-session-binding; base 98053b81c713e30fe85c7b431960a6d142f23f3e; measured HEAD ed147c713ed3c1c1f4ea219f052e5cd6b70ca05c; commits 99ff0179c, 7fe4c21c3, ed147c713, supplied by the parent and checked against `touches`.
- Source/test snapshot identifier (including uncommitted inputs): ed147c713 with a clean `server/` tree at measurement time. Added afterwards (not measured): `PushServiceSubscribeFailureTests.kt` sha256 bcab91341996e23c93850dccc994884744b4e9d22951c076a7a96368686dbb68.
- Exact included files: `core/auth/domain/LoginService.kt`, `core/push/api/PushController.kt`, `core/push/domain/PushEntities.kt`, `core/push/domain/PushService.kt`, `core/push/infra/PushRepositories.kt`, `core/push/infra/WebPushCrypto.kt`, `core/push/infra/WebPushSender.kt` (all under `server/src/main/kotlin/ch/sonensei/cockpit/`). Excluded: `server/src/main/resources/db/migration/core/V202610261800__core_push_session.sql` (SQL, not instrumented by JaCoCo; applied by Flyway in every Postgres test), `api/openapi.yaml` (generated), test files. `PushRepositories.kt` is a Spring Data interface with no executable lines (measured, zero denominator).
- Provider/version, command, working directory, and test selection: JaCoCo via Gradle, `./gradlew check jacocoTestReport` in `server/`, full server test suite (parent run).
- Run start/completion time and exit status: completed with BUILD SUCCESSFUL (exit 0); report written 2026-10-26 19:06 local, after the ed147c713 commit (19:00).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 9294618f4f1c53e1bc1451dfb361a47c1ceddb11f2ddf2de86817567346728c2. The test-writer read the per-line counters from this file; they match the parent's numbers.
- Evidence outputs are fresh and inputs stayed unchanged: yes for ed147c713 production code; the only later change is the new test file above.
- Final run after latest test additions and production fixes: no - after the latest production fix yes, but not after the round-2 test additions. Expected effect of a rerun: PushService.kt branches 41/44 (line 87 rethrow branch covered); the parent should rerun if fresh numbers are required.

Values below use covered/total (percentage) from JaCoCo counters; zero-denominator metrics are N/A.

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `core/push/domain/PushService.kt` | 468/489 (95.7%) | 40/44 (90.9%) | 12/13 (92.3%) | 87/91 (95.6%) |
| `core/push/domain/PushEntities.kt` | 83/98 (84.7%) | N/A | 12/16 (75.0%) | 20/23 (87.0%) |
| `core/push/api/PushController.kt` | 110/110 (100%) | 4/4 (100%) | 14/14 (100%) | 30/30 (100%) |
| `core/push/infra/WebPushSender.kt` | 167/167 (100%) | 10/10 (100%) | 11/11 (100%) | 34/34 (100%) |
| `core/push/infra/WebPushCrypto.kt` | 430/430 (100%) | 23/26 (88.5%) | 11/11 (100%) | 63/63 (100%) |
| `core/push/infra/PushRepositories.kt` | N/A | N/A | N/A | N/A (interface only) |
| `core/auth/domain/LoginService.kt` | 753/756 (99.6%) | 60/60 (100%) | 49/50 (98.0%) | 108/108 (100%) |
| Aggregate (summed counters, 6 measured files; migration SQL excluded, PushRepositories zero denominator) | 2011/2050 (98.1%) | 137/144 (95.1%) | 109/115 (94.8%) | 342/349 (98.0%) |

Uncovered locations (from the JaCoCo XML above) and their disposition:

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `PushService.kt:87` branch `!isForeignKeyViolation(e)` true (rethrow) | CR-1 fix: an unrelated integrity error must not turn into 401 | Added PushServiceSubscribeFailureTests 2 and 3 (not yet run) |
| `PushService.kt:95` `checkNotNull` null branch | Defensive: row vanished right after the upsert in the same transaction | Not reachable in practice; left uncovered |
| `PushService.kt:106` `count()` | Not task behavior (pre-existing helper, unused by the task's tests) | Out of task responsibility; left uncovered |
| `PushService.kt:137` branch of `in 200..299` (status below 200) | Push service answering 1xx | Not a realistic final status from `HttpClient`; other non-2xx paths covered; left uncovered |
| `PushService.kt:146-148` InterruptedException catch | Stop sending on interrupt, keep the interrupt flag | Pre-existing, unchanged by the task; low risk; left uncovered |
| `PushService.kt:177` one branch of `uri.host.isNullOrEmpty()` | Endpoint validation (null vs empty host) | Pre-existing; the rejecting path is covered; left uncovered |
| `PushEntities.kt:25, 45, 49, 53` (`singleton` default, `protected set` accessors) | JPA plumbing, no behavior | Non-material |
| `WebPushCrypto.kt:116` 3 branches of the coordinate range check | Rejecting out-of-range public key coordinates | Pre-existing validation, not changed in meaning by the task; non-material for this task |
| `LoginService.kt:231` one generated method of data class `CredentialCheck.Verified` | None | Non-material |

## Unable to test

- Execution of `PushServiceSubscribeFailureTests` and its ktlint check: Gradle is blocked for the test-writer. Follow-up: the parent runs `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.push.PushServiceSubscribeFailureTests' ktlintCheck` and, if a final coverage number is wanted, `./gradlew test jacocoTestReport` again.
- A real non-FK integrity violation from the upsert cannot be provoked through the database (all columns are filled and the endpoint conflict is handled by the upsert), so that branch is covered with a mocked repository only.
- The migration's `delete from push_subscription` for pre-existing rows runs before any test can insert data; covered only implicitly by Flyway on an empty test database.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The CR-1/TW-1 fix is covered end to end by the fixer's PushWebTests test (passed in the parent's run on ed147c713); three unit tests were added for the remaining branch of the fix (other integrity errors are rethrown, not mapped to 401). Those three tests have not been run yet: the parent must run them before sign-off.
