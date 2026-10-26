# Fix Report

Date: 2026-10-26

Task: and-cal-cache - Android agenda offline read cache

Scope: current task; findings CR-2 (review.md) and the test-writer note on the offline fallback (tests.md), as supplied by the parent worker. CR-1 left untouched (covered by `CalendarViewModelRoomCacheTest.kt`).

## Fixed findings

### CR-2 - A failed calendar list replaces the cached calendars with the in-memory list

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModelCacheTest.kt`

Problem:
When the occurrences loaded but `repository.calendars()` did not, `load()` stored `state.value.calendars` in the cache: empty on a first load (wiping the cached calendars), or carrying a pending, unconfirmed visibility choice.

Fix:
`load()` stores the calendar list only from an `Ok` calendar answer; otherwise it reuses the calendars of the agenda already cached (`cache.read()?.calendars.orEmpty()`). No `AgendaCache` interface change. New tests: `an agenda loaded without the calendar list keeps the cached calendars`, `an agenda loaded without the calendar list and without a cache stores no calendars`.

Validation:
- `android/gradlew -p android ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' -q` - Pass (CalendarViewModelCacheTest 15 tests, all agenda suites 0 failures)

### TW-1 - Offline fallback drops the freshly loaded calendar list

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModelCacheTest.kt`

Problem:
When the occurrences were offline but the calendar list loaded, the cache fallback returned before the loaded list was applied, so the in-memory (or cached) calendars were shown instead of the newest ones.

Fix:
The fallback applies the loaded list with `withCalendars` (keeping pending visibility choices) before `withCached`; without a loaded list it keeps the session's calendars, or the cached ones when there are none. The calendar choice moved out of `withCached`. New test: `offline events with a loaded calendar list show the cached events with the loaded calendars` (also checks the offline load leaves the cache unchanged).

Validation:
- `android/gradlew -p android ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' -q` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `android/gradlew -p android ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' -q` - Pass (18 suites, 0 failures, 0 errors; ktlintFormat changed no other files)

Full test suite:
- Not run by the fixer (`android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug` left to the parent)

Lint/type-check/build:
- `android/gradlew -p android ktlintCheck -q` - Pass

`.taskcheck`:
- Not run (parent runs `sh scripts/check.sh`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
