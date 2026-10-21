---
title: Projects views
status: in_progress
owner: dell26/agent-6
deps: [web-gtd-data]
touches: [web/src/app/features/gtd/projects]
prio: 1
lease_until: 2026-10-21T01:02:21Z
---
## Goal
Project list and detail (SPEC.md §5.3).

## Acceptance criteria
- [ ] List grouped by area with stalled projects highlighted; create/edit project (status, area, dates)
- [ ] Detail: ordered tasks with drag reorder, next-step marker, description (Markdown preview), add task; completing the next step opens 'choose next step'
- [ ] Vitest component/service tests cover grouping, stalled badge, next-step prompt; `cd web && npm run check` passes
- [ ] Playwright `e2e/gtd-projects/projects.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/gtd-projects/projects.spec.ts`, see p0-e2e-harness): complete next step → prompt; skip → stalled

## Notes
