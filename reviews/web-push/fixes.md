# Fix Report

Date: 2026-10-26

Task: web-push - Web Push notifications

Scope: the approved findings CR-1, CR-3, CR-4 and CR-5 from `reviews/web-push/review.md`. CR-2 was excluded by the parent worker, which accepted it as an assumption. Fix commit: d80433b8.

## Fixed findings

### CR-1 (P2): Logout leaves the browser push-subscribed

Files changed:
- `web/src/app/core/auth/auth.service.ts`
- `web/src/app/core/auth/auth.service.spec.ts`

Problem:
`AuthService.logout()` ended the session but kept the browser's Web Push subscription. Pushed mail senders, subjects and reminder titles kept appearing on a logged-out device.

Fix:
`logout()` now calls `PushSubscriptionService.unsubscribe()` before it sends the logout request. This removes the subscription from the server while the session is still valid, then from the browser. The call is best effort:
- a rejection is caught;
- a synchronous throw is caught;
- the wait is capped at `LOGOUT_PUSH_TIMEOUT_MS` (3 s);
- logout then continues as before.

On a browser without push support, `unsubscribe()` returns at once. The service is imported straight from `../notifications/push-subscription.service` and not through the `notifications` barrel. The barrel would create a module cycle through `ReminderNotificationsService` -> `ChangeEventsService` -> `AuthService`. `PushSubscriptionService` does not depend on `AuthService`, so there is no DI cycle. The change to `auth.service.ts` is one injected field, one line in `logout()` and a small private helper.

Tests added in `auth.service.spec.ts`:
- unsubscribe runs before the logout request;
- logout succeeds when unsubscribe rejects, with no toast;
- logout succeeds when unsubscribe throws synchronously;
- logout waits no longer than the timeout for an unsubscribe that hangs (fake timers).

Validation:
- `npx ng test --watch=false --include src/app/core/auth/auth.service.spec.ts` — Pass (29 tests)
- `cd web && npm run check` — Pass

### CR-3 (P3): Concurrent PUT of the same endpoint yields a 500

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/push/infra/PushRepositories.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/core/push/domain/PushService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/core/push/domain/PushEntities.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/push/PushWebTests.kt`

Problem:
`subscribe` looked up the endpoint and then inserted it. Two subscriptions arriving at once could both insert, and the second hit the `push_subscription_endpoint` unique constraint (500).

Fix:
- New native `PushSubscriptionRepository.upsert`: `insert ... on conflict (endpoint) do update`.
- The insert fills every BaseEntity column: a UUIDv7 id, `created_at`/`updated_at` from the injected `Clock`, and `version` 0.
- The update replaces the keys and bumps `version` and `updated_at` only when the keys actually change (`where ... is distinct from`), the same result as the earlier JPA dirty check.
- `PushService.subscribe` runs the upsert and then re-reads the row. The method keeps its signature and return value.
- `PushSubscription.updateKeys` is no longer used and was removed.

The existing test "the same browser subscribing twice at the same time ends with one subscription" now passes. A new test checks that id, created time and version stay correct across a repeated subscribe and a key change.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.push.*'` — Pass

### CR-4 (P3): `COCKPIT_PUSH_SUBJECT` is neither documented nor forwarded by the deployment

Files changed:
- `deploy/docker-compose.yml`
- `docs/ARCHITECTURE.md`
- `deploy/README.md`

Problem:
The compose file lists the server's environment explicitly and did not forward `COCKPIT_PUSH_SUBJECT`, so setting it in `deploy/.env` had no effect. It was not documented either.

Fix:
- Added `COCKPIT_PUSH_SUBJECT: ${COCKPIT_PUSH_SUBJECT:-https://${COCKPIT_DOMAIN}}` to the server service. This matches `cockpit.push.subject=${COCKPIT_PUSH_SUBJECT:https://${COCKPIT_DOMAIN:localhost}}` in `application.properties` and the style of `COCKPIT_OAUTH_PUBLICURL`.
- Added one row to the env-var table in `docs/ARCHITECTURE.md`.
- Added one sentence to the "Configuration" section of `deploy/README.md`. The only table there is for passkeys.

Validation:
- Configuration and documentation only. No compose run was done (no Docker stack started here).

### CR-5 (P3): No known-answer test for the hand-written RFC 8291 implementation

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/push/infra/WebPushCrypto.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/push/WebPushCryptoRfc8291Tests.kt` (new)

Problem:
Encryption was checked only by a round-trip against `PushTestBrowser`, which has the same author. Nothing tied it to an external reference.

Fix:
- Added an `internal` overload `WebPushCrypto.encrypt(plaintext, uaPublic, authSecret, salt, ephemeral: KeyPair)`. The public overload now generates the random salt and ephemeral key pair and delegates to it, so production behavior is unchanged.
- New test class using the RFC 8291 Appendix A vector:
  - plaintext "When I grow up, I want to be a watermelon", record size 4096;
  - the RFC's UA and AS key pairs, auth secret and salt.

It checks:
1. Encrypting gives exactly the RFC's encoded message, byte for byte.
2. The RFC's message decrypts to the plaintext with the UA private key, through the independent `PushTestBrowser`. A GCM tag check confirms the vector values are internally consistent.
3. ECDH from both sides gives the RFC's `ecdh_secret`.

All three pass. The production code matches the RFC, and no discrepancy was found.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.push.*'` — Pass

## Not fixed

### CR-2 (P3): No `pushsubscriptionchange` handling in the service worker

Status:
Out of scope

Reason:
The parent worker excluded it from this fix round and recorded it as an accepted assumption in `tasks/web-push.md`.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat ktlintCheck test --tests 'ch.sonensei.cockpit.core.push.*'` — Pass. After the last change, the run without `ktlintFormat` had 52 tests, 0 failures. Per class: PushWebTests 15, PushDeliveryTests 13, WebPushCryptoEdgeCaseTests 9, WebPushCryptoTests 5, PushNotificationRelayTests 5, WebPushCryptoRfc8291Tests 3, PushPropertiesTests 2.
- `npx ng test --watch=false --include src/app/core/auth/auth.service.spec.ts` — Pass (29 tests).

Full test suite:
- Web: `npm --prefix web run check` (api:check, lint, e2e typecheck, all Vitest tests, build) — Pass, exit 0. 182 files, 3819 tests.
- Server: full suite not run, as the parent worker instructed (it takes over 15 min).

Lint/type-check/build:
- Server ktlintCheck — Pass
- Web lint, typecheck and `ng build` (part of `npm run check`) — Pass

`.taskcheck`:
- Not run. The parent worker runs it, and its server part is the full server suite, which was excluded.

Remaining unresolved findings:
0 of the supplied findings (CR-2 excluded by the parent)

## Final status

All validated findings fixed and validation passed.
