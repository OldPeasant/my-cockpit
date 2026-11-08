# Fix Report

Date: 2026-11-08

Task: and-today-events-habits - Android Today shows events and habits

Scope: current task. The findings come from `reviews/and-today-events-habits/review.md` (CR-1 to CR-4) and `reviews/and-today-events-habits/tests.md` (observations), plus a `lintDebug` error that the parent worker reported (`app/build/reports/lint-results-debug.txt`). Changes stay inside the task `touches` (`gtd/today` main and test packages).

## Fixed findings

### LINT-1 - NonObservableLocale in EventRow

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/TodayExtras.kt`

Problem:
`lintDebug` failed with `TodayExtras.kt:127: Error: Reading locale in a non-observable way in a composable function [NonObservableLocale]`, because `ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()` is used.

Fix:
The code now uses `LocalLocale.current.platformLocale`, which is what the lint explanation recommends. The `LocalConfiguration` and `ConfigurationCompat` imports are removed. `java.util.Locale` is still imported because `eventTime` takes it as a parameter.

Validation:
- `cd android && ./gradlew lintDebug` - not run by the fixer (no Gradle). The parent must run it.

### CR-1 - Out-of-order loads and checks can overwrite newer habit and event state

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/TodayExtrasViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayExtrasTest.kt`

Problem:
`load()` wrote events and habits without any ordering. If an older load answered after a newer one, the older answer replaced the newer one. A habit list read before a check finished could also replace the checked habit with its count from before the check. In addition, `checkFailed` was cleared only by the next check and stayed visible after a successful reload (tests.md observation).

Fix:
- Events and habits each have their own load counter (`eventsSeq` and `habitsSeq`). An answer is applied only if no newer load of the same kind started after it.
- A `changeSeq` counter goes up at the start and at the end of every check, and `lastChange[habitId]` remembers its value for that habit. When a habit list arrives, any habit whose check started or ended after the list was requested keeps the version shown on screen. All other habits take the loaded value. A check still in flight writes its own result when it returns, as before.
- A successful habit reload clears `checkFailed` if no check started or ended while it was loading. That way a list requested before a failed check cannot hide the failure message right away.

New tests in `TodayExtrasTest`:
- `only the newest load's events show, also when an older load answers last`
- `only the newest load's habits show, also when an older load answers last`
- `a habit list requested before a check finished does not undo the check`
- `a habit list requested while a check is on its way keeps the checked habit`
- `a later load shows habits checked elsewhere and clears the failed check message`

All of them use `CompletableDeferred`, with no real delays.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.today.TodayExtrasTest'` - not run by the fixer. The parent must run it.

### CR-2 - All-day events from neighbouring days can show on Today

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/TodayExtrasViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayExtrasTest.kt`

Problem:
All-day occurrences were not checked against today. The server chooses them by the synced settings zone, so it can return a neighbouring day's all-day events.

Fix:
A new function `internal fun Occurrence.isOn(day: LocalDate)` keeps an all-day occurrence only when `startDate <= day < (endDate ?: startDate + 1)`, the same rule as web `isOnDate`. Timed occurrences always pass. `load()` applies it next to the visible-calendar filter.

New test: `all-day events of other days are left out`. It covers tomorrow's event, one that ended yesterday (its exclusive end is today), a multi-day trip that spans today, one without an end date, and today's holiday.

Validation:
- same TodayExtrasTest command - not run by the fixer.

### TW-OBS-3 - eventTime for a timed event covering all of today

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/TodayExtras.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayExtrasTest.kt`

Problem:
A timed event from today 00:00 to tomorrow 00:00 was shown as "00:00–00:00". The KDoc, and web `eventSpan` (`start <= from && end >= to` gives allDay), say it should be "All day".

Fix:
`eventTime` returns `null`, which the row shows as "All day", when the event starts at or before the start of today and ends at or after the start of tomorrow in the given zone.

Test: `event times leave out the side outside today` has three new assertions:
- today 00:00 to tomorrow 00:00 gives null;
- yesterday 12:00 to tomorrow 00:00 gives null;
- today 00:00 to 23:00 gives "00:00–23:00".

Validation:
- same TodayExtrasTest command - not run by the fixer.

### CR-3 - The event-tap wiring in TodayWithEditor has no test

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/TodayWithEditor.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayWithEditorTest.kt` (new)

Problem:
No test composed `TodayWithEditor`. That left three behaviours untested: a tap opening the editor, a birthday opening its contact, and the reload when the editor closes. `TodayWithEditor` also called `BirthdayContact` directly, and `BirthdayContact` needs a Hilt view model, so the birthday path could not be composed in a Robolectric test.

Fix:
- `TodayWithEditor` gets an optional `contact` slot whose default is `BirthdayContact(id, back, modifier)`. This is exactly the seam `CalendarWithEditor` already has. Production behaviour does not change.
- A new Robolectric Compose test, `TodayWithEditorTest`, is modelled on `CalendarWithEditorTest`. It uses real `TodayViewModel`, `TodayExtrasViewModel` and `EventEditorViewModel` instances over mocked repositories:
  - `a tapped event opens it in the editor, and closing the editor loads the events and habits again`: "Edit event" is shown with the event title and the editor state is open with the calendars. Close hides the editor and shows the event row again. `habits.list()` and `calendar.occurrences(...)` are called twice in total (once on open, once after close).
  - `a tapped birthday opens its contact, not the editor, and back returns to Today`: the contact slot gets the contact id, the editor stays closed, `editRepo.event` is never called, and back returns to the event list.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.today.TodayWithEditorTest'` - not run by the fixer. The parent must run it.

## Not fixed

### CR-4 - The duplicated editor callback mapping can silently drift from the calendar's

Status:
Out of scope

Reason:
The fix needs a change in `calendar/edit`, which is outside this task's `touches`. The duplication is already recorded as a task assumption. The parent instructed that it stay out of scope. A follow-up task may move the mapping into a shared function.

## Validation summary

The fixer cannot run Gradle in this environment, so none of the commands below were run by the fixer. The code was written to compile and to follow ktlint_official style: trailing commas, multi-line parameters, and lines of 140 characters or less (line length checked with awk). The parent worker must run:

Focused tests:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.today.*'` - Not run (no Gradle access)

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` - Not run (no Gradle access)

Lint/type-check/build:
- `lintDebug` (covers the NonObservableLocale fix) - Not run (no Gradle access)

`.taskcheck`:
- `sh scripts/check.sh` - Not run (no shell access to Gradle)

Remaining unresolved findings:
1 (CR-4, out of scope)

## Final status

Fixes completed, but validation is still pending: the parent worker must run the Gradle commands above, because the fixer could not run them. CR-4 stays out of scope.
