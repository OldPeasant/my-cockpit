# Code Review

Date: 2026-11-22

Task: and-cal-longpress-create - Android calendar: long press in day/week/month creates an event there

Scope: round 3 (delta) on top of round 2. Round 3 reviewed revision: 9f5fb197b4043d49fdf21c5af654185e55eebd44 (`git diff b2cc45e05..9f5fb197b -- android`, one new test). Round 2: `git diff 46716d41..092f53d17 -- android` (full task diff after the fixes), with special attention to fix commit 092f53d17 (TW-1, CR-1 to CR-4 of round 1). Reviewed revision: 092f53d173bc831655475f48ef794525dabb64cf. Static review only: Gradle was not run on request of the parent worker (a test-writer runs it in parallel).

Requirements:
- tasks/and-cal-longpress-create.md (acceptance criteria and the two recorded assumptions)
- Round-1 findings: reviews/and-cal-longpress-create/review.md (CR-1 to CR-4, previous version), reviews/and-cal-longpress-create/tests.md (TW-1)
- reviews/and-cal-longpress-create/fixes.md
- AGENTS.md project notes (lint, strings, conventions), android/.editorconfig
- Existing code conventions in calendar/agenda and calendar/edit

Git range:
- Branch: main
- HEAD: 092f53d173bc831655475f48ef794525dabb64cf
- Base/start: 46716d412429c32b7a4819a9ce33353d5c003090
- Commits reviewed: 4 (2d79c7420 implementation, b72d31852 review report, 8ad34b31c tests, 092f53d17 fixes)
- Round 3: HEAD of the code 9f5fb197b4043d49fdf21c5af654185e55eebd44, start b2cc45e05 (rewritten 092f53d17, same patch), 1 code commit (9f5fb197b); later commits touch only reviews/

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Verification of round-1 findings

- TW-1 (cancelled gesture created the event): fixed. `CalendarLongPress.kt:69-76` now treats an up change that arrives already consumed in the initial pass as the synthetic ACTION_CANCEL event and calls `onCancel` instead of `onRelease`. Compose's cancel event marks the changes of pressed pointers as consumed, and no ancestor of the grid or the all-day strip consumes in the initial pass (no other `pointerInput`/initial-pass handler in calendar/agenda; the pager and `verticalScroll` work in the main pass), so a real lifted finger still reaches `onRelease`. The heuristic and its precondition are documented in the code comment. The two cancel tests from the test-writer cover grid and strip.
- CR-1 (custom actions on non-focusable nodes): fixed. `DayColumn` (`CalendarScreen.kt:1210-1244`) and each all-day cell (`:1121-1153`) now carry a localized full-date content description ("All day, " prefix on the strip so the two differ) and an unlabelled `onLongClick` semantics action that calls the same `create` as the custom action; no `mergeDescendants`, so event blocks keep their own nodes. The new test `the week grid columns and the all-day strip can be focused by a screen reader and long clicked` asserts description, long-click action, a single "New event" custom action and the created event. A real TalkBack check was not possible (recorded in fixes.md); the semantics now satisfy TalkBack's focusability rules (speaking and actionable node).
- CR-2 (all-day ghost grows the strip): fixed. The cell is a `Box` with the entry `Column` and the ghost as a `matchParentSize()` overlay (`CalendarScreen.kt:1155-1170`), so the ghost no longer contributes to the strip height; the unused `ALL_DAY_GHOST_HEIGHT` is removed. The new test compares the strip bounds before and during the press on a day with an entry.
- CR-3 (stale callbacks in `pointerInput`): fixed at the call sites. Both `DayColumn` and `AllDayStrip` read `callbacks.create` through `rememberUpdatedState` and use it in the gesture callbacks and the accessibility actions; the remaining captured values (`pressed`/`ghost` states remembered per `day`, `pxPerMinute` in the key, the haptics local) are stable for a given key. The KDoc of `longPressAt` documents the requirement for future callers.
- CR-4 (month day announces "New event" twice): fixed. `CalendarOverlayMonth.kt:303-304` sets `onLongClickLabel = null`, keeping the custom action required by criterion 6; the adjusted test asserts one custom action and an unlabelled long click. Changing that test's expectation was legitimate because the old assertion pinned the duplicate label.

