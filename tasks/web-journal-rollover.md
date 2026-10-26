---
title: Journal: Today rolls over at midnight, prompt before dropping conflicting edits
status: in_progress
owner: dell26/agent-2
deps: [web-journal]
touches: [web/src/app/features/journal]
prio: 2
lease_until: 2026-10-26T09:39:02Z
---
## Goal
Follow-ups from the web-journal review (reviews/web-journal/review.md, P3): a journal page left open
past midnight still treats yesterday as "Today" (heatmap/today loaded once), and edits in conflict
state are dropped without a prompt when the user opens another day.

## Acceptance criteria
- [ ] /journal (Today) moves to the new day after midnight in the user's time zone (saving the old day first) and the heatmap includes it
- [ ] Leaving a day whose edits are in conflict state asks before dropping them (keep mine / discard / stay)
- [ ] A heatmap response that arrives after a newer load no longer overwrites the newer heatmap/today (review CR-3: `loadHeatmap()` ignores `loadSeq`)
- [ ] Vitest specs cover all three; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-26 10:14+0100 by dell26/agent-2): Midnight is detected client-side (every minute and on visibilitychange) from settings.timeZone, falling back to the browser's zone; the server's heatmap.to stays the source of 'today'. While the browser's day is ahead of the server's (clock skew, missing settings), the heatmap is re-requested once per minute until they agree.
- ASSUMPTION (2026-10-26 10:14+0100 by dell26/agent-2): At midnight a /journal page whose day is in conflict does not prompt (the user may be away): it stays on the old day by replacing the URL with /journal/<old date>. A day opened by its date is kept; only labels and the heatmap move on.
- ASSUMPTION (2026-10-26 10:14+0100 by dell26/agent-2): The conflict prompt is a MatDialog behind a canDeactivate guard (covers other days and leaving the journal); Escape/backdrop means Stay. After 'Discard my edits' was clicked in the banner, leaving no longer asks.
