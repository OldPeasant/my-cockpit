# Code Review

Date: 2026-11-22

Task: and-cal-hidden-indicator - Android calendar: day/week show which entries are scrolled out of view

Scope: task commit da0ac6667 against base d765ccba1 (git diff d765ccba1..da0ac6667). The commit was later rebased onto the board's assume commits as ea0b43d52; `git diff da0ac6667 ea0b43d52 -- android` is empty, so the reviewed code is identical.

Requirements:
- tasks/and-cal-hidden-indicator.md acceptance criteria and recorded assumptions (week chips drop the 48dp minimum; scroll target rules)
- AGENTS.md project notes (lint, i18n en + de, conventions)
- Existing CalendarScreen.kt / CalendarModels.kt conventions (HOUR_HEIGHT, GUTTER, visibleEnd, MIN_ENTRY_MINUTES)

Git range:
- Branch: main
- HEAD: ea0b43d52e8c8ee764d28996d5318ece39bf52cd (rebased copy of da0ac6667)
- Base/start: d765ccba1e9588bf0f42410a019ce618eeff2cb3
- Commits reviewed: 1 code commit (plus 2 board-only assume commits)

Summary: 0 P0, 0 P1, 1 P2, 2 P3

Gradle was not run by the reviewer (a test-writer runs it concurrently); findings are from reading the code.

## What was checked and found correct

- Visibility function `hiddenEntries` (HiddenEntries.kt) is pure (entries, scroll offset, viewport height, px per minute), skips all-day entries, uses the drawn height (`visibleEnd`, so the 20-minute minimum is honoured), treats an entry touching an edge as hidden and a partly visible one as visible, and returns NONE before the viewport is measured. Its scale matches the grid: DayColumn positions entries with `HOUR_HEIGHT * minute / 60f`, the chips use `HOUR_HEIGHT.toPx() / 60f`.
- Overlay geometry: the chip Row uses `matchParentSize()` of the viewport Box and the same `GUTTER` start padding and `weight(1f)` columns as the grid, so each chip sits on its own column at the viewport's top and bottom edge. The overlay Row has no pointer input, so it does not block taps or scrolls outside the chips.
- Recomposition while scrolling: `remember(entries, scroll, pxPerMinute) { derivedStateOf { ... } }` reads `scroll.value` and `scroll.viewportSize` inside the derived state; the result is a data class, so the column's chips recompose only when a count or target changes, not every scroll frame. The per-frame cost is one O(n) pass per column, which is negligible. `entries` comes from the remembered `entriesByDay` map, so the remember key is stable.
- Tap: `scope.launch { scroll.animateScrollTo(target) }` is animated; repeated taps are serialized by the ScrollState mutator (a new animation cancels the old one); out-of-range targets are clamped by ScrollState.
- TalkBack: the Surface is clickable with role Button, the text's own semantics are cleared and the merged node carries the plural content description ("2 events earlier, show" / "2 Termine früher, anzeigen"). Test tags are per day.
- Labels: week uses `calendar_hidden_short` (arrow + number); day uses "▲ n earlier" / "▼ n later" plurals. All five resources exist in values/ and values-de/ with matching format arguments.
- The week chip width is asserted against (360 - 48) / 7 dp in a w360dp Robolectric configuration.

## P2 Medium

- [ ] **CR-1 | After a tap, the newly revealed short entry can sit under the next chip**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/HiddenEntries.kt:62`
    - Category: Correctness
    - Problem: The scroll target puts the nearest entry flush against the edge (above: its start at the top edge; below: its end at the bottom edge). When more entries remain hidden in that direction, the chip for that direction is still drawn at exactly that edge. The week chip is about 24dp tall (labelSmall text plus 2dp + 2dp padding) and about 35dp of a 44dp column wide; a short entry (the 20-minute minimum is 16dp at 48dp per hour, a 30-minute one is 24dp) ends up almost entirely behind the chip. In the day view the chip keeps a 48dp touch area, so the top or bottom 48dp of the revealed entry is also not tappable in the column's centre.
    - Why it matters: The criterion says a tap brings the nearest hidden entry "in view". Numerically it is inside the viewport, but in the week view with several entries in one direction the user taps, the count drops by one, and the entry they were shown is hidden under the chip they just tapped. The tests do not catch this because they only check scroll offsets.
    - Suggested fix: Leave room for the chip: pass an edge inset (the chip height in px, or about 32dp) to `hiddenEntries` or apply it at the call site. Subtract it from the "above" target and add it to the "below" target, clamped as before, and extend the unit test for the targets accordingly. The visibility counting itself can stay as is.

## P3 Low

- [ ] **CR-2 | Week chips lose the 48dp minimum in both directions although only the width is constrained**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt:893`
    - Category: Quality
    - Problem: `LocalMinimumInteractiveComponentSize provides Dp.Unspecified` in the week drops the minimum touch target in both width and height, so the week chip's touch target is about 24dp tall. Only the column width (about 44dp) prevents the 48dp target. The assumption recorded for the task covers dropping the minimum, but height was not constrained.
    - Why it matters: A small, edge-hugging tap target on a 360dp phone is hard to hit and falls below the Android accessibility guideline (48dp). The height could be kept without affecting how the chip fits the column.
    - Suggested fix: In the week, keep a 48dp-tall touch area with an unconstrained width. For example, add `heightIn(min = 48.dp)` around a transparent clickable area that contains the visual chip, aligned to the edge. Weigh this against CR-1, because a taller touch area covers more of the grid. Otherwise, extend the recorded assumption to say the height was dropped deliberately.

- [ ] **CR-3 | Visible chip labels are not asserted**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/HiddenEntryChipsTest.kt:133`
    - Category: Tests
    - Problem: The Compose tests assert content descriptions, positions and width, but never the visible text. Nothing checks that the week shows only "▼ 2" (criterion 2's "arrow + number only") or that the day view shows "▼ 1 later". A swapped branch in `HiddenChip`'s `when` (for example, the day label in the week) would still pass the width check on a short count only by luck, and would not be caught in the day view at all.
    - Why it matters: Criterion 2 is about the label content per view, and it is currently only indirectly covered.
    - Suggested fix: Assert the text with `useUnmergedTree = true` on the chip's Text (or `onNodeWithText("▼ 2", useUnmergedTree = true)`), checking both the week label and the day label.

## Review conclusion

Material issues found; fix before completing the task. Most important is CR-1 (P2). It is not a blocker if the parent decides the edge-flush target is acceptable and records that, but the fix is small. CR-2 and CR-3 are optional improvements.
