---
title: Bind Web Push subscriptions to the login session
status: in_progress
owner: dell26/agent-1
deps: [web-push]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/push,server/src/main/kotlin/ch/sonensei/cockpit/core/auth,server/src/main/resources/db/migration/core,server/src/test/kotlin/ch/sonensei/cockpit/core/push]
prio: 2
lease_until: 2026-10-26T18:25:45Z
---
## Goal
Web Push stops when the session that subscribed ends, also without an explicit logout (browser-session login
closed, 12 h idle expiry, session revoked). Follow-up of review finding CR-1 in `reviews/web-push/review.md`.

## Acceptance criteria
- [x] `push_subscription` references the `auth_session` that created it (new Flyway migration, `on delete cascade`); PUT /api/v1/push/subscriptions stores the current session (a session id in `CockpitPrincipal`); a re-subscribe from a new session moves the subscription to it
  - verified (2026-10-26 19:07+0100 by dell26/agent-1): Migration V202610261800__core_push_session.sql adds push_subscription.session_id uuid not null references auth_session on delete cascade; CockpitPrincipal.sessionId set by LoginService; PushSessionBindingTests (real setup+login+cookie PUT) 'subscribing stores the session of the request' and 'subscribing again from a new session moves the subscription to it' pass: ./gradlew test --tests 'ch.sonensei.cockpit.core.push.*' on ed147c713 against Testcontainers Postgres 16 -> 127 tests, 0 failures
- [ ] Pushes are not sent to subscriptions whose session is expired; they are deleted (with the session cleanup or on send)
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.push.*'` passes, with tests for logout, idle expiry and revoked session

## Notes
- Also worth doing here: `core/push` holds two RFC 8291 encryptions (`infra/WebPushCrypto.encrypt` from web-push and
  `domain/WebPushEncryption` from UnifiedPush); let `PushService` use `WebPushEncryption` and drop the duplicate
  together with its tests (keep the RFC 8291 vector test).
- ASSUMPTION (2026-10-26 18:50+0100 by dell26/agent-1): Migration deletes existing push_subscription rows (they have no session); the web app re-registers on its next start (PushSubscriptionService.sync), so a browser at most misses pushes until it is opened again.
- ASSUMPTION (2026-10-26 18:50+0100 by dell26/agent-1): PUT /api/v1/push/subscriptions without a browser session (device-token principal) answers 403 Problem Details: Web Push is browser-only, Android uses UnifiedPush.
- ASSUMPTION (2026-10-26 18:50+0100 by dell26/agent-1): Expired-but-not-yet-deleted sessions: PushService.send deletes their subscriptions and only sends to subscriptions joined with a session whose expires_at > now; the auth_session row itself stays for the auth module's own cleanup. Subscription keys are now stored normalised as unpadded base64url.
