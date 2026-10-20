---
title: Browser notifications for reminders
status: todo
owner:
deps: [reminders-core,p0-web-sse]
touches: [web/src/app/core/notifications]
prio: 2
---
## Goal
Show reminders as browser notifications (SPEC.md §4.6).

## Acceptance criteria
- [ ] Asks for Notification permission from a user gesture (settings toggle), shows a notification on the `reminder` SSE event; in-app toast fallback
- [ ] Vitest component/service tests cover permission states and fallback; `cd web && npm run check` passes

## Notes
