---
title: Web Push notifications
status: in_progress
owner: dell26/agent-1
deps: [reminders-core,web-reminders,app-icons]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/push,server/src/test/kotlin/ch/sonensei/cockpit/core/push,web/src/app/core/notifications,web/public]
prio: 3
lease_until: 2026-10-26T16:41:03Z
extended: 
releases: 1
---
## Goal
Notifications without an open tab (SPEC.md §4.6).

## Acceptance criteria
- [ ] VAPID keys generated at first start, subscriptions API, service worker shows reminder/new-mail pushes; unsubscribe on 410
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.push.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] Vitest component/service tests cover subscription flow; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-26 15:44+0100 by felix/agent-1): Web Push implemented with JDK crypto only (RFC 8291 aes128gcm + RFC 8292 VAPID, ~150 lines in core/push/infra/WebPushCrypto.kt) instead of adding the unmaintained nl.martijndwars:web-push library; round-trip and VAPID-signature tests cover it.
- ASSUMPTION (2026-10-26 15:44+0100 by felix/agent-1): Push follows the existing 'reminders as browser notifications' switch (no separate setting): on + permission granted -> subscribe, otherwise unsubscribe. Pushed events are the existing SSE 'reminder' and 'mail' notifications (mail only for accounts with notifyNewMail); the service worker skips showing when a visible tab exists (SSE shows it) and reuses the reminder-<id> tag to avoid duplicates.
- ASSUMPTION (2026-10-26 15:44+0100 by felix/agent-1): VAPID keys and push subscriptions are not in the backup (like sessions): restore empties both, the server generates new keys on next use and the web app replaces subscriptions made with an old key on its next start. VAPID subject = COCKPIT_PUSH_SUBJECT, default https://<COCKPIT_DOMAIN>.
- ASSUMPTION (2026-10-26 15:44+0100 by felix/agent-1): Subscription endpoints must be https (no userinfo); there is no private-network/SSRF filter on push endpoints because only the authenticated single owner can register them. Unsubscribe is DELETE /api/v1/push/subscriptions with the endpoint in the JSON body so the capability URL stays out of access logs.
- ASSUMPTION (2026-10-26 15:44+0100 by felix/agent-1): Service worker texts (EN/DE) live in public/push-sw.js and are chosen by ?lang=<LOCALE_ID> at registration, since the plain-JS worker can't use $localize; the worker is registered relative to the base href so the /de build gets its own scope.
- extended by felix/agent-1: Impl committed (server core/push + web SW/subscription flow); test-writer tests committed in 2540d666 but tests.md report missing; review.md has CR-1 (P2: logout leaves browser push-subscribed) and CR-2..5 (P3, incl. no pushsubscriptionchange). Left: .taskcheck hung >10 min in 'server: ktlint + tests' after the new test-writer push tests (PushNotificationRelayTests/PushPropertiesTests/WebPushCryptoEdgeCaseTests suspects) - find and fix the hang; fixer for CR-1 (+P3 as judged); rerun test-writer (with tests.md) and code-reviewer on final code; verify 3 criteria; quality.json; done.
- ASSUMPTION (2026-10-26 16:37+0100 by felix/agent-1): Review CR-2 accepted: the service worker does not handle pushsubscriptionchange (it would need the session cookie + CSRF header inside the worker); a rotated subscription is replaced on the next app start by PushSubscriptionService.sync, the stale one is removed on the push service's 410.
- release by felix/agent-1: Done: server core/push (VAPID keys, subscriptions API, JDK-crypto Web Push, 410 unsubscribe), web SW + subscription flow, test-writer tests (21c76592), review.md CR-1..5, fixer fixed CR-1/3/4/5 (fixes.md, CR-2 accepted as assumption). Left: (1) last commit 90f0a306 adds unfinished tests to server PushWebTests.kt (lines ~310-365) that don't compile: missing imports shouldNotThrowAny/shouldThrow (kotest), VapidKey, EncryptedSecret, UuidV7, plus a List<String?> type mismatch at 364 - fix imports or drop those tests; web push-logout.spec.ts in the same commit is untested by check yet. (2) re-run sh scripts/check.sh (earlier the server step once hung >10 min; check whether it still does). (3) re-run test-writer (must write reviews/web-push/tests.md with coverage) and code-reviewer on the final code (fixes changed code, so review.md is stale). (4) verify the 3 criteria (incl. ./gradlew test --tests 'ch.sonensei.cockpit.core.push.*' with Testcontainers), write quality.json, done.
