---
title: Dashboard
status: todo
owner:
deps: [web-today-habits,web-weekly-review,web-bookmarks,web-notes-list,web-mail-list]
touches: [web/src/app/features/dashboard]
prio: 2
---
## Goal
Configurable home (SPEC.md §8.7).

## Acceptance criteria
- [ ] Widgets: Today, Inbox count (tasks + unread mail), Stalled projects, Waiting For overdue, Upcoming events, Read-later, Pinned notes, Last weekly review; show/hide and order saved in settings
- [ ] Vitest component/service tests cover widget configuration persistence; `cd web && npm run check` passes

## Notes
