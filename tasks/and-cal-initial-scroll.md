---
title: Android calendar: day/week grid opens scrolled to the earliest entry
status: todo
owner: 
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/main/res/values/strings_calendar_agenda.xml,android/app/src/main/res/values-de/strings_calendar_agenda.xml]
prio: 2
lease_until: 
releases: 2
extended: 
hold: Finished and reviewed (quality.json committed, criteria 1-4 verified, Android check passes). Only python tasks.py done is left, but .taskcheck fails on main in web task-reminders.spec.ts (date-depende
---
## Goal
The day and week grid (`CalendarScreen.kt` `TimeGrid`) always opens scrolled to 07:00 (`FIRST_VISIBLE_HOUR`). It
should open where the entries are: the earliest timed entry visible, and as many entries below it as fit.

## Acceptance criteria
- [x] Day and week view: the grid's top is the full hour at or before the earliest start of a timed entry on the shown
  day(s) (entry at 14:20 -> top at 14:00), clamped to the scrollable range (late entries near midnight scroll to the end)
  - verified (2026-11-22 09:39+0100 by dell26/agent-1): android: ./gradlew testDebugUnitTest --tests '*CalendarGridScrollTest*' -> 11 passed (entry 14:20 -> top 14*hourPx, 23:30 entry -> scroll == maxValue, week earliest across days)
- [x] Entries continuing from an earlier day (e.g. 22:00-02:00, shown from 00:00 on the next day) and all-day entries are
  ignored for this position
  - verified (2026-11-22 09:39+0100 by dell26/agent-1): CalendarGridScrollTest 'entries continuing from an earlier day and all-day entries are ignored' + 'the week ignores an entry continuing from the previous week' -> passed
- [x] No timed entry in the shown period: scroll to the current time (full hour before now) if today is shown, else 07:00
  - verified (2026-11-22 09:39+0100 by dell26/agent-1): CalendarGridScrollTest 'without a timed entry today opens at the current hour' (15:40 -> 15:00) and 'another day opens at seven' -> passed
- [x] The position is applied when the view opens and on every period change (arrows, Today, view switch, later swipe),
  once the period's first data (cache or network) is there; a background refresh/reload of the same period does not
  move the scroll position
  - verified (2026-11-22 09:39+0100 by dell26/agent-1): CalendarGridScrollTest period-change test (waits for data, arrows, view switch, same-period refresh keeps user scroll), Today with cached data then network, StateRestorationTester restore test (fails with plain remember) -> passed; swipe navigation does not exist yet, same period key covers it
- [~] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass
  - skipped (2026-11-22 09:40+0100 by dell26/agent-1): android ./gradlew ktlintCheck testDebugUnitTest lintDebug -> pass (CalendarGridScrollTest 11, CalendarScreensTest 9); sh scripts/check.sh fails only in web task-reminders.spec.ts (4 tests, date-dependent fixtures, unrelated; task web-reminders-spec-clock) -> rerun once that lands

## Notes
Planned 2026-11-22 from the owner's calendar usability request; the design decisions in the criteria were confirmed by the owner.
- ASSUMPTION (2026-11-22 09:17+0100 by dell26/agent-1): 'Full hour before now' read as the current hour floored (15:40 -> 15:00), consistent with the entry rule (14:20 -> 14:00).
- ASSUMPTION (2026-11-22 09:17+0100 by dell26/agent-1): A period is (view, first day, last day): pressing Today while today's period is already shown is not a period change and does not move the scroll position.
- release by dell26/agent-1: Implementation, tests, 2 review rounds, fixer (CR-1) and quality.json committed; criteria 1-4 verified, Android check passes. Only step left: python tasks.py done once task web-reminders-spec-clock fixes the date-dependent web spec that makes scripts/check.sh fail on main (unrelated to this task). Re-verify #5 with check.sh then run done; no code changes needed.
- release by dell26/agent-1: 2026-11-22 09:41: still blocked: web-reminders-spec-clock is todo, task-reminders.spec.ts 4 tests still fail (reproduced). Nothing else left: once that task is done, re-verify #5 with sh scripts/check.sh and run python tasks.py done; no code changes needed.
- hold by dell26/agent-1: Finished and reviewed (quality.json committed, criteria 1-4 verified, Android check passes). Only python tasks.py done is left, but .taskcheck fails on main in web task-reminders.spec.ts (date-dependent fixtures, task web-reminders-spec-clock, still todo and unclaimed). Released 3 times so far without progress. Unhold once web-reminders-spec-clock is done, then re-verify #5 with sh scripts/check.sh and run done. No code changes needed.
