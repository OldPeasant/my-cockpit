---
title: Web calendar: day/week show which entries are scrolled out of view
status: in_progress
owner: dell26/agent-2
deps: []
touches: [web/src/app/features/calendar/views,web/src/locale/messages.xlf,web/src/locale/messages.de.xlf]
prio: 2
lease_until: 2026-11-22T09:10:19Z
releases: 2
extended: 
---
## Goal
In the web calendar's day and week view (FullCalendar `timeGridDay`/`timeGridWeek`, `calendar-view.ts`) the time grid
scrolls vertically, but nothing shows that entries are above or below the visible part. Same behaviour as the Android
task `and-cal-hidden-indicator`.

## Acceptance criteria
- [x] Day and week view: each day column shows a small chip at the top edge of the scroll area when n of its timed
  entries are entirely above the visible part, and at the bottom edge when entirely below; partly visible entries count
  as visible; no chip when n = 0; the chips update while scrolling, on resize and when the period or events change
  - verified (2026-11-22 09:53+0100 by dell26/agent-2): cd web && npm run e2e -- --no-deps --project=chromium --grep 'hidden-entry chips' with reviews/web-cal-hidden-indicator/browser-check.spec.ts.txt copied to web/e2e/calendar-views/ (real stack via podman compose, chromium, 1280x800 and 360x640) -> 1 passed: day+week show per-column '▲ 2'/'▼ 1' for 01:00,02:00 (above) and 22:00 (below) entries; chips update after scroll and disappear after resize to 3000px height; calendar-view.spec.ts chip tests cover period/event changes
- [x] The chip shows arrow + count ("▲ 2" / "▼ 1"); where the column is wide enough it may say more ("2 earlier" /
  "1 later"); it does not cover the all-day row or the column headers, and fits a 360px-wide mobile viewport
  - verified (2026-11-22 09:53+0100 by dell26/agent-2): same browser check (npm run e2e --grep 'hidden-entry chips', 1280x800 + 360x640) -> passed: wide columns '▲ 2 earlier'/'▼ 1 later', 360px week '▲ 2'/'▼ 1'; chip top >= all-day row bottom (no header/all-day overlap), chip right edge <= viewport width 360
- [x] Clicking a chip scrolls (smoothly) so the nearest hidden entry in that direction is in view
  - verified (2026-11-22 09:53+0100 by dell26/agent-2): same browser check (npm run e2e --grep 'hidden-entry chips', 1280x800 + 360x640) -> passed: clicking '1 earlier' scrolls (scrollTo behavior smooth) until no earlier chip remains; clicking '1 later' brings the 22:00 entry into view; unit tests (hidden-entries.spec.ts scrollTargetFor) check the nearest-entry target
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
- release by dell26/agent-2: Done+committed: impl, review fixes (CR-1..4, f86cb06e3), 23 new tests (tests.md, review.md, fixes.md committed). Browser check (reviews/web-cal-hidden-indicator/browser-check.spec.ts.txt; copy to web/e2e/calendar-views/ and run 'npm --prefix web run e2e -- --grep "hidden-entry chips"'): at 1280x800 day+week PASS (chips below all-day row, '▲ 2 earlier'/'▼ 1 later', Enter/click scroll to nearest, chips disappear). At 360x640 day view NO chips appear (line 40) - likely the mobile layout has no inner time-grid scroller (timeGridOf returns undefined) or a different scroll container; fix that. Left: fix 360px, rerun test-writer+code-reviewer on final code (code changed after review), quality.json, verify, done. Note: web unit suite has 4 failing tests in gtd/task-detail/task-reminders.spec.ts (not this task) plus a flaky 'linked event' calendar-view test; check main before done.
