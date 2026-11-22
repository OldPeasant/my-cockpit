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

---

# Fix Report, Round 2

Date: 2026-11-22

Task: and-cal-hidden-indicator - Android calendar: day/week show which entries are scrolled out of view

Scope: current task; findings CR-4 and CR-3 (both P3) from the round-2 review in reviews/and-cal-hidden-indicator/review.md. Task base ca0b99ad5.

## Fixed findings

### CR-4 — With a viewport no taller than the margin, a chip tap no longer reveals the entry

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/HiddenEntries.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/HiddenEntriesTest.kt`

Problem:
Confirmed in the code: the margin was applied no matter how tall the viewport was. If the viewport was no taller than the margin, the "below" target left the viewport's bottom edge at or above the entry's start. The entry stayed hidden and every tap scrolled to the same offset.

Fix:
`hiddenEntries` now uses `val inset = min(margin, viewportHeight / 4)` instead of `margin` in both targets. The KDoc for `margin` says so. Because the inset is always smaller than the viewport, the revealed entry overlaps the viewport after the scroll in both directions. The `coerceAtLeast(0)` clamp cannot undo this: a negative "below" target would need the entry to end above the viewport height, which cannot happen for an entry below the viewport. Normal viewports are unchanged because a quarter of the grid viewport is far more than 32dp. The existing unit and Compose margin tests still pass unchanged.

Regression test: `a viewport smaller than the margin still reveals the nearest entry` uses a 20 px viewport with a 30 px margin. It checks that the "below" target is the entry's start minus 5 px and that the entry is no longer counted below at that offset. It also checks that the "above" target is the start minus 5 px and that nothing is hidden at that offset.

Validation:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'` — Pass

### CR-3 — Which label each view uses is not asserted

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/HiddenEntryChipsTest.kt`

Problem:
Confirmed: no test checked whether `HiddenChip` picks the long label (day) or the short one (week).

Fix:
First I tried the suggested `onNodeWithText("▼ 1 later", useUnmergedTree = true)` (day) and `onNodeWithText("▼ 2", useUnmergedTree = true)` (week). Both failed with "could not find any node". The chip's `Text` has `clearAndSetSemantics { }`, which removes its text from the semantics tree, unmerged tree included. That is intended: TalkBack reads the chip's content description instead. Exposing the text again would change what TalkBack announces, so I left the semantics alone. Instead, the existing `when` that picks the label moved unchanged out of the private `HiddenChip` into a new `internal` composable, `hiddenChipLabel(count, earlier, week)`. `HiddenChip` calls it. The behavior is the same.

New Compose test `the day view chips say earlier and later, the week chips show the arrow and the number only` in HiddenEntryChipsTest runs on w360dp in English. It asserts "▼ 1 later" and "▲ 2 earlier" for the day view and "▼ 2" and "▲ 1" for the week. If the day/week branch is swapped, this test fails. I removed the two failed `onNodeWithText` attempts.

Validation:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'` — Pass: BUILD SUCCESSFUL, 31 test classes, 271 tests, 0 failures, 0 errors, 0 skipped (counted from the JUnit XML results). ktlintFormat made no further changes.

Full test suite:
- Not run by the fixer (the full Android suite and scripts/check.sh are left to the parent worker)

Lint/type-check/build:
- ktlintCheck — Pass. Kotlin compile of main and unit-test sources passed as part of the run. Android lint (lintDebug) was not run.

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
