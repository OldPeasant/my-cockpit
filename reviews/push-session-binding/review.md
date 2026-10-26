# Code Review

Date: 2026-10-26

Task: push-session-binding - Bind Web Push subscriptions to the login session

Scope: whole task range `git diff 98053b81c..ed147c713` (excluding `tasks/` and `reviews/`), second round on the
final code, with focus on the commits added since the first review (7fe4c21c3, ed147c713) and on the resolution of
CR-1 from the first round (which reviewed 99ff0179c)

Requirements:
- `tasks/push-session-binding.md` (acceptance criteria, notes, assumptions)
- `reviews/web-push/review.md` CR-1 (origin of the task)
- `reviews/push-session-binding/fixes.md` (fix of CR-1)
- SPEC.md §4.1 (sessions), §4.2 (API conventions, Problem Details), §4.6 (Web Push), §14 (backup)
- docs/ARCHITECTURE.md (module boundaries, migrations, conventions)
- AGENTS.md project notes (Flyway naming, Problem Details, generated `api/openapi.yaml`, logging)

Git range:
- Branch: main
- HEAD: ed147c713ed3c1c1f4ea219f052e5cd6b70ca05c
- Base/start: 98053b81c713e30fe85c7b431960a6d142f23f3e
- Commits reviewed: 3 (99ff0179c bind Web Push subscriptions to the login session; 7fe4c21c3 session lifecycle
  tests; ed147c713 401 when the session ends during subscribe (CR-1))
- Uncommitted changes: none in code (only the untracked review reports under `reviews/push-session-binding/`)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## What was checked

First-round checks (migration, native SQL upsert and expiry complements, principal session id, encryption switch to
`WebPushEncryption`, concurrency, OpenAPI, logging, `PushSessionBindingTests`) still hold on ed147c713; the code they
cover is unchanged apart from the CR-1 fix below.

### ed147c713 (fix of first-round CR-1)

- `PushService.subscribe` wraps only the upsert in `try`; a `DataIntegrityViolationException` is turned into
  `ErrorResponseException(401)` with a Problem Details body ("The login session has ended") only when its cause chain
  contains an `SQLException` with SQLSTATE `23503`; any other integrity violation is rethrown unchanged. The only
  foreign key on `push_subscription` is `session_id` (checked all migrations), so 23503 there can only mean the
  session no longer exists.
- Transaction: the upsert is a `@Modifying` native query executed immediately (`flushAutomatically`), so the
  violation surfaces inside the `try`, not at commit. No further statement runs on the aborted transaction; the
  runtime `ErrorResponseException` leaves the `@Transactional` method, so it rolls back and nothing is stored.
  `PushController.subscribe` has no surrounding transaction.
- Status and format: 401 matches how the app treats an unknown or expired cookie (`HttpStatusEntryPoint(401)` in
  `SecurityConfiguration`), and `ErrorResponseException` is the same mechanism the controller already uses for its
  403, so the response is `application/problem+json`. The app's ERROR log "Unhandled exception in REST request" no
  longer occurs; the fix logs at DEBUG with the session id only (no token, keys or endpoint). The parent reported that
  Hibernate logs the failed statement at WARN only, consistent with the other `DataIntegrityViolationException` catch
  sites in the codebase.
- Contract: `PushController` documents the 401 ("Not logged in (no body), or the login session ended during the
  request (Problem Details)"), which is accurate for both cases; `api/openapi.yaml` contains exactly that response and
  the parent reported `npm run api:gen` produces no change, so the generated web client is in sync. The web client
  ignores push subscription errors, so no client change is needed.
- Test: `PushWebTests` "subscribing with a session that ended during the request gets 401 and stores nothing" covers
  both upsert paths: the insert path (no row, 401 Problem Details with `status` 401) and the update path (an existing
  row of the live owner session is not moved to the ended session and keeps its keys).

First-round CR-1 status: resolved.

### 7fe4c21c3 (test-writer: `PushSessionLifecycleTests`)

- Real password+TOTP logins, passkey login and a device token against Postgres, WireMock as the push service, a
  `MutableClock`. Covers: password login and cookie authentication carry the session id; two logins get different
  ids; a device token principal has no session and gets the 403 Problem Details; a passkey session subscribes and only
  its own logout ends the subscription; a remembered session keeps pushes past 12 h and loses them exactly at the
  30-day lifetime boundary (no request sent, row deleted); a new login's expired-session cleanup cascades to the
  subscription; an expired cookie cannot subscribe (401); version and `updatedAt` stay unchanged on an identical
  re-subscribe and bump once on a session move; all subscriptions of one session end with it while others stay; a
  sliding expiry keeps pushes past the first expiry.
- Tests assert observable behavior (HTTP status, stored rows, requests received by WireMock), use fictitious data,
  and clean up `app_user` and subscriptions after each test so other classes are not affected.
- Test-only helper `PushTestSessions` writes `auth_session` rows directly for service-level tests; it is used only in
  tests and inserts a fictitious account when none exists.

### Acceptance criteria

1. Migration with `on delete cascade`, session id in `CockpitPrincipal`, PUT stores it, re-subscribe moves it:
   implemented and covered (`PushSessionBindingTests`, `PushSessionLifecycleTests`, `PushWebTests`).
2. No pushes to subscriptions of expired sessions, which are deleted: implemented in `PushService.send` and by the
   DB cascade on session cleanup; covered including the exact expiry boundary.
3. `./gradlew test --tests 'ch.sonensei.cockpit.core.push.*'` with tests for logout, idle expiry and revoked session:
   the tests exist; the parent reported 127 tests, 0 failures (together with `*OpenApiContractTest`) and
   `./gradlew check jacocoTestReport` BUILD SUCCESSFUL with 5127 tests, 0 failures, ktlint OK on ed147c713. Not
   re-run here: Gradle is not available to this reviewer.

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
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/push/domain/PushService.kt:75`
    - Disposition: fixed in ed147c713 and verified in this round (FK violation SQLSTATE 23503 mapped to a 401
      Problem Details, other violations rethrown, transaction rolled back, documented in OpenAPI, tested for the
      insert and the update path).

## Review conclusion

No material issues found.
