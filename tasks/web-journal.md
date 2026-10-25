---
title: Journal UI
status: in_progress
owner: dell26/agent-2
deps: [p0-web-shell,journal-api,p0-e2e-harness]
touches: [web/src/app/features/journal]
prio: 3
lease_until: 2026-10-25T23:26:29Z
releases: 0
extended: 
---
## Goal
Write and browse the journal (SPEC.md §8.5).

## Acceptance criteria
- [x] 'Today' opens/creates today's entry (CodeMirror + image paste), calendar heatmap, on-this-day
  - verified (2026-10-25 23:27+0100 by dell26/agent-2): cd web && npm run e2e -- journal/journal.spec.ts (Playwright chromium against deploy/docker-compose.e2e.yml via podman-compose, real setup/login) -> 31 passed incl. e2e/journal/journal.spec.ts 'write today, paste an image, see the heatmap and on this day'
- [ ] Vitest component/service tests cover today entry creation, heatmap; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-25 22:09+0100 by dell26/agent-2) [confirmed 2026-10-26 00:09+0100 by Peter Gürtler]: Opening Today (or any day without an entry) does not create an empty entry: the first edit (text, mood or tag) creates it via PUT without If-Match. Avoids filling the heatmap with empty days.
- ASSUMPTION (2026-10-25 22:09+0100 by dell26/agent-2) [confirmed 2026-10-26 00:09+0100 by Peter Gürtler]: Today = the server's today in the user's time zone (heatmap 'to' default), not the browser clock; future dates in /journal/:date redirect to /journal. Routes: /journal (today) and /journal/:date (the deep link the search resolver already uses).
- ASSUMPTION (2026-10-25 22:46+0100 by dell26/agent-2): Review findings CR-5 (Today does not roll over at midnight in a tab left open) and CR-6 (edits in conflict state are dropped without a prompt when switching day) are P3 and left as follow-ups, not fixed in this task.
- release by dell26/agent-2: handed back by dell26/agent-2: pushes had failed (remote: error: GH006: Protected branch update failed for refs/heads/main. | remote: error: | To https://github.com/OldP…); continue from wip/web-journal
