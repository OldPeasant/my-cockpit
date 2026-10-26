# Code Review

Date: 2026-10-26

Task: web-push - Web Push notifications

Scope: `git diff 1b5761c0..83bbc19b` (committed task changes only; uncommitted test files in the working tree from the parallel test-writer were not reviewed)

Requirements:
- tasks/web-push.md (acceptance criteria and recorded assumptions)
- SPEC.md §4.6 (reminders and notifications, optional Web Push), §4.2 API conventions, §14 backup
- docs/ARCHITECTURE.md, AGENTS.md project notes (module boundaries, migrations, logging, generated files)
- RFC 8291 / RFC 8188 (aes128gcm), RFC 8292 (VAPID), RFC 8030 (push protocol)

Git range:
- Branch: main
- HEAD: 83bbc19b0d00499422c356760746ecc45a4c03c5
- Base/start: 1b5761c03040634cb6e5325c6b914321ea514ca1
- Commits reviewed: 2 (cdd506ce server push module, 83bbc19b web service worker and subscription flow)

Summary: 0 P0, 0 P1, 1 P2, 4 P3

## What was checked and found correct

- RFC 8291 key derivation in `WebPushCrypto.encrypt`: PRK = HMAC(auth, ecdh), IKM = HKDF(auth, ecdh, "WebPush: info\0" | ua_public | as_public, 32), CEK/nonce from "Content-Encoding: aes128gcm\0" / "Content-Encoding: nonce\0" with a fresh 16-byte salt, single record with the 0x02 delimiter, header `salt | rs | idlen | keyid`. Matches the RFC. The test decryptor (`PushTestBrowser`) is written independently of the server helpers.
- RFC 8292: ES256 with `SHA256withECDSAinP1363Format` (raw r|s as JWS requires), `aud` = endpoint origin, `exp` 12 h (< 24 h), `vapid t=..., k=...` header. Claims are JSON-escaped.
- Received P256 keys are checked for length, uncompressed form and curve membership; auth must be 16 bytes.
- VAPID key pair generated on `ApplicationReadyEvent`, private key stored as `EncryptedSecret` (master key), singleton enforced by a unique + check constraint, concurrent first-start handled via REQUIRES_NEW + re-read.
- Subscriptions API: authenticated (falls under `/api/**`, test for 401 present), Bean Validation + `InvalidRequestException` give Problem Details 400, https-only endpoint without userinfo, length limit, unsubscribe endpoint in the body (not in access logs). OpenAPI and generated client updated.
- 404/410 from the push service delete the subscription (`deleteByEndpoint` is `@Transactional @Modifying`); other failures are logged by subscription id only; endpoint URLs, keys and message contents are not logged. Redirects disabled on the HTTP client.
- Relay acts after commit (`@TransactionalEventListener(fallbackExecution = true)`) on its own thread; core does not depend on the mail module (event name string instead of `NewMailAnnouncer.EVENT_NAME`).
- Backup/restore: restore truncates every table (`RestoreService` line 254 ff.), so both tables are emptied; keys regenerate on next use and the web app replaces a subscription whose `applicationServerKey` differs. Matches the recorded assumption.
- Migration `db/migration/core/V202610261400__core_push.sql` follows the naming rule and BaseEntity columns.
- Service worker: payload shapes match `ReminderResponse` (`id`, `item.title/available/deepLink`) and `NewMailNotification` (`folderId`, `count`, `messages[].id/from/subject`); route `mail/messages/:id` exists; click URLs are confined to the worker scope; CSP `worker-src 'self'` allows the worker; icon exists in `web/public`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Logout (or session expiry) leaves the browser subscribed, so mail senders/subjects and reminder titles keep appearing on a logged-out device**
    - File: `web/src/app/core/notifications/provide-reminder-notifications.ts:19` (effect only runs inside the authenticated shell); `web/src/app/core/auth/auth.service.ts:88` (`logout()` does not touch push)
    - Category: Security
    - Problem: The subscription is only removed when the reminder switch is turned off or permission is lost while the shell is loaded. `AuthService.logout()` ends the session but neither calls `PushSubscriptionService.unsubscribe()` nor does the server tie subscriptions to a session. Before this change, notifications stopped at logout because they came only over the authenticated SSE stream; now the server keeps pushing `mail` (sender + subject, `push-sw.js:71-78`) and `reminder` (item title) notifications to that browser indefinitely, including on the lock screen.
    - Why it matters: On a shared or borrowed computer, logging out no longer stops personal data (email senders/subjects, task titles) from being displayed. This is a regression of the logout boundary introduced by this task.
    - Suggested fix: Unsubscribe as part of logout (call `PushSubscriptionService.unsubscribe()` before the logout request, best effort), and/or have the server drop push subscriptions when the session that created them ends (e.g. store the session id with the subscription, or delete all subscriptions on explicit logout). Add a test for the chosen behaviour. If the current behaviour is intended, record it as an assumption.

