---
title: Web Push notifications
status: todo
owner:
deps: [reminders-core,web-reminders,app-icons]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/push,server/src/test/kotlin/ch/sonensei/cockpit/core/push,web/src/app/core/notifications,web/public]
prio: 3
---
## Goal
Notifications without an open tab (SPEC.md §4.6).

## Acceptance criteria
- [ ] VAPID keys generated at first start, subscriptions API, service worker shows reminder/new-mail pushes; unsubscribe on 410
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.push.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] Vitest component/service tests cover subscription flow; `cd web && npm run check` passes

## Notes
