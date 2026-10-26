---
title: Share habit check label/error helpers between Habits view and Today
status: in_progress
owner: felix/agent-1
deps: [web-today-habits]
touches: [web/src/app/features/habits,web/src/app/features/gtd/today]
prio: 2
lease_until: 2026-10-26T13:00:41Z
extended: 
releases: 1
---
## Goal
`checkLabel()` and the error `message()` helper are copied in `features/habits/habits-page.ts` and
`features/gtd/today/today-habits.ts` (review web-today-habits CR-3). Move them into
`features/habits/habit-display.ts` and use them from both.

## Acceptance criteria
- [x] One shared implementation of the check label and error message, used by both components
  - verified (2026-10-26 11:03+0100 by felix/agent-1): grep checkLabel/errorMessage in web/src: defined once in features/habits/habit-display.ts, imported by habits-page.ts and gtd/today/today-habits.ts, no local copies left; i18n ids @@habits.check/uncheck/checkCount/error unchanged (git diff 8ab942ce..HEAD); code-reviewer reviews/web-habits-shared-labels/review.md -> 0 findings
- [x] Existing Vitest tests of both still pass; `cd web && npm run check` passes
  - verified (2026-10-26 11:03+0100 by felix/agent-1): cd web && npm run check (lint+build+vitest via ng test) at 06172c40 -> pass, 175 files / 3678 tests passed incl. habits-page, today-habits, habit-display specs

## Notes
Follow-up from reviews/web-today-habits/review.md (CR-3, P3).
- extended by felix/agent-1: Implementation committed (8a14cd9a: checkLabel/errorMessage moved to habit-display.ts, used by habits-page and today-habits). Tests + reviews/web-habits-shared-labels/{tests.md,review.md} committed in 082e245a; test-writer ran npm run check (pass, 3671 tests) and coverage; review has 0 findings. i18n ids confirmed unchanged (git diff 8a14cd9a~1 8a14cd9a | grep @@). Left: re-run code-reviewer if done treats the spec additions (committed after review) as stale; tasks.py verify #1/#2; run .taskcheck; write+commit quality.json (fixer not required); tasks.py done.
- release by felix/agent-1: All work done and committed (5 local commits ahead of origin/main, backed up to wip/): checkLabel/errorMessage shared in features/habits/habit-display.ts, used by habits-page and today-habits; both criteria verified; tests.md, review.md (0 findings), quality.json (reviewed_revision 06172c40, fixer not required) committed. Only left: python tasks.py done web-habits-shared-labels. My run of done hung >20 min with no output (killed by tool timeout, beyond the 15 min check timeout) - run it with visible output (no pipe) to see where it stalls; if reviewed_revision is rejected after merging main, set it to the new HEAD hash (only tasks/reviews changed).
