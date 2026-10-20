---
title: Waiting For, Someday, Scheduled, Logbook views
status: todo
owner:
deps: [web-gtd-data]
touches: [web/src/app/features/gtd/lists]
prio: 2
---
## Goal
The remaining GTD lists (SPEC.md §5.3).

## Acceptance criteria
- [ ] Waiting For sorted by waitingSince with 'follow up' (creates a task); Someday with 'activate'; Scheduled grouped by date; Logbook (completed, by day, uncomplete)
- [ ] Vitest component/service tests cover sorting and actions; `cd web && npm run check` passes
- [ ] Playwright `e2e/gtd-lists/lists.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/gtd-lists/lists.spec.ts`, see p0-e2e-harness): waiting item sorted by waitingSince; logbook shows completed task

## Notes
