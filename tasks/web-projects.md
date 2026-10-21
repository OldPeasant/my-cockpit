---
title: Projects views
status: in_progress
owner: dell26/agent-6
deps: [web-gtd-data]
touches: [web/src/app/features/gtd/projects]
prio: 1
lease_until: 2026-10-21T01:08:18Z
---
## Goal
Project list and detail (SPEC.md §5.3).

## Acceptance criteria
- [ ] List grouped by area with stalled projects highlighted; create/edit project (status, area, dates)
- [ ] Detail: ordered tasks with drag reorder, next-step marker, description (Markdown preview), add task; completing the next step opens 'choose next step'
- [ ] Vitest component/service tests cover grouping, stalled badge, next-step prompt; `cd web && npm run check` passes
- [ ] Playwright `e2e/gtd-projects/projects.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/gtd-projects/projects.spec.ts`, see p0-e2e-harness): complete next step → prompt; skip → stalled

## Notes
- ASSUMPTION (2026-10-21 02:50+0200 by dell26/agent-6): Markdown preview uses a small in-house renderer (projects/markdown.ts: headings, lists, quotes, code, emphasis, http/mailto links, all text escaped) since no Markdown library is in web/package.json; replace with the notes editor's renderer once web-notes-editor brings one
- ASSUMPTION (2026-10-21 02:50+0200 by dell26/agent-6): Areas are read via a local read-only AreaStore in features/gtd/projects (data/ is outside this task's touches); area management stays with web-settings-gtd
- ASSUMPTION (2026-10-21 02:50+0200 by dell26/agent-6): Adding a task to a stalled ACTIVE project makes it the next step automatically (GTD: a project's first/only step is its next action); otherwise new tasks go to the end of the project as NEXT-list tasks
- ASSUMPTION (2026-10-21 02:50+0200 by dell26/agent-6): The 'Choose next step' prompt is an inline panel on the project detail (not a modal) and only triggers there; completing a next step from other views (Next, Today) does not prompt yet. A stalled project shows a 'Choose next step' button to reopen it
