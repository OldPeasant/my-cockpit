# Code Review

Date: 2026-10-26

Task: web-push - Web Push notifications

Scope: `git diff c7b117400574a0d13bfd17d594887e9b36241d14..8bc4d528b2347095b8f3ec979e56079720f1e90b`, excluding `tasks/` and `reviews/` (committed changes only). This is a re-review of the final code. The earlier review covered findings CR-1 to CR-5 on an older revision; `reviews/web-push/fixes.md` records how they were fixed.

Requirements:
- tasks/web-push.md (acceptance criteria and recorded assumptions, including CR-2 accepted as an assumption)
- SPEC.md §4.6 (optional Web Push), §4.1 (sessions), §4.2 (API conventions), §14 (backup)
- docs/ARCHITECTURE.md, AGENTS.md project notes (module packages, migrations, logging, generated files, env table)
- RFC 8291 / RFC 8188 (aes128gcm), RFC 8292 (VAPID), RFC 8030 (push protocol)

Git range:
- Branch: main
- HEAD: 8bc4d528b2347095b8f3ec979e56079720f1e90b
- Base/start: c7b117400574a0d13bfd17d594887e9b36241d14
- Commits reviewed: 10
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 1 P2, 0 P3

## Earlier findings re-checked

- CR-1 (logout leaves the browser subscribed): fixed for explicit logout. `AuthService.logout()` (`web/src/app/core/auth/auth.service.ts:95-116`) unsubscribes before the logout request, best effort, with a 3 s cap. The shell effect (`provide-reminder-notifications.ts:25-29`) now does nothing while logged out and subscribes again after a new login in the same tab. Tests: `auth.service.spec.ts`, `push-logout.spec.ts`, `provide-reminder-notifications.spec.ts`. The part of the earlier finding about sessions that end without a logout is still open; see CR-1 below.
- CR-2 (no `pushsubscriptionchange`): accepted as an assumption in the task notes. Not reported again.
- CR-3 (concurrent PUT gives a 500): fixed. `PushSubscriptionRepository.upsert` is a single `insert ... on conflict (endpoint) do update` statement that fills every BaseEntity column and bumps the version only when the keys change. Covered by the concurrent-subscribe test and the id/version test in `PushWebTests`.
- CR-4 (`COCKPIT_PUSH_SUBJECT` not forwarded or documented): fixed. `deploy/docker-compose.yml` forwards it with the same default as `application.properties`, and both `docs/ARCHITECTURE.md` and `deploy/README.md` document it.
- CR-5 (no known-answer test for RFC 8291): fixed. `WebPushCryptoRfc8291Tests` checks the RFC 8291 Appendix A vector byte for byte through the internal `encrypt` overload. The public overload only adds a random salt and ephemeral key pair.

## Changes since the last review, checked

- `VapidKeyService.current()` wraps `GeneralSecurityException` in `IllegalStateException`, so `generateAtStartup()` catches a non-PKCS#8 stored key (it catches `RuntimeException`). A key the master key can't decrypt fails inside `reveal()` or the converter, which is also a `RuntimeException`. Neither case replaces the stored row. The tests at `PushWebTests.kt:313-358` cover both cases and also generation at startup. They restore a usable key pair in `finally`, so other tests are not affected.
- The `auth.loggedIn()` gate is correct. The effect reads `loggedIn` first, so a change of session re-runs it. While logged out it neither subscribes nor unsubscribes, because logout already unsubscribed. `PushSubscriptionService` runs its calls one at a time in a queue, so a logout unsubscribe that is still running is ordered before the sync of the next login.

## Other things checked and found correct

- Acceptance criterion 1 is met:
  - VAPID keys are generated on `ApplicationReadyEvent`. The private key is stored as an `EncryptedSecret`, and a singleton constraint plus a REQUIRES_NEW re-read handle two instances starting at once.
  - The subscriptions API (GET key, PUT and DELETE subscription) requires a login, validates its input and returns Problem Details. The endpoint is sent in the request body.
  - The service worker shows `reminder` and `mail` pushes. Their payload shapes match `ReminderResponse` and `NewMailNotification` (`mail/sync/NewMailAnnouncer.kt:31`).
  - A 404 or 410 from the push service deletes the subscription (`PushService.kt:182-185`, tested in `PushDeliveryTests`).
- Architecture:
  - Packages are `core.push.{api,domain,infra}`. Domain importing infra repositories matches other modules (`files`, `backup`).
  - Core does not depend on the mail module: the relay uses the event name string `"mail"`.
  - The migration file name follows the `core/V{timestamp}__core_{desc}.sql` rule.
  - The OpenAPI file and the generated client are updated (`api:check` is part of `npm run check`).
- Security and logging:
  - Push service redirects are not followed.
  - Logs name only subscription ids, never endpoints, keys or message contents.
  - VAPID claims are JSON-escaped.
  - Received public keys are checked against the P-256 curve.
  - Notification click URLs stay inside the worker's scope.
- Backup: `VapidKeyService` reads the key from the database on every call, with no cache, so after a restore empties the table the next use generates new keys. This matches the recorded assumption.
- Tests: the server push tests (55, run by the parent against Postgres via Testcontainers) and the web specs cover subscribe, unsubscribe, key replacement, ordering in the queue, the logout paths, the login gate, the service worker's rendering, suppression and click handling, and the toggle hint. The tests check behaviour and do not mock away the logic under test.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Push subscriptions outlive a session that ends without an explicit logout (browser-session login, idle expiry)**
    - File: `web/src/app/core/notifications/provide-reminder-notifications.ts:26`; `web/src/app/core/auth/auth.service.ts:95`; `server/src/main/kotlin/ch/sonensei/cockpit/core/push/domain/PushService.kt:128-145` (a subscription is not tied to a session); `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/AuthSession.kt:42-43`
    - Category: Security
    - Problem: Only an explicit `AuthService.logout()` removes the browser's subscription. A session without "remember device" ends when the browser closes (its cookie is a browser-session cookie) or after 12 h idle (`BROWSER_SESSION_IDLE`). After that, the client only sets `loggedIn` to false, and the effect then deliberately does nothing. The server has no link between subscriptions and sessions. So the server keeps pushing mail senders and subjects (`push-sw.js:66-78`) and reminder titles to that browser until it is subscribed again or the push service reports 410. This is the "or session expiry" part of the earlier CR-1, which the fix did not address. It is also not recorded as an assumption in `tasks/web-push.md`.
    - Why it matters: On a shared or borrowed computer, a user who logs in without "remember device" and just closes the browser expects their data to stop appearing there, as it did before this task, when notifications came only over the authenticated SSE stream. Now personal mail and task data keeps appearing on that machine's lock screen with no session behind it.
    - Suggested fix: Tie each subscription to the session that created it. For example, store the `auth_session` id on `push_subscription`, delete the subscription when the session is deleted or expires, and skip or delete subscriptions of expired sessions in `PushService.send`. Alternatively, subscribe only for remembered sessions. If the current behaviour is intended, record it with `tasks.py assume` and say in the toggle hint that pushes continue after the session ends. Add a test for the behaviour you choose.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task. CR-1 is the unresolved remainder of the earlier CR-1. Fix it, or have the parent worker accept it explicitly as an assumption. Fixes CR-3, CR-4 and CR-5 hold, CR-2 is accepted, and the changes since the last review are correct and tested.
