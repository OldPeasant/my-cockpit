---
title: Share habit check label/error helpers between Habits view and Today
status: in_progress
owner: felix/agent-1
deps: [web-today-habits]
touches: [web/src/app/features/habits,web/src/app/features/gtd/today]
prio: 2
lease_until: 2026-10-26T09:23:53Z
---
## Goal
`checkLabel()` and the error `message()` helper are copied in `features/habits/habits-page.ts` and
`features/gtd/today/today-habits.ts` (review web-today-habits CR-3). Move them into
`features/habits/habit-display.ts` and use them from both.

## Acceptance criteria
- [ ] One shared implementation of the check label and error message, used by both components
- [ ] Existing Vitest tests of both still pass; `cd web && npm run check` passes

## Notes
Follow-up from reviews/web-today-habits/review.md (CR-3, P3).
