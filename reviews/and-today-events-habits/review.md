# Code Review

Date: 2026-11-08

Task: and-today-events-habits - Android Today shows events and habits

Scope: final `git diff 0f47c5801..HEAD` at HEAD 0dca142a0d63bdde9862dc350b52c610edc82d9a (task commits cb4ec48ec, 1556df62f, 134c5cfa1, a34f77661, 0dca142a0). This covers the Android `gtd/today` main and test packages, the one-line `openNext` change in `ui/CockpitShell.kt`, and `strings_next_today.xml` (en/de). Committed changes only, and the working tree was clean. The full fixed code at a34f77661 was reviewed in the previous round, with results below. This round reviews the single change since then: `git diff --stat a34f77661 0dca142a0` shows that outside `reviews/`, only `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayExtrasTest.kt` changed, with 15 added lines (one new test). No production code changed.

Requirements:
- tasks/and-today-events-habits.md (acceptance criteria and the two recorded assumptions)
- SPEC.md §5.3 (Today), docs/PARITY.md (row "Today: no events, no habits")
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- The earlier review rounds (CR-1 to CR-4) and reviews/and-today-events-habits/fixes.md
- Web reference: `web/src/app/features/gtd/today/today-events.ts` (`isOnDate`), `today-habits.ts` (load sequence)
- Android conventions: `habits/HabitsViewModel.kt`, `calendar/edit/CalendarWithEditor.kt` and its test

Git range:
- Branch: main
- HEAD: 0dca142a0d63bdde9862dc350b52c610edc82d9a
- Base/start: 0f47c5801e7f1e8b591d3c9cf3418b207dceaa66
- Commits reviewed: 5

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Round 3: the test added in 0dca142a0

`a habit list requested before a failed check keeps the failed check message` (TodayExtrasTest.kt:295-308) was checked against `TodayExtrasViewModel` (unchanged since a34f77661) under the test's `UnconfinedTestDispatcher`:
- The second `load()` requests a list that stays pending, with `changes = 0`.
- `check(water)` sets `changeSeq` to 1. The mocked check returns `Offline` at once, which sets `changeSeq` to 2 and `checkFailed = true`. The first assertion holds.
- When the list completes, line 97 keeps `checkFailed` because `changes (0) != changeSeq (2)`. Line 94 keeps the on-screen `water` because `lastChange = 2 > 0`.

The test discriminates: if a reload always cleared `checkFailed`, the second `assertTrue` would fail. It covers the `&& changes != changeSeq` clause, and with `a later load shows habits checked elsewhere and clears the failed check message` it covers both sides of that condition. The last assertion (`habits == listOf(water)`) holds on both paths, because the list and the screen hold the same habit. It adds no discrimination but does no harm, so this is not reported as a finding. The test uses fictitious data, follows the file's naming and mocking style, and the parent reports TodayExtrasTest 21/21 passing and `ktlintCheck` passing on HEAD.

This round found no new issues.

## Status of the earlier findings

- CR-1 (out-of-order loads and checks): resolved. `TodayExtrasViewModel.load()` (lines 68-101) numbers event loads and habit loads separately (`eventsSeq`, `habitsSeq`) and drops the answer of any load that a newer one replaced. `check()` bumps `changeSeq` when it starts and again when it ends, and stores the value in `lastChange[habit.id]`. A habit list keeps the on-screen habit for every habit whose check started or ended after the list was requested (line 94). `checkFailed` is cleared only by a reload during which no check started or ended (line 97). Tests: `only the newest load's events show...`, `only the newest load's habits show...`, `a habit list requested before a check finished does not undo the check`, `a habit list requested while a check is on its way keeps the checked habit`, `a later load shows habits checked elsewhere and clears the failed check message`, and, new in this round, `a habit list requested before a failed check keeps the failed check message`.
- CR-2 (all-day events from neighbouring days): resolved. `Occurrence.isOn(day)` (lines 131-136) keeps an all-day occurrence only when `startDate <= day < end`, the same rule as web `isOnDate`. It is applied at line 79 and covered by `all-day events of other days are left out`.
- CR-3 (TodayWithEditor wiring untested): resolved. `TodayWithEditorTest` composes the real view models over mocked repositories. It covers opening the editor, reloading on close, and the birthday contact slot.
- CR-4 (duplicated editor callback mapping): not fixed, and kept out of scope by a recorded task assumption (`calendar/edit` is outside `touches`). It is carried over below as P3.

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

At 0dca142a0, CR-1, CR-2 and CR-3 are resolved. CR-4 is a P3 that the task assumption keeps out of scope. The test added in this round is correct and closes the remaining `checkFailed` branch. All acceptance-criterion behaviours are present:
- events with time, title, calendar colour, and a tap that opens the editor or the birthday contact;
- due habits with a check that goes through `HabitsRepository.check` (`Habit.nextCount`);
- the empty-day "Pick a next action", wired to Next through `CockpitShell`;
- English and German strings, with all 9 new `today_*` keys and matching format arguments in both files.

The reviewer could not run Gradle. The parent reports TodayExtrasTest 21/21 passing and `ktlintCheck` passing on HEAD. The parent must still record the full `testDebugUnitTest` run and `sh scripts/check.sh` for the check criterion.
