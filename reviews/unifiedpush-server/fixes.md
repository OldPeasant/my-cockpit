# Fix Report

Date: 2026-10-26

Task: unifiedpush-server: Server side of UnifiedPush (registrations + push sending)

Scope: current task. The two P3 findings CR-1 and CR-2 in `reviews/unifiedpush-server/review.md`. Code base: HEAD 80ca88fc8 (base bacf27da8).

## Fixed findings

### CR-1 — Concurrent PUTs for the same instance answer 500 or 409

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/push/infra/UnifiedPushRegistrationRepository.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/core/push/domain/UnifiedPushService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/core/push/domain/UnifiedPushRegistration.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/push/UnifiedPushServiceTests.kt`

Problem:
`register` read the row and then inserted or updated it. Two overlapping registrations of a new instance broke the unique constraint, which surfaced as a 500. Two overlapping updates of an existing row failed the `@Version` check, which surfaced as a 409.

Fix:
`register` now runs one native upsert, `UnifiedPushRegistrationRepository.upsert`: `insert ... on conflict (instance_id) do update set endpoint, p256dh, auth, updated_at, version = version + 1`. This follows the existing `mail_body` upsert in `MailRepositories.kt`. The id of a new row is a fresh UUIDv7. Timestamps come from the injected `Clock`. The entity method `UnifiedPushRegistration.update` had no callers left, so I removed it. The service now logs "registered" (instance id only) on every registration, not just the first. Endpoint validation, key validation and the 400 mapping did not change.

Regression test: `concurrent registrations of the same instance all succeed and leave one row`. It runs 5 rounds. In each round, 8 threads start together at a barrier and register the same new instance, then each registers it again. The test checks that no call fails, that each instance ends up with exactly one row, and that the second-wave endpoint is stored. It failed against the old code with `DataIntegrityViolationException` (checked by restoring the HEAD production files temporarily) and passes with the fix.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.core.push.UnifiedPushServiceTests'` against the old production code — Fail (expected: the new test fails with DataIntegrityViolationException)
- `./gradlew test --tests 'ch.sonensei.cockpit.core.push.*'` with the fix — Pass (49 tests, 0 failures)

### CR-2 — Dispatcher failure-path assertions ran before the async push finished (part already fixed)

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/core/push/PushDispatcherTests.kt`

Problem:
The review was written against the earlier revision e39350a8d. There, the `down` (unreachable) and `reset` assertions ran before the dispatcher thread had handled those endpoints. Commit 80ca88fc8 already fixed the main problem: `awaitFailureLogged` waits for the dispatcher's WARN log, which is its last step for an endpoint, before the registrations are asserted. The suggested "a later push still goes out" check was still missing.

Fix:
The unreachable-endpoint test now also registers a reachable `/ok` endpoint after the failure has been logged. It publishes another push and waits for it to arrive, which shows the dispatcher still works after a connection failure. `ktlintFormat` also added one blank line before the local function in `awaitFailureLogged`, to fix an existing lint error. `PushDispatcherFailureTests.kt` covers the mocked-client failure paths and was not duplicated.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.core.push.*'` — Pass (PushDispatcherTests: 5 tests, 0 failures)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat && ./gradlew test --tests 'ch.sonensei.cockpit.core.push.*'` — Pass (8 suites, 49 tests, 0 failures)

Full test suite:
- `cd server && ./gradlew check` (ktlint, detekt, all server tests) — Pass (BUILD SUCCESSFUL)

Lint/type-check/build:
- Covered by `./gradlew check` above — Pass

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("all checks passed", exit 0)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
