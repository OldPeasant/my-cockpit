---
title: Journal: Today rolls over at midnight, prompt before dropping conflicting edits
status: todo
owner:
deps: [web-journal]
touches: [web/src/app/features/journal]
prio: 2
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