## Re-check of the full diff (no new material problems)

- Gesture logic unchanged apart from TW-1; waiting phase still aborts on lift, second pointer, slop or consumption, so tap, scroll and swipe are untouched (criterion 7).
- `EventEditorViewModel.create(date, calendars, start = null, allDay = false)`: defaults keep the + button path unchanged; calendar choice shared with the + button; `newForm` all-day form uses an inclusive `endDate = date`, timed form from `startTime` lasting `NEW_EVENT_HOURS` (a 23:45 start ends next day via `plusHours`) (criteria 1, 3, 5).
- Month: `combinedClickable` with tap to open the day, long press to `create(day, null, false)` (default start as with the + button), single haptic (criterion 4).
- Strings: `calendar_new_event_here` in `values/` and `values-de/` (criterion 8). The new content descriptions reuse the existing `calendar_all_day` string and a localized `FormatStyle.FULL` date, so no new untranslated text.
- No secrets, logging or security-relevant changes.

## Round 3 delta (9f5fb197b)

- `git diff b2cc45e05 HEAD -- android` contains only the new test `a long press calls the create callback of the latest composition, not the first one` in `CalendarLongPressInteractionTest.kt:403-431`; no production code changed since round 2, so the round-2 assessment stands unchanged.
- The test is meaningful for the CR-3 fix: it recomposes with a new `CalendarCallbacks` while the day stays the same. `longPressAt` keys `pointerInput` by `day` (strip, `CalendarScreen.kt:1127`) and `day to pxPerMinute` (grid, `:1219`), so the gesture handlers are not restarted; only the `rememberUpdatedState(callbacks.create)` reads (`:1104`, `:1213`) route the press to the new sink. Removing them would make `created` non-empty and fail the test. It covers grid (08:05 snaps to 08:00 timed) and the day-view all-day strip, with expectations consistent with the existing tests at lines 271 and 393-400.
- Uses only existing helpers and imports (`mutableStateOf`, `assertTrue`, `grid`, `gridPoint`, `pressAndHold`, `lift`, `px`); no line exceeds 140 columns. Not executed here (static review on request; the parent runs Gradle).
- No new findings. The two round-2 P3 items remain open as optional cleanups.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | String comment still describes `calendar_new_event_here` as the long-press label**
    - File: `android/app/src/main/res/values/strings_calendar_agenda.xml:57`
    - Category: Quality
    - Problem: The comment reads "Accessibility action and long-press label", but after the CR-4 fix the string is no longer used as a long-click label anywhere (month `onLongClickLabel = null`, grid and strip long-click actions unlabelled); it is only the custom action label.
    - Why it matters: Translators and later maintainers get a wrong picture of where the text appears, and someone may reintroduce the label and with it the duplicate announcement.
    - Suggested fix: Change the comment to say it is the accessibility custom action label only.

- [ ] **CR-2 | Over-long KDoc line in CalendarWithEditor**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/CalendarWithEditor.kt:30`
    - Category: Quality
    - Problem: The extended KDoc line is 161 characters; the surrounding code wraps at the ktlint_official width of 140 (only a few comment lines in the whole Android source exceed it). ktlint does not fail on it, so this is consistency only.
    - Why it matters: Minor readability and consistency with the rest of the file.
    - Suggested fix: Re-wrap the KDoc paragraph to 140 columns.

## Review conclusion

No material issues found (round 3: the delta adds one valid regression test, no production change). All round-1 findings (CR-1 to CR-4) and TW-1 are resolved in 092f53d17; the two remaining P3 items are optional cleanups.
