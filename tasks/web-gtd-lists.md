---
title: Waiting For, Someday, Scheduled, Logbook views
status: in_progress
owner: dell26/agent-6
deps: [web-gtd-data]
touches: [web/src/app/features/gtd/lists]
prio: 2
lease_until: 2026-10-20T22:06:42Z
---
## Goal
The remaining GTD lists (SPEC.md §5.3).

## Acceptance criteria
- [ ] Waiting For sorted by waitingSince with 'follow up' (creates a task); Someday with 'activate'; Scheduled grouped by date; Logbook (completed, by day, uncomplete)
- [ ] Vitest component/service tests cover sorting and actions; `cd web && npm run check` passes
- [ ] Playwright `e2e/gtd-lists/lists.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/gtd-lists/lists.spec.ts`, see p0-e2e-harness): waiting item sorted by waitingSince; logbook shows completed task

## Notes
- ASSUMPTION (2026-10-20 23:38+0200 by dell26/agent-6): Added a /scheduled route + nav entry (gtd/gtd.routes.ts, shell/nav-sections.ts, app.spec.ts BUILT list, features/README.md; outside touches, one line each) since no Scheduled section existed and features/README.md says new GTD views add their route there
- ASSUMPTION (2026-10-20 23:38+0200 by dell26/agent-6): 'Follow up' creates a NEXT task titled 'Follow up with <waitingForText>: <title>' (or 'Follow up: <title>') in the same project and keeps the waiting item; drafting an email to the contact (SPEC 5.3 alternative) is left out since the criterion only asks for a task
- ASSUMPTION (2026-10-20 23:38+0200 by dell26/agent-6): Waiting For shows the waiting age in days, accented after 7 days; Logbook loads all completed tasks of every list and reopens via the row checkbox; day headings use the browser's local date
