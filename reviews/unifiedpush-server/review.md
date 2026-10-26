# Code Review

Date: 2026-10-26

Task: unifiedpush-server - Server side of UnifiedPush (registrations + push sending)

Scope: `git diff 9b1203a906cef42fed2a7a43ff40690c56a2ace7...8b862e12423c9eec1528c575fb6992803eb58143` (the final code: `core/push/**`, `mail/sync/NewMailAnnouncer.kt`, migration `db/migration/core/V202610261400__core_push_unifiedpush.sql`, tests `core/push/**` including the new `UnifiedPushUpsertTests.kt`, `NewMailAnnouncerTests.kt`, `api/openapi.yaml`, generated web client). Committed changes only. The working tree was clean.

Requirements:
- `tasks/unifiedpush-server.md` acceptance criteria 1-3 and the four recorded assumptions in its Notes
- `SPEC.md` (§4.2 conventions, Android push/UnifiedPush mentions)
- `docs/ARCHITECTURE.md`, `docs/QUALITY.md`
- `AGENTS.md` project notes (module boundaries, migrations, OpenAPI regeneration, logging rules)
- Previous review and fixes for this task (`reviews/unifiedpush-server/review.md` earlier revision, `fixes.md`), `tests.md`

Git range:
- Branch: main
- HEAD: 8b862e12423c9eec1528c575fb6992803eb58143
- Base/start: 9b1203a906cef42fed2a7a43ff40690c56a2ace7
- Commits reviewed: 7 (6c9e1cf74, 6f2061b0a, e3dbcebc9, d3170a767, 24e382920, merge c3fbd5f3f, 8b862e124)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Commands run

- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.push.*' --tests 'ch.sonensei.cockpit.mail.sync.NewMailAnnouncerTests'` -> BUILD SUCCESSFUL; JUnit XML: 52 tests in `core.push.*` (UnifiedPushServiceTests 12, UnifiedPushWebEdgeCaseTests 8, PushDispatcherFailureTests 7, PushDispatcherTests 5, PushEndToEndTests 5, UnifiedPushWebTests 5, WebPushEncryptionEdgeCaseTests 4, UnifiedPushUpsertTests 3, WebPushEncryptionTests 3) plus NewMailAnnouncerTests 11; 0 failures, 0 errors, 0 skipped.
- Read-only inspection: `git log`, `git diff`, full reads of every changed production file, the migration, the tests, `BaseEntity`, `ReminderService.fireDue`, `DeviceTokenService.revoke`, the OpenAPI diff and the generated model.

## Acceptance criteria check

1. PUT/DELETE `/api/v1/push/unifiedpush/{instanceId}`: implemented in `UnifiedPushController` (204, idempotent, no If-Match per assumption; instance id pattern checked; endpoint `@NotBlank`/`@Size(2048)` plus absolute http(s) URL with host and without user info; keys both-or-none, base64url, size and on-curve checks; Problem Details 400 with field names; 401 anonymous, CSRF handled globally). Flyway migration in `db/migration/core` with unique instance id and a both-keys-or-none check constraint; backup exclusion recorded as an assumption and documented in the migration. `api/openapi.yaml` and the web client (`register-unified-push.ts`, `unregister-unified-push.ts`, model) are regenerated and consistent with the controller. Upsert is a single native `insert ... on conflict` statement that maintains id (UUIDv7), `created_at`, `updated_at` (injected clock) and `version`, so the previous CR-1 race is fixed; `UnifiedPushUpsertTests` covers the row metadata and that `clearAutomatically` avoids stale entities in the caller's persistence context.
2. Push sending: `PushDispatcher` listens to `ReminderFiredEvent` and `PushMessage` after commit (`@TransactionalEventListener(fallbackExecution = true)`), sends on its own virtual-thread executor, payload `{"kind","id"}` only, RFC 8291 `aes128gcm` when keys are stored (verified against the RFC 8291 example vector), TTL 1 day, Urgency high/normal, no redirects, 404/410 remove the registration only while its endpoint is unchanged (`deleteIfEndpoint`), all other failures logged without the endpoint URL and never propagated to the publisher. `NewMailAnnouncer` publishes `PushMessage(MAIL, newest unread id)` exactly when it publishes the SSE notification, matching the assumption.
3. `./gradlew test --tests 'ch.sonensei.cockpit.core.push.*'` passes (re-run above), with WireMock as the distributor ("MockWebServer or similar").

The previous findings are resolved in the current code: CR-1 (concurrent PUT -> single upsert statement, `UnifiedPushServiceTests` "concurrent registrations of the same instance all succeed and leave one row") and CR-2 (failure-path test waits for the dispatcher's WARN log before asserting, `awaitFailureLogged`).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Registrations outlive a revoked device; the decision is not recorded**
    - File: `server/src/main/resources/db/migration/core/V202610261400__core_push_unifiedpush.sql:5`
    - Category: Security
    - Problem: `push_unifiedpush_registration` has no link to the `auth_device` (or session) that registered it, and `DeviceTokenService.revoke` (`server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/DeviceTokenService.kt:104`) does not touch it. A phone revoked because it was lost keeps receiving a push (kind, item UUID, timing) for every reminder and every new mail until its distributor answers 404/410, and the owner has no API to list the registrations and remove it.
    - Why it matters: The payload has no item contents, so the leak is small (metadata only), but "revoke this device" does not fully cut it off, and nothing in the task notes records this as an intended trade-off.
    - Suggested fix: No code change required for this task; record it with `tasks.py assume` (or add a follow-up task) as a known limitation. A later fix could store the registering device id and delete its registrations on revoke, or offer list/delete of registrations to the owner.

## Review conclusion

No material issues found.
