# Fix Report

Date: 2026-11-22

Task: and-cal-hidden-indicator - Android calendar: day/week show which entries are scrolled out of view

Scope: current task; finding CR-1 from reviews/and-cal-hidden-indicator/review.md (CR-2 and CR-3 dispositions as decided by the parent worker)

## Fixed findings

### CR-1 — After a tap, the newly revealed short entry can sit under the next chip

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/HiddenEntries.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/HiddenEntriesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/HiddenEntryChipsTest.kt`

Problem:
The scroll targets placed the revealed entry flush with the viewport edge (above: start at the top edge; below: end at the bottom edge). When more entries remained hidden in that direction, the chip drawn at that edge covered most of a short entry. Confirmed in the code before the change (HiddenEntries.kt, `toAbove` / `toBelow`).

Fix:
`hiddenEntries` takes a new `margin: Int = 0` px parameter. Above target = `top - margin`; below target = `min(top - margin, bottom + margin - viewport)`; both rounded and kept non-negative (`coerceAtLeast(0)`). The visibility counting is unchanged. `HiddenEntryChips` in CalendarScreen.kt passes `HIDDEN_ENTRY_MARGIN` (new `internal val`, 32.dp) converted with `roundToPx()`, and it is part of the `remember` key.

Tests: HiddenEntriesTest gains three tests (targets keep the margin while counts are unchanged; a margin never makes a target negative; an entry taller than the viewport scrolls to a margin above its start). The existing margin-free tests still hold via the default 0. HiddenEntryChipsTest now expects the targets to include the margin (day view below and above, week column below). In the week tap test, Thursday's second entry moved from 22:00 to 23:00, so it is still entirely below once the 21:00 entry ends 32dp (40 minutes at 48dp per hour) above the bottom edge. With the margin, an entry starting at 22:00 would now be partly visible.

Validation:
- `cd android && ./gradlew ktlintFormat` — Pass (no further changes needed)
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests '*HiddenEntr*' --tests '*CalendarGridScrollTest'` — Pass (BUILD SUCCESSFUL; HiddenEntriesTest 19, HiddenEntryChipsTest 7, HiddenEntryStringsTest 2, CalendarGridScrollTest 11; 0 failures, 0 errors, 0 skipped)

## Not fixed

### CR-2 — Week chips lose the 48dp minimum in both directions although only the width is constrained

Status:
Requires decision (handled by the parent worker as a recorded assumption)

Reason:
The parent worker records the deliberate drop of the 48dp minimum, height included, as a task assumption, so no code change was made. A taller touch area would also cover more of the grid, which works against CR-1.

### CR-3 — Visible chip labels are not asserted

Status:
Invalid (already covered)

Reason:
The parent worker reports that HiddenEntryStringsTest covers the visible week and day labels. It ran and passed in the focused run above (2 tests). No code change was made.

## Validation summary

Focused tests:
- `./gradlew ktlintCheck testDebugUnitTest --tests '*HiddenEntr*' --tests '*CalendarGridScrollTest'` (in android/) — Pass, 39 tests, 0 failures

Full test suite:
- Not run by the fixer (the full Android suite and scripts/check.sh are left to the parent worker)

Lint/type-check/build:
- `./gradlew ktlintFormat` and `./gradlew ktlintCheck` (in android/) — Pass; Kotlin compile of main and unit-test sources passed as part of the run

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0 requiring code changes (CR-2 is handled by an assumption, CR-3 is already covered)

## Final status

All validated findings fixed and validation passed.
