---
title: Bind Web Push subscriptions to the login session
status: todo
owner:
deps: [web-push]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/push,server/src/main/kotlin/ch/sonensei/cockpit/core/auth,server/src/main/resources/db/migration/core,server/src/test/kotlin/ch/sonensei/cockpit/core/push]
prio: 2
---
## Goal
Web Push stops when the session that subscribed ends, also without an explicit logout (browser-session login
closed, 12 h idle expiry, session revoked). Follow-up of review finding CR-1 in `reviews/web-push/review.md`.

## Acceptance criteria
- [ ] `push_subscription` references the `auth_session` that created it (new Flyway migration, `on delete cascade`); PUT /api/v1/push/subscriptions stores the current session (a session id in `CockpitPrincipal`); a re-subscribe from a new session moves the subscription to it
- [ ] Pushes are not sent to subscriptions whose session is expired; they are deleted (with the session cleanup or on send)
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.push.*'` passes, with tests for logout, idle expiry and revoked session

## Notes
- Also worth doing here: `core/push` holds two RFC 8291 encryptions (`infra/WebPushCrypto.encrypt` from web-push and
  `domain/WebPushEncryption` from UnifiedPush); let `PushService` use `WebPushEncryption` and drop the duplicate
  together with its tests (keep the RFC 8291 vector test).
