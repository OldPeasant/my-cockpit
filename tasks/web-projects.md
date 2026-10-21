---
title: Projects views
status: in_progress
owner: dell26/agent-6
deps: [web-gtd-data]
touches: [web/src/app/features/gtd/projects]
prio: 1
lease_until: 2026-10-21T01:48:44Z
---
## Goal
Project list and detail (SPEC.md §5.3).

## Acceptance criteria
- [x] List grouped by area with stalled projects highlighted; create/edit project (status, area, dates)
  - verified (2026-10-21 03:30+0200 by dell26/agent-6): npm run test:ci: projects-pages/projects-states/project-groups/project-form specs (grouping by area, stalled badge, create/edit with status/area/dates) pass, 1226 tests total; e2e projects.spec.ts shows stalled project after skip -> passed
- [ ] Detail: ordered tasks with drag reorder, next-step marker, description (Markdown preview), add task; completing the next step opens 'choose next step'
- [ ] Vitest component/service tests cover grouping, stalled badge, next-step prompt; `cd web && npm run check` passes
- [x] Playwright `e2e/gtd-projects/projects.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/gtd-projects/projects.spec.ts`, see p0-e2e-harness): complete next step → prompt; skip → stalled
  - verified (2026-10-21 03:30+0200 by dell26/agent-6): cd web && npm run e2e -- e2e/gtd-projects/projects.spec.ts (podman-compose e2e stack, deploy/docker-compose.e2e.yml, server image rebuilt at 942dc560) -> 6 passed incl. 'completing the next step prompts; skipping leaves the project stalled'

## Notes
- ASSUMPTION (2026-10-21 02:50+0200 by dell26/agent-6): Markdown preview uses a small in-house renderer (projects/markdown.ts: headings, lists, quotes, code, emphasis, http/mailto links, all text escaped) since no Markdown library is in web/package.json; replace with the notes editor's renderer once web-notes-editor brings one
- ASSUMPTION (2026-10-21 02:50+0200 by dell26/agent-6): Areas are read via a local read-only AreaStore in features/gtd/projects (data/ is outside this task's touches); area management stays with web-settings-gtd
- ASSUMPTION (2026-10-21 02:50+0200 by dell26/agent-6): Adding a task to a stalled ACTIVE project makes it the next step automatically (GTD: a project's first/only step is its next action); otherwise new tasks go to the end of the project as NEXT-list tasks
- ASSUMPTION (2026-10-21 02:50+0200 by dell26/agent-6): The 'Choose next step' prompt is an inline panel on the project detail (not a modal) and only triggers there; completing a next step from other views (Next, Today) does not prompt yet. A stalled project shows a 'Choose next step' button to reopen it
- ASSUMPTION (2026-10-21 02:50+0200 by dell26/agent-6): Project list hides COMPLETED/DROPPED projects behind a 'Show completed and dropped' toggle; within an area, projects order by status (active first) then title
