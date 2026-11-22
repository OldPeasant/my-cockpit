---
title: Web: task-reminders.spec.ts fails since 2026-11-21 (fixture dates in the past, real clock)
status: in_progress
owner: dell26/agent-2
deps: []
touches: [web/src/app/features/gtd/task-detail/task-reminders.spec.ts]
prio: 2
lease_until: 2026-11-22T09:27:25Z
---
## Goal
`sh scripts/check.sh` (web part) fails on main for every agent: 4 tests in
`web/src/app/features/gtd/task-detail/task-reminders.spec.ts` click `buttons('.chip')[0]` but no preset chip is
rendered, because the fixture anchors (due 2026-11-20 14:30) are now in the past and the component hides past presets
using the real clock. Failing: "shows the error when adding or loading fails", "does not let a reloading list bring back
a reminder removed meanwhile", "offers a retry instead of claiming there are none when loading fails", "does not reload
while an add after a failed load is pending".

## Acceptance criteria
- [ ] The spec no longer depends on the real date (fixed/fake clock or future-proof fixtures); the 4 tests pass
- [ ] `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Found 2026-11-22 by agent-1 while finishing and-cal-initial-scroll (reproduced alone with
`npm --prefix web run test:ci -- --include src/app/features/gtd/task-detail/task-reminders.spec.ts`).
