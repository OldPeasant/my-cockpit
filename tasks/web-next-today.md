---
title: Next Actions and Today views
status: in_progress
owner: dell26/agent-4
deps: [web-gtd-data]
touches: [web/src/app/features/gtd/next,web/src/app/features/gtd/today]
prio: 1
lease_until: 2026-10-20T22:20:07Z
extended: dell26/agent-4
---
## Goal
Filterable next actions and the Today view (SPEC.md §5.3).

## Acceptance criteria
- [ ] Next view filters: context, energy, creativity, max estimate, area/project, due; filters in the URL; saved presets stored in settings
- [ ] Today view: overdue, due today, scheduled today (calendar events and habits are added by later tasks via extension slots)
- [ ] Vitest component/service tests cover filter combinations and presets; `cd web && npm run check` passes
- [x] Playwright `e2e/gtd-next/next.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/gtd-next/next.spec.ts`, see p0-e2e-harness): @phone + ≤15 min filter shows only the matching task
  - verified (2026-10-20 23:54+0200 by dell26/agent-4): npm --prefix web run e2e -- e2e/gtd-next/next.spec.ts (deploy/docker-compose.e2e.yml stack, real setup+login, chromium) at 75bd7412 -> 6 passed incl. 'next actions > @phone + <= 15 min shows only the matching task'

## Notes
- ASSUMPTION (2026-10-20 22:43+0200 by dell26/agent-4): Saved Next presets are stored in a new server setting 'nextActionPresets' (registered in gtd/infra/GtdSettings.kt, TextList of JSON texts {name, filter}, max 50) because /api/v1/settings rejects unknown keys and SettingType has no object-list type.
- ASSUMPTION (2026-10-20 22:43+0200 by dell26/agent-4): Area filter is applied client-side (task's project.areaId) since GET /api/v1/tasks has no area parameter; tasks without a project never match an area.
- ASSUMPTION (2026-10-20 22:44+0200 by dell26/agent-4): Today 'scheduled for today' shows SCHEDULED tasks with scheduledDate <= today; the server's releaseDue moves them to NEXT and clears scheduledDate on read, so in practice this section is usually empty until the server keeps a marker (follow-up task gtd-scheduled-marker).
- ASSUMPTION (2026-10-20 22:44+0200 by dell26/agent-4): Today view lives at /today with a nav entry under Tasks; overdue/due-today sections include open tasks of every list; extension slots are a multi-provider TODAY_SECTIONS (provideTodaySection) rendered after the task sections.
- ASSUMPTION (2026-10-20 22:44+0200 by dell26/agent-4): Next filter 'max estimate' excludes tasks without an estimate (server rule of maxMinutes); 'Time available' options are the estimate quick picks (<= 5 min ... <= 2 h).
- ASSUMPTION (2026-10-20 23:13+0200 by dell26/agent-4): Edited files outside touches (small, required for the views): web/src/app/features/gtd/gtd.routes.ts (route /today), web/src/app/shell/nav-sections.ts (Today nav entry), web/src/app/app.spec.ts (BUILT list), server gtd/infra/GtdSettings.kt (nextActionPresets setting).
- extended by dell26/agent-4: Implementation complete (Next view with URL filters + presets in setting nextActionPresets, Today view with extension slots, e2e spec), check passes at e7d5f769. Code review done (reviews/web-next-today/review.md, written against pre-rebase hashes; code unchanged since). Test-writer added tests (committed in e7d5f769) but did not write tests.md. Left: re-run test-writer to write tests.md with coverage; fixer for CR-1 (save/remove presets when settings unloaded wipes presets) and CR-3 (failed delete silently undone); CR-2 is covered by follow-up gtd-scheduled-marker, CR-4 by the recorded assumption; re-review after fixes; run e2e next.spec.ts; verify 1-4; quality.json; done.
