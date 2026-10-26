---
title: Web Push notifications
status: in_progress
owner: felix/agent-1
deps: [reminders-core,web-reminders,app-icons]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/push,server/src/test/kotlin/ch/sonensei/cockpit/core/push,web/src/app/core/notifications,web/public]
prio: 3
lease_until: 2026-10-26T15:01:58Z
---
## Goal
Notifications without an open tab (SPEC.md §4.6).

## Acceptance criteria
- [ ] VAPID keys generated at first start, subscriptions API, service worker shows reminder/new-mail pushes; unsubscribe on 410
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.push.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] Vitest component/service tests cover subscription flow; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-26 15:44+0100 by felix/agent-1): Web Push implemented with JDK crypto only (RFC 8291 aes128gcm + RFC 8292 VAPID, ~150 lines in core/push/infra/WebPushCrypto.kt) instead of adding the unmaintained nl.martijndwars:web-push library; round-trip and VAPID-signature tests cover it.
