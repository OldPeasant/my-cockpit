---
title: Next Actions and Today views
status: todo
owner:
deps: [web-gtd-data]
touches: [web/src/app/features/gtd/next,web/src/app/features/gtd/today]
prio: 1
---
## Goal
Filterable next actions and the Today view (SPEC.md §5.3).

## Acceptance criteria
- [ ] Next view filters: context, energy, creativity, max estimate, area/project, due; filters in the URL; saved presets stored in settings
- [ ] Today view: overdue, due today, scheduled today (calendar events and habits are added by later tasks via extension slots)
- [ ] Vitest component/service tests cover filter combinations and presets; `cd web && npm run check` passes
- [ ] Playwright `e2e/gtd-next/next.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/gtd-next/next.spec.ts`, see p0-e2e-harness): @phone + ≤15 min filter shows only the matching task

## Notes
