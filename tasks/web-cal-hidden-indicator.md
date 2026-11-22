---
title: Web calendar: day/week show which entries are scrolled out of view
status: in_progress
owner: dell26/agent-2
deps: []
touches: [web/src/app/features/calendar/views,web/src/locale/messages.xlf,web/src/locale/messages.de.xlf]
prio: 2
lease_until: 2026-11-22T08:45:08Z
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
