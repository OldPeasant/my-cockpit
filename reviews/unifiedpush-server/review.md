# Code Review

Date: 2026-10-26

Task: unifiedpush-server: Server side of UnifiedPush (registrations + push sending)

Scope: committed changes 75896adc511956c35273517771fbcf5a508fdda3..e39350a8d (production code under `server/src/main`, the migration, `api/openapi.yaml`, the generated web client, and the committed tests). The untracked test files in `server/src/test/kotlin/ch/sonensei/cockpit/core/push/` belong to the parallel test-writer run and were not reviewed.

Requirements:
- `tasks/unifiedpush-server.md`: acceptance criteria and the four recorded assumptions (no backup, payload shape, PUT/DELETE semantics and validation, after-commit dispatch, TTL/Urgency, no VAPID)
- `SPEC.md` (single user §1, reminders §4.6, Problem Details and API conventions)
- `docs/ARCHITECTURE.md`, `AGENTS.md` project notes (migration path and naming, logging rules, module boundaries, generated files)
- Instructions from the parent agent (RFC 8291, 404/410 removal, failure isolation, after-commit dispatch, payload, user scoping, endpoint validation and SSRF, backup decision, secret logging, lint)

Git range:
- Branch: main
- HEAD: e39350a8d
- Base/start: 75896adc511956c35273517771fbcf5a508fdda3
- Commits reviewed: 2 (ec85e6074 feat(push), e39350a8d test(push) + OpenAPI/web client regeneration)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Concurrent PUTs for the same instance answer 500 or 409, although the endpoint is documented as idempotent**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/push/domain/UnifiedPushService.kt:39`
    - Category: Correctness
    - Problem: `register` reads first (`findByInstanceId`) and then inserts or updates. When two PUTs for a new `instanceId` overlap, both insert, and the second one hits the unique constraint `push_unifiedpush_registration_instance`. The resulting `DataIntegrityViolationException` reaches the catch-all in `ProblemDetailsAdvice.handleUnexpected` (`core/web/ProblemDetailsAdvice.kt:58`): 500, plus an ERROR log. Two overlapping PUTs for an existing row fail the `@Version` check instead, and `OptimisticLockingFailureException` becomes 409, even though the endpoint takes no If-Match (recorded assumption).
    - Why it matters: The UnifiedPush connector can call `onNewEndpoint` more than once in a row, for example after a distributor restart. One of the requests then fails with a 500, which contradicts the "idempotent, 204" contract in the OpenAPI description. The retry works, so the impact is small.
    - Suggested fix: Use an upsert (`insert ... on conflict (instance_id) do update set endpoint = ..., p256dh = ..., auth = ..., updated_at = ..., version = version + 1`). Or catch `DataIntegrityViolationException` / `OptimisticLockingFailureException` in `register` and retry once as an update.

- [ ] **CR-2 | The test "an unreachable push server does not break the publisher" passes without the failure path ever running**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/core/push/PushDispatcherTests.kt:147`
    - Category: Tests
    - Problem: Sending is asynchronous, so `events.publishEvent(...)` always returns at once. The assertion `repository.findByInstanceId("down")` then runs before the virtual thread tries `localhost:1`. The test would pass even if a connection failure deleted the registration or ended the dispatcher thread. For the same reason, the `reset` registration in the 404/410 test (line 127) is not reliably checked after its request completed.
    - Why it matters: The acceptance criterion says failures never break the event flow, and a connection failure must not remove a registration. Neither has a test that can fail.
    - Suggested fix: Wait until the dispatcher has handled the unreachable endpoint before asserting. For example, register it together with a reachable `/ok` endpoint, wait for the `/ok` request, and wait a short time for the failure path. Or inject a client whose call is observable. Then assert that the registration is still there and a later push still goes out.

## Checked without findings

- RFC 8291 / RFC 8188 `aes128gcm`: The PRK_key, IKM (`"WebPush: info" 0x00 ua_public as_public 0x01`), PRK, CEK, nonce, the 0x02 last-record delimiter and the header (salt, rs = 4096, idlen = 65, keyid) match the RFC. The encryption is checked against the RFC 8291 Appendix A vector. Every message gets a fresh salt and ephemeral key. Public keys off the curve are rejected and map to a 400 on `p256dh`.
- 404/410 handling: `removeGone` deletes only while the stored endpoint is still the one that failed (`deleteIfEndpoint`), so a newer registration survives. Other statuses and exceptions are logged and the registration is kept.
- Failure isolation and after-commit dispatch: Both listeners are `@TransactionalEventListener` (AFTER_COMMIT, `fallbackExecution = true`) and hand off to a virtual-thread executor. Errors while reading registrations, encrypting or sending are caught per registration. The reminder scheduler and mail sync never wait for or see a push failure. A test covers the rollback case.
- Payload: only `{"kind","id"}`, from a typed enum and UUID (no injection). Mail sends the newest unread new message's id, only when the SSE new-mail notification goes out (Inbox, `notifyNewMail`), as the assumption says.
- User scoping: SPEC.md §1 defines a single owner account. Both endpoints require authentication (tests cover the 401 case), so no per-user column is needed.
- Input validation and SSRF: `instanceId` is checked against `^[A-Za-z0-9._-]{1,128}$`. The endpoint must be an absolute http(s) URL with a host and no userinfo, at most 2048 characters. Keys must be valid base64url of the right size, set together. Plain http and LAN hosts are allowed on purpose (recorded assumption: LAN ntfy). The remaining SSRF exposure is limited:
    - only the authenticated single owner can set the target;
    - the request body is a fixed, small payload;
    - the response is never returned to the caller;
    - redirects are not followed;
    - connect and read timeouts are set.
  No finding.
- Secret logging: Endpoint URLs and keys are never logged. Logs carry only the instance id, the push kind, the HTTP status or the exception's simple class name. `UnifiedPushClient` replaces exception messages, which may contain the URL. `WebPushKeys.toString()` hides the keys.
- Migration: `db/migration/core/V202610261400__core_push_unifiedpush.sql` follows the path and naming convention and the BaseEntity columns (id, created_at, updated_at, version). It adds a unique constraint on instance_id and a both-or-none check on the keys. The comment documents the no-backup decision.
- Backup: Restore truncates every table (`RestoreService`), so registrations are dropped and the apps register again. This matches the recorded assumption. No round-trip test lists the tables, so nothing else needs updating.
- OpenAPI and client: `api/openapi.yaml` and `web/src/app/api/generated/` match the controller (204/400/401, path pattern, request schema with required `endpoint`).
- Lint: I did not run ktlint or detekt. This review was limited to read-only inspection. The parent's `.taskcheck` run has to provide that evidence.

## Review conclusion

No material issues found. CR-1 and CR-2 are low-priority improvements. The parent worker decides whether to fix them in this task.
