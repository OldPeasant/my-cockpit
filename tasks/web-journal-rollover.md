---
title: Journal: Today rolls over at midnight, prompt before dropping conflicting edits
status: in_progress
owner: dell26/agent-2
deps: [web-journal]
touches: [web/src/app/features/journal]
prio: 2
lease_until: 2026-10-26T10:09:18Z
---
## Goal
Follow-ups from the web-journal review (reviews/web-journal/review.md, P3): a journal page left open
past midnight still treats yesterday as "Today" (heatmap/today loaded once), and edits in conflict
state are dropped without a prompt when the user opens another day.

## Acceptance criteria
- [x] /journal (Today) moves to the new day after midnight in the user's time zone (saving the old day first) and the heatmap includes it
  - verified (2026-10-26 10:37+0100 by dell26/agent-2): cd web && npx ng test --watch=false --include='src/app/features/journal/**/*.spec.ts' -> 6 files, 138 passed; journal-page.spec 'midnight' (fake Date+setInterval, DAY_CHECK_MS and visibilitychange): /journal saves the old day first (PUT If-Match), then shows 2026-10-26 as Today with the reloaded heatmap containing it; journal-dates.spec dayIn: Europe/Zurich vs UTC vs America/New_York day boundaries
- [x] Leaving a day whose edits are in conflict state asks before dropping them (keep mine / discard / stay)
  - verified (2026-10-26 10:37+0100 by dell26/agent-2): same journal spec run -> 138 passed; 'leaving a conflict' specs: canDeactivate guard asks LeavePrompt; stay keeps URL and edits, discard opens the other day without PUT, keep-mine GET+PUT If-Match then leaves, failed keep-mine stays, leaving the journal asks too; leave-dialog.spec: three buttons, Escape/backdrop = stay
- [x] A heatmap response that arrives after a newer load no longer overwrites the newer heatmap/today (review CR-3: `loadHeatmap()` ignores `loadSeq`)
  - verified (2026-10-26 10:37+0100 by dell26/agent-2): same journal spec run -> 138 passed; 'opens only the day chosen last when the route changes before the first heatmap arrived': newer heatmap answered first, older one (to=2026-10-24) answered last is ignored (2026-10-19 stays marked, today stays 2026-10-25); overlapping day-check spec: newest heatmap wins
- [x] Vitest specs cover all three; `cd web && npm run check` passes
  - verified (2026-10-26 10:37+0100 by dell26/agent-2): Vitest specs above cover all three; cd web && npm run check -> lint clean, 176 files / 3713 tests passed, ng build ok (only the pre-existing initial bundle budget warning)

## Notes
- ASSUMPTION (2026-10-26 10:14+0100 by dell26/agent-2): Midnight is detected client-side (every minute and on visibilitychange) from settings.timeZone, falling back to the browser's zone; the server's heatmap.to stays the source of 'today'. While the browser's day is ahead of the server's (clock skew, missing settings), the heatmap is re-requested once per minute until they agree.
- ASSUMPTION (2026-10-26 10:14+0100 by dell26/agent-2): At midnight a /journal page whose day is in conflict does not prompt (the user may be away): it stays on the old day by replacing the URL with /journal/<old date>. A day opened by its date is kept; only labels and the heatmap move on.
- ASSUMPTION (2026-10-26 10:14+0100 by dell26/agent-2): The conflict prompt is a MatDialog behind a canDeactivate guard (covers other days and leaving the journal); Escape/backdrop means Stay. After 'Discard my edits' was clicked in the banner, leaving no longer asks.
