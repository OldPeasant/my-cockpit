---
title: Web calendar: day/week grid opens scrolled to the earliest entry
status: in_progress
owner: dell26/agent-2
deps: [web-cal-hidden-indicator]
touches: [web/src/app/features/calendar/views]
prio: 2
lease_until: 2026-11-22T10:05:47Z
---
## Goal
The web calendar's day and week view (FullCalendar, `calendar-view.ts`) always opens scrolled to 07:00
(`scrollTime: '07:00:00'`). It should open where the entries are: the earliest timed entry visible, and as many entries
below it as fit. Same behaviour as the Android task `and-cal-initial-scroll`.

## Acceptance criteria
- [x] Day and week view: the grid's top is the full hour at or before the earliest start of a timed entry in the shown
  period (entry at 14:20 -> top at 14:00), clamped to the scrollable range (late entries near midnight scroll to the end)
  - verified (2026-11-22 10:50+0100 by dell26/agent-2): cd web && npx ng test --watch=false --include 'src/app/features/calendar/views/*.spec.ts' -> 162 passed; initial-scroll.spec (14:20->14:00, wall clock of zone, 22:45->23:00 clamped by FullCalendar scroll) + calendar-view.spec 'initial scroll position' (scrollToTime('14:00:00') spy). Unit/jsdom only: scroll effect in a real browser not observed
- [x] Entries continuing from an earlier day (e.g. 22:00-02:00, shown from 00:00 on the next day), all-day entries and
  overlay tasks without a time are ignored for this position
  - verified (2026-11-22 10:50+0100 by dell26/agent-2): same run -> initial-scroll.spec 'ignores entries that started before the period'; calendar-view.spec all-day + overnight-from-previous-day week -> 07:00; overlay all-day tasks filtered by allDay===false
- [x] No timed entry in the shown period: scroll to the current time (full hour before now) if today is shown, else 07:00
  - verified (2026-11-22 10:50+0100 by dell26/agent-2): same run -> initial-scroll.spec 'full hour before now when today is shown' (16:42->16:00) and '07:00 when today is not shown'
- [x] The position is applied when the view opens and on every period change (prev/next, Today, view switch, jump to a
  date/event), once the period's events are loaded; a background refresh/reload of the same period does not move the
  scroll position
  - verified (2026-11-22 10:50+0100 by dell26/agent-2): same run -> calendar-view.spec: scroll only after the period's occurrences load, again on next week and on week->day switch, not on resync/change-event refresh, not on late answer for old period, never in month/agenda (jsdom: verifies scrollToTime is called with the right time, not the visual result in a browser)
- [x] The position calculation is a pure function with unit tests
  - verified (2026-11-22 10:50+0100 by dell26/agent-2): initialScrollTime in web/src/app/features/calendar/views/initial-scroll.ts is pure; initial-scroll.spec.ts 10 tests pass (focused ng test run)
- [ ] Unit tests cover the new behavior; `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-22 from the owner's request to have the Android initial-scroll behaviour in the web app too; design as
confirmed for Android. Depends on `web-cal-hidden-indicator` only because both change `calendar-view.ts`.
- ASSUMPTION (2026-11-22 10:40+0100 by dell26/agent-2): Timed overlay tasks (due with a time) count as timed entries for the initial scroll, using the overlay entries shown when the period's occurrences arrive; the scroll waits for occurrences only, not for the overlay's task request (keeps it simple, overlay is optional).
- ASSUMPTION (2026-11-22 10:40+0100 by dell26/agent-2): Only occurrences of visible calendars count; toggling a calendar's visibility does not move the scroll (same period).
- ASSUMPTION (2026-11-22 10:50+0100 by dell26/agent-2): If loading a new period fails, the initial scroll stays pending and applies on the next successful answer for that period (retry or background refresh) (review CR-2, accepted).
