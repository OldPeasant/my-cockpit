# Code Review

Date: 2026-10-26

Task: push-session-binding - Bind Web Push subscriptions to the login session

Scope: whole task range `git diff 98053b81c..091428200` (excluding `tasks/` and `reviews/`), third (final) round on
the final code. Rounds one and two reviewed 99ff0179c and ed147c713; this round focuses on the only code commit added
since then, 091428200 (test file `PushServiceSubscribeFailureTests.kt`).

Requirements:
- `tasks/push-session-binding.md` (acceptance criteria, notes, assumptions)
- `reviews/web-push/review.md` CR-1 (origin of the task)
- `reviews/push-session-binding/fixes.md` (fix of first-round CR-1)
- SPEC.md §4.1 (sessions), §4.2 (API conventions, Problem Details), §4.6 (Web Push), §14 (backup)
- docs/ARCHITECTURE.md (module boundaries, migrations, conventions)
- AGENTS.md project notes (Flyway naming, Problem Details, generated `api/openapi.yaml`, logging)

Git range:
- Branch: main
- HEAD: 091428200b36a6101ccd3a1975bf88d95f70bf74
- Base/start: 98053b81c713e30fe85c7b431960a6d142f23f3e
- Commits reviewed: 4 (4676a2b27 bind Web Push subscriptions to the login session; 69adbf954 session lifecycle
  tests; 78a803dd1 401 when the session ends during subscribe (CR-1); 091428200 unit tests for the subscribe FK
  mapping, review reports). The hashes differ from earlier rounds after a rebase; the code content is the same.
- Uncommitted changes: none in code

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## What was checked

### Production code unchanged since round two

`git diff --stat ed147c713..091428200 -- server/src/main api web android` is empty. Between ed147c713 and 091428200
only `PushServiceSubscribeFailureTests.kt`, `reviews/push-session-binding/*` and `tasks/*.md` changed. So everything
checked in rounds one and two still holds for the final revision: migration with `on delete cascade`, native SQL
upsert, principal session id, sending only to live sessions, the 23503-to-401 Problem Details mapping, OpenAPI
contract and logging.

### 091428200 (test-writer: `PushServiceSubscribeFailureTests`)

- Plain MockK unit test of `PushService.subscribe`. The constructor arguments match the current `PushService`
  signature (repository, VAPID keys, sender, properties, JSON mapper, clock). The relaxed mocks are not used on the
  path under test: before the upsert, `subscribe` only validates the endpoint and decodes keys, without touching
  those collaborators.
- Inputs pass the validation that runs before the upsert: an https endpoint on a fictitious `example.test` host, and
  real keys from `PushTestBrowser` (uncompressed P-256 key, 16-byte secret). So the tests really reach the `try` block
  and don't fail early with `InvalidRequestException`.
- Test 1: the `SQLException` with SQLSTATE 23503 sits two causes deep, like Hibernate wraps it. This exercises the
  cause-chain walk in `isForeignKeyViolation`. It asserts status 401, the Problem Details `status` and `detail`, and
  that `findByEndpoint` is never called, which matches the code comment "No further query: the failed statement
  aborted the transaction".
- Test 2: SQLSTATE 23505 (unique violation) is rethrown as the same instance, so a non-FK error is not disguised as
  a logout.
- Test 3: a `DataIntegrityViolationException` without any `SQLException` cause is rethrown as the same instance.
  This covers the "no SQL state" branch.
- The tests assert behavior (exception type, status, body, identity of the rethrown error, no follow-up query), not
  internal details. Data is fictitious and nothing is logged. Style matches the surrounding tests (Kotest matchers,
  backtick names, tabs). The parent reported ktlintCheck passing and 3 tests, 0 failures on 091428200.
- These tests add to the integration test in `PushWebTests` (real Postgres FK violation on the insert and update
  paths), they don't replace it. Together they cover the mapping at both the unit and the HTTP level.

### Acceptance criteria

1. Migration with `on delete cascade`, session id in `CockpitPrincipal`, PUT stores it, re-subscribe moves it:
   implemented and covered (`PushSessionBindingTests`, `PushSessionLifecycleTests`, `PushWebTests`).
2. No pushes to subscriptions of expired sessions, which get deleted: implemented in `PushService.send` and by the
   DB cascade on session cleanup. Covered, including the exact expiry boundary.
3. `./gradlew test --tests 'ch.sonensei.cockpit.core.push.*'` with tests for logout, idle expiry and revoked session:
   the tests exist. On ed147c713 the parent reported 127 tests, 0 failures, and `./gradlew check jacocoTestReport`
   BUILD SUCCESSFUL. On 091428200 the parent reported the new class passing (3 tests) and ktlintCheck OK. I did not
   re-run these here because this reviewer can't run Gradle.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Dispositions of earlier findings

- [x] **CR-1 (first round) | A subscribe that races with the end of its session answers 500 and logs an ERROR**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/push/domain/PushService.kt:84`
    - Disposition: fixed in 78a803dd1 (ed147c713 before the rebase) and verified in round two. FK violation
      SQLSTATE 23503 is mapped to a 401 Problem Details, other violations are rethrown, the transaction rolls back,
      and the 401 is documented in OpenAPI. Now also covered by unit tests in 091428200.

## Review conclusion

No material issues found.
