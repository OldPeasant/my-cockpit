---
title: Web calendar: day/week show which entries are scrolled out of view
status: in_progress
owner: dell26/agent-2
deps: []
touches: [web/src/app/features/calendar/views,web/src/locale/messages.xlf,web/src/locale/messages.de.xlf]
prio: 2
lease_until: 2026-11-22T08:50:10Z
releases: 1
extended: 
---
## Goal
In the web calendar's day and week view (FullCalendar `timeGridDay`/`timeGridWeek`, `calendar-view.ts`) the time grid
scrolls vertically, but nothing shows that entries are above or below the visible part. Same behaviour as the Android
task `and-cal-hidden-indicator`.

## Acceptance criteria
- [ ] Day and week view: each day column shows a small chip at the top edge of the scroll area when n of its timed
  entries are entirely above the visible part, and at the bottom edge when entirely below; partly visible entries count
  as visible; no chip when n = 0; the chips update while scrolling, on resize and when the period or events change
- [ ] The chip shows arrow + count ("▲ 2" / "▼ 1"); where the column is wide enough it may say more ("2 earlier" /
  "1 later"); it does not cover the all-day row or the column headers, and fits a 360px-wide mobile viewport
- [ ] Clicking a chip scrolls (smoothly) so the nearest hidden entry in that direction is in view
- [ ] Chips are buttons with an accessible label (e.g. "2 events earlier, show") and work with the keyboard
- [ ] The visibility calculation is a pure function (entry positions, scroll offset, viewport height) with unit tests
- [ ] New texts are in English and German (`messages.xlf` + `messages.de.xlf`)
- [ ] Unit tests cover the new behavior; `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-22 from the owner's request to have the Android hidden-entry indicator in the web app too; design as
confirmed for Android (per-column chips with counts, click scrolls to the nearest hidden entry).
- ASSUMPTION (2026-11-22 09:27+0100 by dell26/agent-2): Chips count only timed calendar occurrences of visible calendars; task-overlay marks are not counted (they are markers, not entries). Column/scroller found via FullCalendar's [data-date] cells (classes are hashed in v7), entry pixel positions computed from wall-clock minutes x column height (grid is 00:00-24:00).
- ASSUMPTION (2026-11-22 09:27+0100 by dell26/agent-2): Wide chip text ('2 earlier' / '2 früher') from 88px column width; narrower shows '▲ 2'. German label '2 frühere Termine, anzeigen'.
- release by dell26/agent-2: Done (committed): pure fn hidden-entries.ts (+10 unit tests, passing), DOM measuring hidden-chips.ts, wiring in calendar-view.ts/html/scss (scroll capture listener, resize, effect on events/view; chip click scrollTo smooth; aria-label buttons), i18n en+de. Calendar specs (359) and lint pass. Left: component tests for chips in calendar-view.spec.ts (fake [data-date] columns + scroller like the existing scroller() helper) and hidden-chips.ts tests; check in a real browser (Playwright, 360px viewport, chip not covering all-day row/headers, wide text); then test-writer + code-reviewer, quality.json, verify, done.
