---
title: Journal UI
status: in_progress
owner: dell26/agent-2
deps: [p0-web-shell,journal-api,p0-e2e-harness]
touches: [web/src/app/features/journal]
prio: 3
lease_until: 2026-10-25T23:41:39Z
releases: 0
extended: dell26/agent-2
---
## Goal
Write and browse the journal (SPEC.md §8.5).

## Acceptance criteria
- [x] 'Today' opens/creates today's entry (CodeMirror + image paste), calendar heatmap, on-this-day
  - verified (2026-10-25 23:27+0100 by dell26/agent-2): cd web && npm run e2e -- journal/journal.spec.ts (Playwright chromium against deploy/docker-compose.e2e.yml via podman-compose, real setup/login) -> 31 passed incl. e2e/journal/journal.spec.ts 'write today, paste an image, see the heatmap and on this day'
- [x] Vitest component/service tests cover today entry creation, heatmap; `cd web && npm run check` passes
  - verified (2026-10-26 00:24+0100 by dell26/agent-2): npm --prefix web run test:ci -- --include 'src/app/features/journal/**/*.spec.ts' -> 5 files, 97 passed (today entry creation PUT w/o If-Match, heatmap component+page); npm --prefix web run check at 164a790e8 -> pass (lint, typecheck, 171 files/3561 tests, build)

## Notes
- ASSUMPTION (2026-10-25 22:09+0100 by dell26/agent-2) [confirmed 2026-10-26 00:09+0100 by Peter Gürtler]: Opening Today (or any day without an entry) does not create an empty entry: the first edit (text, mood or tag) creates it via PUT without If-Match. Avoids filling the heatmap with empty days.
- ASSUMPTION (2026-10-25 22:09+0100 by dell26/agent-2) [confirmed 2026-10-26 00:09+0100 by Peter Gürtler]: Today = the server's today in the user's time zone (heatmap 'to' default), not the browser clock; future dates in /journal/:date redirect to /journal. Routes: /journal (today) and /journal/:date (the deep link the search resolver already uses).
- ASSUMPTION (2026-10-25 22:46+0100 by dell26/agent-2): Review findings CR-5 (Today does not roll over at midnight in a tab left open) and CR-6 (edits in conflict state are dropped without a prompt when switching day) are P3 and left as follow-ups, not fixed in this task.
- release by dell26/agent-2: handed back by dell26/agent-2: pushes had failed (remote: error: GH006: Protected branch update failed for refs/heads/main. | remote: error: | To https://github.com/OldP…); continue from wip/web-journal
- extended by dell26/agent-2: Implementation and e2e (criterion 1) done; last commit 40f964b19 adds one test (late conflict-reload failure) + latest review.md (CR-3 deferred to web-journal-rollover). Left: re-run test-writer + code-reviewer on final code (evidence stale after 40f964b19), write reviews/web-journal/quality.json (CR-1/2/3 deferred to web-journal-rollover, fixes.md exists), verify criterion 2 (web Vitest 171 files passed inside scripts/check.sh; record full 'npm run check'), then done.
