---
title: Browser notifications for reminders
status: in_progress
owner: dell26/agent-2
deps: [reminders-core,p0-web-sse]
touches: [web/src/app/core/notifications]
prio: 2
lease_until: 2026-10-20T17:01:39Z
---
## Goal
Show reminders as browser notifications (SPEC.md §4.6).

## Acceptance criteria
- [ ] Asks for Notification permission from a user gesture (settings toggle), shows a notification on the `reminder` SSE event; in-app toast fallback
- [ ] Vitest component/service tests cover permission states and fallback; `cd web && npm run check` passes

## Notes
