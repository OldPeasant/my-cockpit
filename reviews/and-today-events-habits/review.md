# Code Review

Date: 2026-11-08

Task: and-today-events-habits - Android Today shows events and habits

Scope: re-review after fixes of the final `git diff 0f47c5801..HEAD` at HEAD a34f77661bd7476e576ad6fb56e43bcc8dc8fc64 (task commits cb4ec48ec, 1556df62f, 134c5cfa1, a34f77661). This covers the Android `gtd/today` main and test packages, the one-line `openNext` change in `ui/CockpitShell.kt`, and `strings_next_today.xml` (en/de). Committed changes only. The working tree was clean when the review started. The reviewer had no shell or git access, so the files were read in full at HEAD. HEAD was read from `.git/refs/heads/main`, and the commit list comes from the parent's git status.

Requirements:
- tasks/and-today-events-habits.md (acceptance criteria and the two recorded assumptions)
- SPEC.md §5.3 (Today), docs/PARITY.md (row "Today: no events, no habits")
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- The previous review (CR-1 to CR-4) and reviews/and-today-events-habits/fixes.md
- Web reference: `web/src/app/features/gtd/today/today-events.ts` (`isOnDate`), `today-habits.ts` (load sequence)
- Android conventions: `habits/HabitsViewModel.kt`, `calendar/edit/CalendarWithEditor.kt` and its test

Git range:
- Branch: main
- HEAD: a34f77661bd7476e576ad6fb56e43bcc8dc8fc64
- Base/start: 0f47c5801e7f1e8b591d3c9cf3418b207dceaa66
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of the previous findings

- CR-1 (out-of-order loads and checks): resolved. `TodayExtrasViewModel.load()` (lines 68-101) numbers event loads and habit loads separately (`eventsSeq`, `habitsSeq`) and drops the answer of any load that a newer one replaced. `check()` bumps `changeSeq` when it starts and again when it ends, and stores the value in `lastChange[habit.id]`. A habit list keeps the on-screen habit for every habit whose check started or ended after the list was requested (line 94). The traces I checked all end correct:
  - a list requested before a check, answering after it: the check is kept;
  - a list requested during a check, answering after it: kept;
  - a list requested during a check, answering before it: the check result overwrites the list's habit, and the button stays disabled meanwhile.
  `checkFailed` is cleared only by a reload during which no check started or ended. Tests: `only the newest load's events show...`, `only the newest load's habits show...`, `a habit list requested before a check finished does not undo the check`, `a habit list requested while a check is on its way keeps the checked habit`, `a later load shows habits checked elsewhere and clears the failed check message`, `a habit list requested before a failed check keeps the failed check message`.
- CR-2 (all-day events from neighbouring days): resolved. `Occurrence.isOn(day)` (lines 131-136) keeps an all-day occurrence only when `startDate <= day < end`. `end` is `endDate` when it is after `startDate`, otherwise `startDate + 1`, the same rule as web `isOnDate`. Timed occurrences always pass. `load()` applies it at line 79. The test `all-day events of other days are left out` covers tomorrow, an occurrence whose exclusive end is today, a multi-day trip, no end date, and today.
- CR-3 (TodayWithEditor wiring untested): resolved. `TodayWithEditor` now has a `contact` slot whose default is `BirthdayContact(id, back, modifier)`. That is the same seam `CalendarWithEditor` has, and production behaviour does not change. The new `TodayWithEditorTest` composes the real view models over mocked repositories. It checks that a tap opens "Edit event" with the event title and the calendars, that close reloads both habits and occurrences (call counts go from 1 to 2), and that a birthday opens the contact slot with the right id, does not open the editor and returns to Today on back.
- CR-4 (duplicated editor callback mapping): not fixed. It stays out of scope by a recorded task assumption, because the fix needs `calendar/edit`, which is outside `touches`. It is carried over below as P3.

Other fix-round changes checked:
- `EventRow` now reads the locale through `LocalLocale.current.platformLocale`, which fixes the NonObservableLocale lint error.
- `eventTime` now returns null ("All day") for a timed event that covers all of today. Its new assertions match the KDoc and web `eventSpan`.

No new issues were found in these changes.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-4 | The duplicated editor callback mapping can silently drift from the calendar's**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/TodayWithEditor.kt:59-80`
    - Category: Quality
    - Problem: `editorCallbacks()` copies the 19-entry `EventEditorCallbacks` mapping from `CalendarWithEditor.kt` field by field. Every field of `EventEditorCallbacks` defaults to a no-op. So a callback added to the editor and wired only in `CalendarWithEditor` compiles, but it does nothing when the editor is opened from Today. The task file records this duplication as a deliberate assumption, because `calendar/edit` is outside this task's `touches`.
    - Why it matters: It is a maintenance trap that the type system will not catch.
    - Suggested fix: In a follow-up task that may touch `calendar/edit`, move the mapping into one shared function (for example `EventEditorViewModel.callbacks()`) and use it in both places.

## Review conclusion

No material issues found.

CR-1, CR-2 and CR-3 are resolved in the code at a34f77661. CR-4 is a P3 that the task assumption keeps out of scope. All four acceptance-criterion behaviours are present:
- events with time, title, calendar colour, and a tap that opens the editor or the birthday contact;
- due habits with a check that goes through `HabitsRepository.check` (`Habit.nextCount`);
- the empty-day "Pick a next action", wired to Next through `CockpitShell`;
- English and German strings, with all 9 new `today_*` keys and matching format arguments in both files.

The reviewer could not run Gradle. The parent reports that on HEAD the Today test suites (67 tests), `ktlintCheck` and `lintDebug` pass. The parent must still record the full `testDebugUnitTest` run and `sh scripts/check.sh` for AC5.

Process note: `reviews/and-today-events-habits/tests.md` describes the code before the fixes (HEAD 1556df62f, and it says TodayWithEditor is untested). Under AGENTS.md step 6, the test-writer has to run again on the final code before `done`.
