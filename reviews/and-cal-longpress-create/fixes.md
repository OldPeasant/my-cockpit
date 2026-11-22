# Fix Report

Date: 2026-11-22

Task: and-cal-longpress-create - Android calendar: long press in day/week/month creates an event there

Scope: current task; findings TW-1 (`reviews/and-cal-longpress-create/tests.md`) and CR-1 to CR-4
(`reviews/and-cal-longpress-create/review.md`), as supplied by the parent worker. Base of the fix: HEAD `f2660ce7a`.

## Fixed findings

### TW-1 - A cancelled gesture after a recognised long press creates the event as if the finger was lifted

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarLongPress.kt`

Problem:
Compose delivers ACTION_CANCEL to a suspending pointer input as a synthetic event in which every pointer is up and
already consumed. The release loop of `longPressAt` only checked `!change.pressed` and called `onRelease`, so the
editor opened after a system gesture took the press away.

Fix:
In the loop after recognition, an up change that arrives already consumed in the initial pass (nothing runs before
this modifier in that pass, so only the synthetic cancel event looks like that) now calls `onCancel` instead of
`onRelease`. A real lifted finger still calls `onRelease`. The two failing tests are unchanged and now pass.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*'` (from `android/`) - Pass (577 tests, 0 failures,
  including both cancel tests of `CalendarLongPressInteractionTest`)

### CR-1 - Grid and all-day "New event" custom actions sit on nodes TalkBack cannot focus

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarLongPressInteractionTest.kt`

Problem:
The day column and the all-day strip column had only a `customActions` semantics property: no content description
and no action that makes them screen-reader focusable, so TalkBack could not reach the action.

Fix:
The day column now has the localized FULL date (`currentLocale()`) as content description, the all-day column
"All day, " plus that date (so it differs from the grid column of the same day). Both get an `onLongClick` semantics
action that creates exactly like the custom action (grid: default start; strip: all day). No `mergeDescendants` was
added, so the event blocks inside keep their own nodes. The long-click action is deliberately unlabelled: with a
"New event" label TalkBack would announce "New event" twice, which is the problem of CR-4; the custom action carries
the label, the same as on the month day. New test
`the week grid columns and the all-day strip can be focused by a screen reader and long clicked` asserts the content
description, the presence of the long-click action without a duplicate label, exactly one "New event" custom action,
and that invoking the long-click action creates the expected event, for a week grid column and an all-day column. A
check with TalkBack on a device or emulator was not possible here.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*'` - Pass
- Full suite `./gradlew testDebugUnitTest` - Pass (no existing test that looks nodes up by content description or text
  broke)

### CR-2 - All-day ghost grows the strip and shifts the hour grid while the finger is down

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarLongPressInteractionTest.kt`

Problem:
The ghost was an extra 20dp layout child of the day's all-day column, so the strip (intrinsic height) grew while
pressed when the day had entries.

Fix:
The day's all-day cell is now a `Box` (same modifiers, tag and semantics as the former `Column`) holding the entry
`Column` and, while pressed, the ghost with `matchParentSize()` drawn over it; it no longer takes part in the height.
The now unused `ALL_DAY_GHOST_HEIGHT` was removed. New test
`the all-day ghost is drawn over the entries, so the strip keeps its height while pressed` (a day with an all-day
entry) asserts the strip's bounds are identical before and during the press.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*'` - Pass

### CR-3 - Long-press callbacks are captured once by `pointerInput` without `rememberUpdatedState`

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarLongPress.kt` (KDoc only)

Problem:
`pointerInput(key)` keeps the first composition's lambdas, which closed over that composition's `callbacks.create`.

Fix:
`DayColumn` and `AllDayStrip` read `callbacks.create` through `rememberUpdatedState` and call that in the gesture
callbacks (and the accessibility actions). The other captured values are already safe: the ghost/pressed states are
remembered per day (the key), `pxPerMinute` is part of the grid key, the haptics are a composition local. The KDoc of
`longPressAt` now says callers pass changing values through `rememberUpdatedState`. A `composed` or `Modifier.Node`
rewrite was not done: the call-site fix is smaller and the project uses neither elsewhere.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*'` - Pass
- `./gradlew ktlintCheck lintDebug` - Pass

### CR-4 - Month day exposes "New event" twice to accessibility services

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarOverlayMonth.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarLongPressInteractionTest.kt`

Problem:
`combinedClickable(onLongClickLabel = newEvent)` and the "New event" custom action announced the same action twice.

Fix:
As decided by the parent worker (criterion 6 names a custom action on month days): the custom action stays and
`onLongClickLabel` is now `null`. The long press itself is unchanged. The test formerly named
`a month day offers New event, and its long click is labelled the same` is now
`a month day offers New event once, as a custom action, and as its long click`: it asserts exactly one "New event"
custom action and an unlabelled long-click action, and still runs the custom action. The expectation was changed
because the old one asserted the duplicate label this finding removes.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*'` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `./gradlew ktlintFormat` (from `android/`) - Pass, no further changes needed
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*'` - Pass: 55 classes, 577 tests, 0 failures,
  0 errors; `CalendarLongPressInteractionTest` 20 tests, 0 failures

Full test suite:
- `./gradlew testDebugUnitTest` (from `android/`) - Pass: 5714 tests, 0 failures, 0 errors

Lint/type-check/build:
- `./gradlew ktlintCheck lintDebug` (from `android/`) - Pass (BUILD SUCCESSFUL)

`.taskcheck`:
- Not run by the fixer (`sh scripts/check.sh` covers server and web as well); the parent worker runs it before `done`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
