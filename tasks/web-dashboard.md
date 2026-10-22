---
title: Dashboard
status: in_progress
owner: dell26/agent-1
deps: [web-today-habits,web-weekly-review,web-bookmarks,web-notes-list,web-mail-list]
touches: [web/src/app/features/dashboard]
prio: 2
lease_until: 2026-10-22T09:52:21Z
releases: 0
extended: 
---
## Goal
Configurable home (SPEC.md §8.7).

## Acceptance criteria
- [x] Widgets: Today, Inbox count (tasks + unread mail), Stalled projects, Waiting For overdue, Upcoming events, Read-later, Pinned notes, Last weekly review; show/hide and order saved in settings
  - verified (2026-10-22 10:43+0200 by dell26/agent-1): cd web && npx ng test --watch=false --include 'src/app/features/dashboard/**/*.spec.ts' -> 84 passed (dashboard-page.spec renders all 8 widgets: Today, Inbox tasks+unread mail, Upcoming, Waiting For overdue, Stalled projects, Last weekly review, Read later, Pinned notes; hide/move in Customize PATCHes dashboardWidgets with If-Match and the stored order/hidden state is shown); cd server && ./gradlew test --tests '*DashboardSettingsWebTests' --tests '*SettingsServiceTests' (Testcontainers Postgres) -> pass (setting registered, default empty, order kept, invalid values 400)
- [x] Vitest component/service tests cover widget configuration persistence; `cd web && npm run check` passes
  - verified (2026-10-22 11:36+0200 by dell26/agent-1): cd web && npx ng test --watch=false --include 'src/app/features/dashboard/**/*.spec.ts' -> 87 passed (dashboard-layout.spec: hide/move/reset PATCH bodies, If-Match chain, rollback on refusal, quick changes while a save is unanswered, 412 reload, reset shown while in flight; dashboard-page.spec customize panel); cd web && npm run check -> pass (lint clean, 164 files / 3353 tests, build ok, existing bundle budget warning); sh scripts/check.sh -> all checks passed

## Notes
- ASSUMPTION (2026-10-22 10:25+0200 by dell26/agent-1): Layout stored in a new server setting dashboardWidgets (TextList, core/settings/infra/DashboardSettings.kt): ordered widget ids, '<id>:hidden' for hidden; widgets missing from the list show at the end. Outside the task's touches, but settings must be registered server-side.
- ASSUMPTION (2026-10-22 10:25+0200 by dell26/agent-1): Widget definitions: Today = overdue + due-today tasks, today's events, habits due/done; Upcoming = next 7 days; Waiting For overdue = WAITING tasks with due=overdue; Stalled = ACTIVE projects with stalled=true; Read-later = readLater & unread bookmarks; last weekly review turns red and offers 'Start' after 7 days.
- ASSUMPTION (2026-10-22 10:26+0200 by dell26/agent-1): Counts are capped at one page (50): Inbox tasks and unread mail show 'N+' beyond it; unread mail = unseen messages among the newest 50 of the unified Inbox (no unread-count API exists).
- ASSUMPTION (2026-10-22 10:38+0200 by dell26/agent-1): Review fixes: unread mail shows 'N+' only when N>0 (a '0+' was misleading); the last-weekly-review figure turns red and 'Start a weekly review' shows from 7 days on (>=7). The server sends no WEEKLY_REVIEW change event, so that widget refreshes on page open, re-show and SSE resync only.
- release by dell26/agent-1: handed back by dell26/agent-1: pushes had failed (remote: error: GH006: Protected branch update failed for refs/heads/main. | remote: error: | To https://github.com/OldP…); continue from wip/web-dashboard
