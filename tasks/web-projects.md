---
title: Projects views
status: todo
owner:
deps: [web-gtd-data]
touches: [web/src/app/features/gtd/projects]
prio: 1
---
## Goal
Project list and detail (SPEC.md §5.3).

## Acceptance criteria
- [ ] List grouped by area with stalled projects highlighted; create/edit project (status, area, dates)
- [ ] Detail: ordered tasks with drag reorder, next-step marker, description (Markdown preview), add task; completing the next step opens 'choose next step'
- [ ] Vitest component/service tests cover grouping, stalled badge, next-step prompt; `cd web && npm run check` passes
- [ ] Playwright `e2e/gtd-projects/projects.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/gtd-projects/projects.spec.ts`, see p0-e2e-harness): complete next step → prompt; skip → stalled

## Notes
