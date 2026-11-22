---
title: Android calendar: day/week show which entries are scrolled out of view
status: in_progress
owner: dell26/agent-1
deps: [and-cal-initial-scroll]
touches: [android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/main/res/values/strings_calendar_agenda.xml,android/app/src/main/res/values-de/strings_calendar_agenda.xml]
prio: 2
lease_until: 2026-11-22T10:00:44Z
---
## Goal
In the day and week grid you cannot see that entries are above or below the visible part. Show it per day column and
let a tap bring them into view.

## Acceptance criteria
- [x] Day and week view: each day column shows a small chip at the top edge ("▲ n") when n of its timed entries are entirely
  above the visible part, and at the bottom edge ("▼ n") when entirely below; partly visible entries count as visible;
  no chip when n = 0; the chips update while scrolling
  - verified (2026-11-22 10:41+0100 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' -> HiddenEntriesTest 21/21 (above/below counts, partly visible = visible, none when 0) and HiddenEntryChipsTest 8/8 (chips per day/week column, update on scroll) pass
- [x] In the narrow week columns the chip fits (arrow + number only) on a 360dp-wide phone; in the day view it may say
  more (e.g. "2 earlier" / "1 later")
  - verified (2026-11-22 10:41+0100 by dell26/agent-1): HiddenEntryChipsTest week chip fits its column at qualifiers w360dp + hiddenChipLabel test (week '▼ 2', day '▼ 1 later') + HiddenEntryStringsTest en/de -> pass
- [x] Tapping a chip scrolls (animated) so the nearest hidden entry in that direction is in view
  - verified (2026-11-22 10:41+0100 by dell26/agent-1): HiddenEntryChipsTest tap tests (day above/below, week column) assert animated scroll to the nearest hidden entry's target offset; HiddenEntriesTest margin/clamp targets -> pass
- [x] Chips have content descriptions for TalkBack (e.g. "2 events earlier, show")
  - verified (2026-11-22 10:41+0100 by dell26/agent-1): HiddenEntryChipsTest asserts content descriptions e.g. '2 events earlier, show' (en) and German descriptions (de-rDE-w360dp-h640dp) -> pass
- [x] The visibility calculation is a pure function (entries, scroll offset, viewport height) with unit tests
  - verified (2026-11-22 10:41+0100 by dell26/agent-1): hiddenEntries(entries, scrollOffset, viewportHeight, margin) in HiddenEntries.kt is pure; HiddenEntriesTest 21 unit tests -> pass, JaCoCo 100% lines and branches of HiddenEntries.kt
- [x] New strings are in English and German (`res/values/` + `res/values-de/`)
  - verified (2026-11-22 10:42+0100 by dell26/agent-1): strings in res/values/strings_calendar_agenda.xml and res/values-de/; HiddenEntryStringsTest en-rUS and de-rDE -> 2/2 pass; lintDebug clean
- [x] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass
  - verified (2026-11-22 10:42+0100 by dell26/agent-1): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -> exit 0, 5644 tests 0 failed (final code); sh scripts/check.sh at 4133c18ee -> all checks passed

## Notes
Planned 2026-11-22 from the owner's calendar usability request; the design decisions in the criteria were confirmed by the owner.
- ASSUMPTION (2026-11-22 10:04+0100 by dell26/agent-1): Tapping a chip scrolls so the nearest hidden entry is fully in view: above -> its start at the top edge, below -> its end at the bottom edge (its start at the top if taller than the viewport); all non-all-day entries of the column count, including ones continuing from the previous day
- ASSUMPTION (2026-11-22 10:04+0100 by dell26/agent-1): Week-view chips drop the 48dp minimum touch target (columns are ~44dp on a 360dp phone); day-view chips keep it
- ASSUMPTION (2026-11-22 10:06+0100 by dell26/agent-1): Week-view chips drop the 48dp minimum touch target in both width and height (~24dp tall): a 48dp-tall tap area would cover more of the grid edge and the entries a tap reveals (review CR-2)
- ASSUMPTION (2026-11-22 10:22+0100 by dell26/agent-1): Review P3 CR-4 deferred: when the grid viewport is no taller than the 32dp chip margin (extreme landscape with many all-day rows) a chip tap may not reveal the entry; accepted as a rare edge case. P3 CR-3 (no test that the day view uses the long label) also left open
- ASSUMPTION (2026-11-22 10:31+0100 by dell26/agent-1): Supersedes the CR-4/CR-3 deferral note: both were fixed in b47fbb93e (scroll margin clamped to viewport/4 so a tiny grid still reveals the entry; per-view label choice extracted to hiddenChipLabel and tested)
- ASSUMPTION (2026-11-22 10:31+0100 by dell26/agent-1): Supersedes the scroll-target note (review CR-5): a revealed entry stops a margin from the edge (32dp, or a quarter of the grid height if smaller) so the chip does not cover it
