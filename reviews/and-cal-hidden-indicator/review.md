# Code Review

Date: 2026-11-22

Task: and-cal-hidden-indicator - Android calendar: day/week show which entries are scrolled out of view

Scope: final round, full task code `git diff ca0b99ad5..b47fbb93e -- android`, with focus on the round-2 fix commit b47fbb93e (CR-4 margin clamp, CR-3 label extraction and test)

Requirements:
- tasks/and-cal-hidden-indicator.md acceptance criteria and recorded assumptions
- AGENTS.md project notes (lint, i18n en + de, conventions)
- Prior review rounds and reviews/and-cal-hidden-indicator/fixes.md (rounds 1 and 2)
- Existing CalendarScreen.kt / CalendarModels.kt conventions (HOUR_HEIGHT, GUTTER, visibleEnd)

Git range:
- Branch: main
- HEAD: 4133c18ee66be74b44afef65828a0bffc4a0ae91 (full-scope review at b47fbb93ef944d40ab299aa2433bf50b46c88297, same code as 44d8bbc0b; delta review of 4133c18ee below)
- Base/start: ca0b99ad5dc76f36b9caaa2812302c665ae9686d
- Commits reviewed: 6 (c1de00c67 implementation, 185238081 round-1 review report, c49f0f3b7 round-1 tests and report, 0e51e81ce CR-1 fix, 038326db8 round-2 review report, b47fbb93e CR-4/CR-3 fix); the only uncommitted change is the task file (assumption notes, owned by the parent worker)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Gradle was not run by the reviewer (a test-writer runs it concurrently); findings are from reading the code. The fixer reports a passing focused run (`ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'`, 271 tests); the full criterion-7 commands are for the parent worker to record.

## Prior findings and status

- CR-1 (P2, revealed short entry under the chip): resolved in 0e51e81ce (confirmed in round 2); still correct after the clamp.
- CR-2 (P3, week chip touch target height): resolved by a recorded assumption; unchanged.
- CR-3 (P3, which label each view uses not asserted): resolved. The `when` that picks the label moved unchanged into `internal fun hiddenChipLabel(count, earlier, week)` (CalendarScreen.kt:898); `HiddenChip` calls it (CalendarScreen.kt:883). The new Compose test in HiddenEntryChipsTest asserts "▼ 1 later" / "▲ 2 earlier" for `week = false` and "▼ 2" / "▲ 1" for `week = true`, so a swap of the branches fails. The fixer's reasoning for not using `onNodeWithText` is sound: the chip text sits under `clearAndSetSemantics`, and exposing it would change what TalkBack announces. The test does not render the day view itself, but the call site passes `week = state.view == CalendarView.WEEK` (CalendarScreen.kt:782) directly, so the remaining untested surface is trivial. `labels.take(4)` correctly tolerates recomposition.
- CR-4 (P3, viewport no taller than the margin): resolved. `val inset = min(margin, viewportHeight / 4)` (HiddenEntries.kt:64) replaces `margin` in both targets, and the KDoc documents the cap. Checked the arithmetic: since `inset < viewportHeight` whenever `viewportHeight > 0` (guarded at the top of the function), the below target `min(top - inset, bottom + inset - viewport)` puts the viewport bottom past `top`, and the above target puts `top` inside the viewport, so the revealed entry overlaps the viewport in both directions. `coerceAtLeast(0)` cannot undo this for a below entry (its `top >= viewBottom >= viewport`). For 1 to 3 px viewports the inset is 0, which is the original flush behavior and still reveals. On normal phone grids a quarter of the viewport exceeds 32dp, so behavior there is unchanged and the existing margin tests still apply. The new unit test (20 px viewport, 30 px margin) checks both targets (`start - 5`) and that the entry is no longer hidden after scrolling; its expected values are correct.

## What was checked in this round and found correct

- Integer types: `margin` and `viewportHeight` are `Int`, `viewportHeight / 4` is integer division, `kotlin.math.min(Int, Int)` is used; the subtraction from the `Float` top keeps float arithmetic before `roundToInt()`.
- `hiddenChipLabel` is a value-returning composable and follows the lowercase naming convention for such functions; `internal` visibility is the minimum needed for the test.
- No change to strings, semantics, touch-target handling, the `remember` keys or the `derivedStateOf` update path in this commit.
- Full-scope criteria (chip per column, update while scrolling, animated scroll, TalkBack descriptions, en + de strings, week chip width on w360dp, pure visibility function with unit tests) are unchanged since round 2 and still hold.

## P3 Low

- [ ] **CR-5 | The first scroll-target assumption no longer matches the code**
    - File: `tasks/and-cal-hidden-indicator.md:28`
    - Category: Quality
    - Problem: The assumption recorded at 10:04 says a tap scrolls "above -> its start at the top edge, below -> its end at the bottom edge (its start at the top if taller than the viewport)". Since the CR-1 fix the entry is kept 32dp (at most a quarter of the viewport) away from that edge. The later notes mention the CR-4 clamp but none restates the margin behavior that replaces the 10:04 rule.
    - Why it matters: Humans review assumptions with `tasks.py assumptions`; the one describing the tap behavior describes the pre-fix behavior, so a human confirming it confirms something the code does not do.
    - Suggested fix: Record a superseding assumption (`tasks.py assume`), e.g. "Supersedes the 10:04 scroll-target note: the revealed entry is kept 32dp (capped at a quarter of the grid viewport) from the edge so the chip still drawn there does not cover it". No code change.

## Delta review (4133c18ee)

Scope: `git diff 44d8bbc0b 4133c18ee -- android`. The only code change is one new unit test in `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/HiddenEntriesTest.kt:117-128` (CR-4 clamp boundary); no production code changed, so the full-scope findings above still apply unchanged. Gradle was not run by the reviewer.

Expected values checked by hand against `hiddenEntries` (HiddenEntries.kt, `inset = min(margin, viewportHeight / 4)`), scale 1 px per minute, margin 30:
- Above, entry 100-160, scroll 600: bottom 160 is at or above the top edge 600, so it is hidden above. Viewport 120 gives inset `min(30, 30) = 30`, target 70; viewport 119 gives `min(30, 29) = 29`, target 71. Both match the asserted `100 - 30` / `100 - 29`.
- Below, entry 600-900, scroll 0: top 600 is at or below the bottom edge (120 or 119), so it is hidden below; `visibleEnd` is 900 (longer than the minimum entry length, within the day). Viewport 120: `min(600 - 30, 900 + 30 - 120) = min(570, 810) = 570`; viewport 119: `min(571, 810) = 571`. Both match the asserted values and exercise the "taller than the viewport, start below the top edge" branch.
- The pair 120/119 pins the exact boundary where the quarter-viewport cap takes over and the integer-division rounding, in both directions, so changing the divisor, `min` to `max`, or dropping the cap from either target fails the test. The test name and comments describe the behavior accurately; no line is longer than the existing ones in the file.

Delta findings: none.

## Review conclusion

No material issues found. CR-1, CR-3 and CR-4 are resolved and CR-2 is resolved by a recorded assumption. CR-5 is a documentation-only note for the parent worker's assumption log. The delta commit 4133c18ee adds a correct boundary test and introduces no new findings.
