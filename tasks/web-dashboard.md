---
title: Dashboard
status: in_progress
owner: dell26/agent-1
deps: [web-today-habits,web-weekly-review,web-bookmarks,web-notes-list,web-mail-list]
touches: [web/src/app/features/dashboard]
prio: 2
lease_until: 2026-10-22T08:59:21Z
---
## Goal
Configurable home (SPEC.md §8.7).

## Acceptance criteria
- [ ] Widgets: Today, Inbox count (tasks + unread mail), Stalled projects, Waiting For overdue, Upcoming events, Read-later, Pinned notes, Last weekly review; show/hide and order saved in settings
- [ ] Vitest component/service tests cover widget configuration persistence; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-22 10:25+0200 by dell26/agent-1): Layout stored in a new server setting dashboardWidgets (TextList, core/settings/infra/DashboardSettings.kt): ordered widget ids, '<id>:hidden' for hidden; widgets missing from the list show at the end. Outside the task's touches, but settings must be registered server-side.
- ASSUMPTION (2026-10-22 10:25+0200 by dell26/agent-1): Widget definitions: Today = overdue + due-today tasks, today's events, habits due/done; Upcoming = next 7 days; Waiting For overdue = WAITING tasks with due=overdue; Stalled = ACTIVE projects with stalled=true; Read-later = readLater & unread bookmarks; last weekly review turns red and offers 'Start' after 7 days.
- ASSUMPTION (2026-10-22 10:26+0200 by dell26/agent-1): Counts are capped at one page (50): Inbox tasks and unread mail show 'N+' beyond it; unread mail = unseen messages among the newest 50 of the unified Inbox (no unread-count API exists).
- ASSUMPTION (2026-10-22 10:38+0200 by dell26/agent-1): Review fixes: unread mail shows 'N+' only when N>0 (a '0+' was misleading); the last-weekly-review figure turns red and 'Start a weekly review' shows from 7 days on (>=7). The server sends no WEEKLY_REVIEW change event, so that widget refreshes on page open, re-show and SSE resync only.
