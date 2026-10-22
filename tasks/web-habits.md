---
title: Habits UI
status: in_progress
owner: dell26/agent-3
deps: [p0-web-shell,habits-api,p0-e2e-harness]
touches: [web/src/app/features/habits]
prio: 3
lease_until: 2026-10-22T01:56:07Z
releases: 1
extended: 
---
## Goal
Track habits (SPEC.md §8.5).

## Acceptance criteria
- [x] Habit list with one-tap check, streaks, stats, create/edit
  - verified (2026-10-22 03:38+0200 by dell26/agent-3): npx ng test --watch=false --include='src/app/features/habits/**/*.spec.ts' (web) -> 4 files, 71 passed; habits-page.spec covers list grouped by due today with streaks + 30-day completion, one-tap check/uncheck, create/edit via habit-form.spec, archive; component-level (no browser run)
- [ ] Vitest component/service tests cover check toggle and streak display; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-22 02:15+0200 by dell26/agent-2): One-tap check on a habit with targetCount > 1 counts up by one per tap and wraps to 0 after the target; a once-a-day habit toggles 0/1. The check always targets the server's stats.today.
- ASSUMPTION (2026-10-22 02:15+0200 by dell26/agent-2): Habits page has no delete action (archive/unarchive only) and no per-day history grid: SPEC 8.5 asks for list, check, streaks and 30-day completion; delete and history are left for later.
- ASSUMPTION (2026-10-22 02:16+0200 by dell26/agent-2): web/src/locale/messages.xlf not regenerated for the new habits.* ids, like web-cal-sources: left to a dedicated i18n extract (shared file outside touches).
- ASSUMPTION (2026-10-22 03:07+0200 by dell26/agent-2): A quiet reload dropped because a change happened while it was pending is not retried (review P3, round 3): the window is one GET long and the next 409/412 or tab return reloads again; accepted as a known limitation rather than another fix round.
- release by dell26/agent-2: Implementation complete and committed (6fd1881e..e81fb715): habits page with one-tap check, streaks, 30-day stats, create/edit, archive; Vitest specs. test-writer and code-reviewer ran 3 rounds; final review (reviews/web-habits/review.md) has 0 P0-P2, 1 P3 (dropped quiet reload not retried) already accepted as a known limitation via assumption. npm --prefix web run check passes (only the pre-existing bundle budget warning). Left: (1) tasks.py verify web-habits 1 and 2 with evidence (run npm --prefix web run check, cite the habits specs); (2) write reviews/web-habits/quality.json per docs/QUALITY.md with reviewed_revision = current HEAD of the task code (code unchanged since the third review, so evidence is fresh), helper report paths, P3 CR-1 disposition = accepted known limitation, fixer ran in rounds 1-2; (3) commit, run sh scripts/check.sh, then tasks.py done web-habits. No code changes needed.
