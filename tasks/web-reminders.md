---
title: Browser notifications for reminders
status: in_progress
owner: dell26/agent-2
deps: [reminders-core,p0-web-sse]
touches: [web/src/app/core/notifications]
prio: 2
lease_until: 2026-10-20T17:21:55Z
---
## Goal
Show reminders as browser notifications (SPEC.md §4.6).

## Acceptance criteria
- [ ] Asks for Notification permission from a user gesture (settings toggle), shows a notification on the `reminder` SSE event; in-app toast fallback
- [ ] Vitest component/service tests cover permission states and fallback; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-20 18:48+0200 by dell26/agent-2): The browser-notification switch is stored per browser in localStorage (cockpit.reminderNotifications), not as a server setting: the Notification permission is per browser too, and SPEC 4.7 lists no such setting.
- ASSUMPTION (2026-10-20 18:48+0200 by dell26/agent-2): Reminder SSE events are consumed via a new generic ChangeEventsService.messages(name) in core/events (outside the task touches); the toggle sits in Settings > General and provideReminderNotifications() in app.routes.ts: small wiring edits outside core/notifications, needed to meet the criteria.
- ASSUMPTION (2026-10-20 18:48+0200 by dell26/agent-2): With notifications on and permitted, every fired reminder shows as a system notification (tag reminder-<id> dedupes across tabs), also when the tab is visible; otherwise, or when the browser refuses (Chrome Android needs a service worker), an in-app snackbar with an Open action (deep link) is shown.
