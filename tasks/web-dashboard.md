---
title: Dashboard
status: in_progress
owner: dell26/agent-1
deps: [web-today-habits,web-weekly-review,web-bookmarks,web-notes-list,web-mail-list]
touches: [web/src/app/features/dashboard]
prio: 2
lease_until: 2026-10-22T08:44:15Z
---
## Goal
Configurable home (SPEC.md §8.7).

## Acceptance criteria
- [ ] Widgets: Today, Inbox count (tasks + unread mail), Stalled projects, Waiting For overdue, Upcoming events, Read-later, Pinned notes, Last weekly review; show/hide and order saved in settings
- [ ] Vitest component/service tests cover widget configuration persistence; `cd web && npm run check` passes

## Notes
