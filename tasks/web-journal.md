---
title: Journal UI
status: in_progress
owner: dell26/agent-2
deps: [p0-web-shell,journal-api,p0-e2e-harness]
touches: [web/src/app/features/journal]
prio: 3
lease_until: 2026-10-25T21:43:48Z
---
## Goal
Write and browse the journal (SPEC.md §8.5).

## Acceptance criteria
- [ ] 'Today' opens/creates today's entry (CodeMirror + image paste), calendar heatmap, on-this-day
- [ ] Vitest component/service tests cover today entry creation, heatmap; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-25 22:09+0100 by dell26/agent-2): Opening Today (or any day without an entry) does not create an empty entry: the first edit (text, mood or tag) creates it via PUT without If-Match. Avoids filling the heatmap with empty days.
- ASSUMPTION (2026-10-25 22:09+0100 by dell26/agent-2): Today = the server's today in the user's time zone (heatmap 'to' default), not the browser clock; future dates in /journal/:date redirect to /journal. Routes: /journal (today) and /journal/:date (the deep link the search resolver already uses).
