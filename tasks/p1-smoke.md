---
title: Phase 1 demo script
status: in_progress
owner: dell26/agent-4
deps: [web-capture,web-inbox,web-next-today,web-projects,web-task-detail,web-gtd-lists,web-trash,web-search,web-reminders,web-settings-gtd,p0-smoke]
touches: [web/e2e/phase1]
prio: 1
lease_until: 2026-10-21T03:07:45Z
---
## Goal
Automate SPEC.md §13.2 Phase 1 end to end.

## Acceptance criteria
- [ ] `web/e2e/phase1/*.spec.ts` covers demo steps 1–9 (incl. tickler with the e2e clock and recurrence) and passes, together with phase 0 specs
- [ ] Defects found are fixed here or recorded as new tasks

## Notes
- ASSUMPTION (2026-10-21 04:39+0200 by dell26/agent-4): SPEC.md §13.2 phase 1 step 2 says 'Plan holiday' goes to a new project 'Holiday', but step 1 captures it with '#Holiday', which the parser only resolves for existing projects. The demo creates 'Holiday' in a set-up step and moves 'Plan holiday' to it; the 'New project' path of inbox processing is exercised end to end on a separate item instead.