## P3 Low

- [ ] **CR-2 | No `pushsubscriptionchange` handling in the service worker**
    - File: `web/public/push-sw.js:26-30`
    - Category: Correctness
    - Problem: When the push service rotates or expires a subscription (Firefox does this), the server's endpoint returns 410 and is deleted, but the new subscription is only registered on the next app start (`PushSubscriptionService.sync`). Until then no pushes arrive, which defeats "notifications without an open tab" for a user who rarely opens the app.
    - Why it matters: Silent loss of push delivery with no feedback.
    - Suggested fix: Handle `pushsubscriptionchange` in the worker: re-subscribe with the old `applicationServerKey` and PUT the new subscription to `/api/v1/push/subscriptions` (with credentials and the CSRF header the API expects), or at least document the limitation as an assumption.

- [ ] **CR-3 | Concurrent PUT of the same endpoint yields a 500**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/push/domain/PushService.kt:59-64`
    - Category: Correctness
    - Problem: `findByEndpoint` then `save` is a check-then-insert. Two tabs starting at the same time (each runs `sync()` on startup) can both insert; the second hits the `push_subscription_endpoint` unique constraint and the `DataIntegrityViolationException` is not mapped by `ProblemDetailsAdvice` (only optimistic locking is), so it surfaces as a 500. The web client swallows it silently, so the impact is a logged server error rather than a broken flow.
    - Why it matters: Spurious 500s and error logs for a documented idempotent operation.
    - Suggested fix: Use an upsert (`insert ... on conflict (endpoint) do update`) or catch `DataIntegrityViolationException` and retry the update path.

- [ ] **CR-4 | `COCKPIT_PUSH_SUBJECT` is neither documented nor forwarded by the deployment**
    - File: `server/src/main/resources/application.properties:62`; `deploy/docker-compose.yml` (server `environment`), `docs/ARCHITECTURE.md` env table
    - Category: Integration
    - Problem: The recorded assumption names `COCKPIT_PUSH_SUBJECT` as the VAPID subject setting, but the compose file passes an explicit environment list that does not include it, and the env-var tables in `docs/ARCHITECTURE.md` / `deploy/README.md` do not mention it. Setting it in `deploy/.env` has no effect. The default (`https://<COCKPIT_DOMAIN>`) works, so this is a configuration/documentation gap only.
    - Why it matters: An operator cannot change the VAPID contact (e.g. to `mailto:`) as the docs/assumption suggest.
    - Suggested fix: Add `COCKPIT_PUSH_SUBJECT: ${COCKPIT_PUSH_SUBJECT:-https://${COCKPIT_DOMAIN}}` to the server service and one row to the env table (or create a follow-up task if those files are outside this task's touches).

- [ ] **CR-5 | No known-answer test for the hand-written RFC 8291 implementation**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/core/push/WebPushCryptoTests.kt:16`
    - Category: Tests
    - Problem: Encryption is verified only by round-trip against `PushTestBrowser`, which was written by the same author from the same reading of the RFC. A shared misunderstanding (e.g. info string, key order) would pass both sides while real browsers reject the message. The review found the derivation correct, but nothing pins it to an external reference.
    - Why it matters: The assumption explicitly replaces a maintained library with ~150 lines of custom crypto; a regression there fails silently in production (push services accept the body, browsers drop it).
    - Suggested fix: Add a test that decrypts/derives with the RFC 8291 Appendix A test vector (fixed UA keys, auth secret, salt and AS key pair), e.g. by exposing an internal `encrypt` overload that accepts the salt and ephemeral key pair.

## Review conclusion

Material issues found; fix before completing the task. CR-1 (pushes continue after logout) should be resolved or explicitly accepted as an assumption by the parent worker; the P3 items are non-blocking improvements.
