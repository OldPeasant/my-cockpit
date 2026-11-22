# Code Review

Date: 2026-11-22

Task: and-cal-hidden-indicator - Android calendar: day/week show which entries are scrolled out of view

Scope: round 2, final code of the task: `git diff abb01ddce..d6b18ac5e -- android` (focus on the CR-1 fix, `git diff 3557cffc0..d6b18ac5e -- android`)

Requirements:
- tasks/and-cal-hidden-indicator.md acceptance criteria and recorded assumptions (scroll target rules; week chips drop the 48dp minimum in width and height)
- AGENTS.md project notes (lint, i18n en + de, conventions)
- Round-1 review and reviews/and-cal-hidden-indicator/fixes.md
- Existing CalendarScreen.kt / CalendarModels.kt conventions (HOUR_HEIGHT, GUTTER, visibleEnd)

Git range:
- Branch: main
- HEAD: d6b18ac5e
- Base/start: abb01ddce9ee5d5330b912df68858c66a1693fc9
- Commits reviewed: 4 (3557cffc0 implementation, 3f546f72b round-1 review report, 480c05954 round-1 tests and report, d6b18ac5e CR-1 fix); no uncommitted changes

Summary: 0 P0, 0 P1, 0 P2, 2 P3

Gradle was not run by the reviewer (a test-writer runs it concurrently); findings are from reading the code. Round 1 referred to the pre-rebase hashes d765ccba1 / da0ac6667; the implementation is the same code as 3557cffc0.

## Round-1 findings and status

- CR-1 (P2, revealed short entry under the chip): resolved. `hiddenEntries` takes a `margin` (px); above target = `top - margin`, below target = `min(top - margin, bottom + margin - viewport)`, both rounded and clamped at 0. `HiddenEntryChips` passes `HIDDEN_ENTRY_MARGIN` (32dp) via `roundToPx()` and includes it in the `remember` key. The visible chip is about 26dp tall at the edge (labelSmall plus 2dp text padding plus 2dp outer padding), so a revealed entry now starts below it. Counting is unchanged. Covered by three new unit tests and updated Compose tap tests (day above/below, week column below). One residual edge case is CR-4 below.
- CR-2 (P3, week chip touch target height): resolved by decision. The task now records the assumption that week chips drop the 48dp minimum in both width and height; no code change, which is consistent with CR-1.
- CR-3 (P3, visible labels not asserted): partly resolved. HiddenEntryStringsTest (added in 480c05954) asserts the label resources in English and German ("▼ 2", "▲ 1 earlier", "▼ 1 später", ...). Which resource `HiddenChip` picks per view is still not asserted; carried over as CR-3 below at P3.

## What was checked in round 2 and found correct

- Margin arithmetic: for a normal viewport the revealed entry is fully visible after the tap and no longer counted in that direction; the taller-than-viewport case falls back to its start a margin below the top edge; `coerceAtLeast(0)` keeps the target at or above 0 and ScrollState clamps the upper end (an entry ending at 24:00 cannot get the bottom margin, but then nothing remains below, so no chip covers it).
- In the day view the chip keeps a 48dp touch area (plus 2dp padding), so the top or bottom roughly 18dp of a revealed entry under the chip's horizontal span is still inside the chip's touch area; visually it is clear. This is minor and not reported as a finding.
- Tests: new unit tests cover margin targets, negative clamp, tall entry with margin, unordered input, overnight parts, middle day of multi-day entries, one-px edges and rounding. New Compose tests cover a week column tap, overnight entries in two columns, no chips when everything is visible, and German descriptions. The week tap test moved the second entry to 23:00 so it stays entirely below with the margin; the arithmetic in its comment (32dp is 40 minutes at 48dp per hour) is correct.
- The remaining criteria (chip per column, update while scrolling via `derivedStateOf`, animated scroll, TalkBack descriptions, en + de strings, week chip width on w360dp) are unchanged since round 1 and still hold.

## P3 Low

- [ ] **CR-4 | With a viewport no taller than the margin, a chip tap no longer reveals the entry**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/HiddenEntries.kt:65`
    - Category: Correctness
    - Problem: The margin is applied regardless of the viewport height. For "below", the target is at most `top - margin`, so after the scroll the viewport bottom is `top - margin + viewport`; when the viewport is no taller than the margin that is still at or above the entry's start, the entry stays counted as below, the chip count does not change and every further tap scrolls to the same offset. For "above", the entry ends up counted as below instead. Before the CR-1 fix (margin 0) the target always revealed the entry. The grid viewport is the space left under the unbounded all-day strip, so it can become very small (landscape phone with several all-day entries or task marks), though below about 32dp the grid is barely usable anyway.
    - Why it matters: A regression introduced by the fix in an edge case: a tap that silently does nothing, contrary to criterion 3.
    - Suggested fix: Clamp the effective margin to less than the viewport, for example `val inset = min(margin, viewportHeight / 4)` inside `hiddenEntries`, and add a unit test with a viewport smaller than the margin asserting that the nearest entry becomes visible.

- [ ] **CR-3 | Which label each view uses is not asserted**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/HiddenEntryChipsTest.kt:83`
    - Category: Tests
    - Problem: HiddenEntryStringsTest checks the resource texts, and the w360dp width check would probably catch the long day label in the week, but no test checks that the day view chip shows "▼ 1 later" (the `when` in `HiddenChip`, CalendarScreen.kt:887). A swap to the short label in the day view would pass all tests.
    - Why it matters: Criterion 2 distinguishes the week and day labels; the day-view branch is untested.
    - Suggested fix: In a Compose test, assert the text with `onNodeWithText("▼ 1 later", useUnmergedTree = true)` in the day view and `onNodeWithText("▼ 2", useUnmergedTree = true)` in the week.

## Review conclusion

No material issues found. CR-1 is resolved; CR-2 is resolved by a recorded assumption. CR-4 and CR-3 are optional low-priority improvements: CR-4 is a small, cheap hardening of the CR-1 fix, and CR-3 is a test gap.
