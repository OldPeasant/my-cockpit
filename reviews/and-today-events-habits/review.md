# Code Review

Date: 2026-11-08

Task: and-today-events-habits - Android Today shows events and habits

Scope: `git diff 0f47c5801..HEAD` (task commits cb4ec48ec and 1556df62f): Android `gtd/today` package, one line in `ui/CockpitShell.kt`, `strings_next_today.xml` (en/de), `TodayExtrasTest.kt`. Committed changes only; an uncommitted edit to `TodayExtrasTest.kt` present in the working tree at review time (presumably the test-writer's) was not reviewed.

Requirements:
- tasks/and-today-events-habits.md (acceptance criteria and the two recorded assumptions)
- SPEC.md §5.3 (Today), docs/PARITY.md (row "Today: no events, no habits")
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- Web reference: `web/src/app/features/gtd/today/today-events.ts`, `today-habits.ts`
- Existing Android conventions: `habits/HabitsViewModel.kt`, `calendar/edit/CalendarWithEditor.kt`, `calendar/agenda/CalendarViewModel.kt`

Git range:
- Branch: main
- HEAD: 1556df62f55a6584a84a382848eedf9de34764cc
- Base/start: 0f47c5801e7f1e8b591d3c9cf3418b207dceaa66
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 3 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Out-of-order loads and checks can overwrite newer habit and event state**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/TodayExtrasViewModel.kt:52`
    - Category: Correctness
    - Problem: `load()` starts two coroutines that write `events` and `habits` unconditionally, with no request sequence and no regard for in-flight checks. `load()` runs on every composition where the editor is closed: when Today opens, after every editor close, and after a configuration change. This leads to two races. (a) A habit list read before a check finishes can arrive after the check result (`check()`, line 76) and replace the checked habit with its old `todayCount`/`doneToday`. The next tap then computes `nextCount` from the stale count, so the tap is lost or the count goes back to zero unexpectedly. (b) Two overlapping loads (open, then a quick edit and close) can finish in reverse order, and the older one wins, which shows events from before the edit. The web Today habits (`today-habits.ts:68-95`: `seq` check and no refresh while a check runs) and the Android `HabitsViewModel` (`loadSeq`/`changeSeq`, lines 72-135) both guard against exactly this.
    - Why it matters: A tap on a habit check can be undone on screen, and a later tap then sends the wrong count. An event edit can look as if it was not saved.
    - Suggested fix: Give each load a sequence number and drop answers from older loads. Also drop (or redo) a habit load whose answer arrives while a check is in flight, or one that started before a check finished, the same way `HabitsViewModel.startLoad(quiet = true)` does. Add a unit test that holds a load until a check completes.

- [ ] **CR-2 | All-day events from neighbouring days can show on Today when the device zone differs from the synced calendar zone**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/TodayExtrasViewModel.kt:55-63`
    - Category: Correctness
    - Problem: The query uses the device zone (`clock.zone`) for the range from device midnight to the next midnight. All results from visible calendars are kept, and all-day occurrences are not filtered by their `startDate`/`endDate`. The server picks all-day occurrences by the calendar settings' time zone, so when that zone differs from the device zone the answer can include the previous or next day's all-day events. The web handles this explicitly (`today-events.ts:36-45`, `isOnDate`). `CalendarViewModel` avoids it by using the synced settings zone (lines 98, 123). Every all-day row is labelled "All day" (`TodayExtras.kt:128`), so a wrong-day event looks like a real event today.
    - Why it matters: AC1 says Today lists today's events. A user whose device zone differs from the synced setting (for example while travelling) sees tomorrow's or yesterday's all-day events as today's.
    - Suggested fix: Keep an all-day occurrence only if `startDate <= today < (endDate ?: startDate + 1)`, as web `isOnDate` does. Add a test case with an all-day occurrence for `today + 1`.

- [ ] **CR-3 | The event-tap wiring in TodayWithEditor has no test**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/TodayWithEditor.kt:22-58`
    - Category: Tests
    - Problem: `TodayExtrasTest` only checks that `TodayContent` calls `openEvent` with the tapped occurrence. Nothing tests the behaviour AC1 actually names: a tap opens the event editor (`editor.edit`), a birthday occurrence opens its contact instead, and closing the editor runs `extras.load()` again. All of that lives in `TodayWithEditor`, which no test composes. The calendar's equivalent has its own test (`calendar/edit/CalendarWithEditorTest.kt`).
    - Why it matters: AC1 ("tap opens the event editor/viewer") and AC5 ("tests cover the new behavior") depend on this wiring. A wrong callback or a missed reload would go unnoticed.
    - Suggested fix: Add a Compose test that builds `TodayWithEditor` with mocked view models (as `CalendarWithEditorTest` does). Check that tapping a normal event opens `EventEditorContent`, tapping a birthday occurrence shows the contact, and closing the editor calls `load()` again.

## P3 Low

- [ ] **CR-4 | The duplicated editor callback mapping can silently drift from the calendar's**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/TodayWithEditor.kt:59-79`
    - Category: Quality
    - Problem: `editorCallbacks()` copies, field by field, the 19-entry `EventEditorCallbacks` mapping from `CalendarWithEditor.kt:49-70`. Every field of `EventEditorCallbacks` defaults to a no-op (`EventEditorScreen.kt:61` onward). So if a callback is added to the editor and wired only in `CalendarWithEditor`, it compiles but does nothing when the editor is opened from Today. The task file records this duplication as a deliberate assumption because `calendar/edit` is outside this task's `touches`.
    - Why it matters: It is a maintenance trap that the type system will not catch.
    - Suggested fix: In a follow-up task that may touch `calendar/edit`, move the mapping into one shared function (for example `EventEditorViewModel.callbacks()`) and use it in both places.

## Review conclusion

Material issues found; fix before completing the task.

All four acceptance-criterion behaviours are present in the code:
- events with time, title, calendar colour and a tap that opens the editor or contact;
- due habits with a check that uses `Habit.nextCount` via `HabitsRepository.check`, the same semantics as the Habits screen;
- an empty day offering "Pick a next action", which opens Next through `CockpitShell`;
- new strings in English and German.

CR-1 and CR-2 are correctness defects in edge conditions, and CR-3 is a test gap for AC1/AC5. The full android check and `scripts/check.sh` named in AC5 could not be run by this reviewer; the parent worker must record that evidence.
