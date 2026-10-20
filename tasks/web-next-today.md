---
title: Next Actions and Today views
status: in_progress
owner: dell26/agent-4
deps: [web-gtd-data]
touches: [web/src/app/features/gtd/next,web/src/app/features/gtd/today]
prio: 1
lease_until: 2026-10-20T20:59:24Z
---
## Goal
Filterable next actions and the Today view (SPEC.md §5.3).

## Acceptance criteria
- [ ] Next view filters: context, energy, creativity, max estimate, area/project, due; filters in the URL; saved presets stored in settings
- [ ] Today view: overdue, due today, scheduled today (calendar events and habits are added by later tasks via extension slots)
- [ ] Vitest component/service tests cover filter combinations and presets; `cd web && npm run check` passes
- [ ] Playwright `e2e/gtd-next/next.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/gtd-next/next.spec.ts`, see p0-e2e-harness): @phone + ≤15 min filter shows only the matching task

## Notes
- ASSUMPTION (2026-10-20 22:43+0200 by dell26/agent-4): Saved Next presets are stored in a new server setting 'nextActionPresets' (registered in gtd/infra/GtdSettings.kt, TextList of JSON texts {name, filter}, max 50) because /api/v1/settings rejects unknown keys and SettingType has no object-list type.
- ASSUMPTION (2026-10-20 22:43+0200 by dell26/agent-4): Area filter is applied client-side (task's project.areaId) since GET /api/v1/tasks has no area parameter; tasks without a project never match an area.
