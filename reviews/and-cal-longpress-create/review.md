# Code Review

Date: 2026-11-22

Task: and-cal-longpress-create - Android calendar: long press in day/week/month creates an event there

Scope: `git diff 8c484d19..4fae7d05` (implementation commit 4fae7d052 only; static review, Gradle not run on request of the parent worker because another process uses it; the parallel test-writer changes are not part of this scope)

Requirements:
- tasks/and-cal-longpress-create.md (acceptance criteria and the two recorded assumptions)
- AGENTS.md project notes (conventions, lint, strings)
- Existing code conventions in calendar/agenda and calendar/edit

Git range:
- Branch: main
- HEAD: 4fae7d052413902daa52471c83b0dc6fcdffe143
- Base/start: 8c484d190a8b2bff5a2ed3c26e3efd89bcf5bf0b
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 1 P2, 3 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Grid and all-day "New event" custom actions sit on nodes TalkBack cannot focus**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt:1210` (DayColumn) and `:1131` (AllDayStrip column)
    - Category: Correctness
    - Problem: The custom action is attached with a plain `semantics { customActions = ... }` to the day column `BoxWithConstraints` and to the all-day `Column`. Neither node has a content description, text, a click/long-click action or `mergeDescendants`, so Compose does not report it as screen-reader focusable and TalkBack's focus heuristics (actionable or speaking node) skip it. TalkBack only offers the actions of the focused node, so a TalkBack user can focus the event blocks inside the column (which do not carry the action) but never the column itself; on a day without entries there is nothing to focus at all. Compose tests using `performCustomAccessibilityAction` on the test tag will pass regardless, so a test does not prove this works. The month day is fine (it is clickable and has a content description).
    - Why it matters: Acceptance criterion 6 asks that grid and all-day strip offer a matching accessibility action; as written it exists in the semantics tree but is unreachable for the main assistive technology, so screen-reader users still can only create events with the + button.
    - Suggested fix: Make the two nodes focusable for accessibility, e.g. add a `contentDescription` (the day's date, like the month day) and an `onLongClick(label = newEvent) { ... }` semantics action next to `customActions` (a long-click action makes the node actionable for TalkBack and also gives "double-tap and hold"), and confirm on a device or emulator with TalkBack, or at least assert in a Compose test that the node has a long-click action / content description.

## P3 Low

- [ ] **CR-2 | All-day ghost grows the strip and shifts the hour grid while the finger is down**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt:1142`
    - Category: Quality
    - Problem: The ghost is inserted as an extra 20dp child at the top of the day's column. The strip's height is `IntrinsicSize.Min` of its columns, so when the pressed day already has all-day entries or task chips (content taller than the 24dp left inside the 28dp minimum), the strip grows by the ghost's height during the press and everything below it, including the hour grid, jumps down, and the entries under the finger move.
    - Why it matters: Visible layout jump during the gesture; only cosmetic, the created event is correct.
    - Suggested fix: Draw the ghost as an overlay of the column (e.g. a `Box` around the column with the ghost `matchParentSize`, or `drawBehind`) instead of a layout child.

- [ ] **CR-3 | Long-press callbacks are captured once by `pointerInput` without `rememberUpdatedState`**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarLongPress.kt:39` (used at `CalendarScreen.kt:1120` and `:1199`)
    - Category: Quality
    - Problem: `pointerInput(key)` only restarts when `key` (`day`, or `day to pxPerMinute`) changes, so the `onPress`/`onRelease`/`onCancel` lambdas from the first composition stay in use; they close over that composition's `callbacks`. Today this is harmless because `CalendarWithEditor` passes a lambda that reads the editor and `uiState.value` at call time, but any caller passing a `create` that captures changing state would silently get stale behavior.
    - Why it matters: Latent stale-closure bug, a known Compose pitfall; `combinedClickable` used in the month view does not have it.
    - Suggested fix: Inside `longPressAt`, wrap the callbacks with `rememberUpdatedState` (making it a `composed`/`Modifier.Node` or taking the state holders), or include `callbacks.create` in the key.

- [ ] **CR-4 | Month day exposes "New event" twice to accessibility services**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarOverlayMonth.kt:303` and `:307`
    - Category: Quality
    - Problem: `combinedClickable(onLongClickLabel = newEvent)` already adds a labelled long-click action that calls the same handler, and the `customActions` entry adds a second "New event" action, so TalkBack announces "double-tap and hold to New event" and lists "New event" again in the actions menu.
    - Why it matters: Redundant announcement; minor.
    - Suggested fix: Keep one of the two (the criterion names a custom action, so keep that and drop `onLongClickLabel`, or keep both deliberately and record it).

## Notes (verified, no finding)

- Gesture: observing in `PointerEventPass.Initial` with `requireUnconsumed = false` lets the grid see a press that starts on a clickable `EventBlock`; before the timeout nothing is consumed, so a tap still reaches the child's `clickable` and opens the event; after recognition every change is consumed in the Initial pass, so the child's click is cancelled (consumed move/up) and neither `verticalScroll` nor the `HorizontalPager` start a drag. A move beyond `touchSlop` or a second pointer before the timeout aborts the long press, leaving scroll and swipe untouched. `withTimeoutOrNull` resolves to the `AwaitPointerEventScope` member.
- Slot rounding: `slotStart` floors to the minute and then to 15 minutes, clamped to 00:00..23:45; the press position is local to the 24-hour column, so the scroll offset is already included. A 23:45 start ends on the next day, which `newForm` handles (`endDate` from `start.plusHours`).
- `IntrinsicSize.Min` in `AllDayStrip`: the strip's content (`Text`, `EventBlock`, `TaskChip`) contains no `SubcomposeLayout`/`BoxWithConstraints`, so intrinsic measurement is safe; `heightIn(min)` contributes to the intrinsic height, so the 28dp minimum applies.
- `newForm`: the all-day form uses an inclusive `endDate = date`, matching `editForm`; the + button path (`create(date, calendars)`) is unchanged; the calendar choice is shared with the + button.
- Haptics: performed once on recognition in grid and strip; the month view disables the built-in haptic and performs its own, so it fires once.
- Strings: `calendar_new_event_here` exists in `values/` and `values-de/`.
- Tests: the implementation commit contains no tests; criterion 9 depends on the test-writer's parallel work, which is outside this scope.

## Review conclusion

Material issues found; fix before completing the task.
