---
title: Android Today shows events and habits
status: in_progress
owner: dell26/agent-1
deps: [and-next-filters]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/today,android/app/src/test/java/ch/sonensei/cockpit/gtd/today,android/app/src/main/res/values/strings_next_today.xml,android/app/src/main/res/values-de/strings_next_today.xml]
prio: 2
lease_until: 2026-11-08T22:18:58Z
---
## Goal
Web Today shows today's calendar events and due habits with one-tap check (`today-view.html:7`,
`today-habits.html:41`); Android Today shows tasks only.

## Acceptance criteria
- [x] Today lists today's events (time, title, calendar color; tap opens the event editor/viewer)
  - verified (2026-11-08 22:56+0100 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.today.*' -> TodayExtrasTest 21/21 (events rows: time range, title, calendar-colour dot; only visible calendars, all-day of other days filtered), TodayWithEditorTest 2/2 (tap opens event editor, birthday opens contact, close reloads)
- [x] Today lists habits due today with a check button (same check semantics as and-habits-manage, increment up to target)
  - verified (2026-11-08 22:56+0100 by dell26/agent-1): same run -> TodayExtrasTest: due-today habits listed (archived/not-due left out), check sends HabitsRepository.check (Habit.nextCount: increment to target, then reset, as and-habits-manage), busy button disabled, failure message, stale-load guards; all pass
- [x] An empty Today offers "Pick a next action" (opens Next)
  - verified (2026-11-08 22:56+0100 by dell26/agent-1): same run -> TodayExtrasTest empty-day tests: TodayTags.EMPTY shows 'Pick a next action' which calls openNext; CockpitShell wires openNext to onOpen(CockpitDestination.Next); TodayScreenTest 15/15, TodayRouteTest 2/2 still pass
- [x] New strings are in English and German (`res/values/` + `res/values-de/`)
  - verified (2026-11-08 22:56+0100 by dell26/agent-1): grep today_ android/app/src/main/res/values{,-de}/strings_next_today.xml -> 8 new keys (today_pick_next, today_events, today_event_all_day, today_habits, today_habit_check/uncheck/count/check_failed) present in both EN and DE; lintDebug (MissingTranslation) 0 errors
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 22:41+0100 by dell26/agent-1): Today: events above the task sections, habits below (as on web today-view); events/habits load when Today opens and again whenever the event editor closes, a failed load keeps what was shown (no own error banner); only habits with dueToday are listed, done ones stay with an undo tap
- ASSUMPTION (2026-11-08 22:41+0100 by dell26/agent-1): Pick a next action needs navigation: one-line change in ui/CockpitShell.kt (outside touches) passes openNext to TodayScreen; event editor callbacks are duplicated in gtd/today/TodayWithEditor.kt instead of refactoring calendar/edit (outside touches)
