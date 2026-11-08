# Fix Report

Date: 2026-11-08

Task: and-habits-manage - Android habits: create, edit, archive, incremental check

Scope: findings CR-1 and CR-2 of `reviews/and-habits-manage/review.md` (reviewed revision b656840c5, rebased as 7332e26f5), plus the failures in the required Android check (`ktlintCheck testDebugUnitTest lintDebug`) found in this task's own code.

## Fixed findings

### CR-1 - A conflict reload read before a later local change overwrites that change

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/habits/HabitsViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/habits/HabitsViewModelManageTest.kt`

Problem:
`startLoad` dropped a list answer only when another load had started. A conflict reload (after 404/409/412) whose answer was read before a later check, archive or save started or finished replaced the whole list, so an archived habit came back, or a check-off was undone and the next absolute count was based on it.

Fix:
Added a `changeSeq` counter, the same as web `changeSeq`/`reloadQuietly` in `habits-page.ts`. It goes up when `change()` and `save()` start and again when their answer arrives. The second increment happens before a conflict reload starts, so a failed change does not drop its own reload. `startLoad(quiet = true)`, used by `reloadAfterConflict`, drops its answer (only clearing `loading`) when `changeSeq` moved since the load began. User reloads and view switches keep their behavior, matching web `load()`.
Added three view-model regression tests: an archive finishing before a pending conflict reload answer, a check-off finishing before it, and a check-off still in flight when the answer arrives. With the guard disabled, all three fail (`31 tests completed, 3 failed`). With the fix, all pass.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.habits.HabitsViewModelManageTest'` with the guard temporarily disabled - Fail (3 new tests fail, as expected)
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` - Pass (HabitsViewModelManageTest: 31 tests, 0 failures)

### CR-2 - Duplicated, formatter-wrapped replace-in-list logic

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/habits/HabitsViewModel.kt`

Problem:
`change()` had an inline replace lambda that the formatter split over eight lines, and `saved()` had a second copy of the same logic.

Fix:
Extracted a private top-level `List<Habit>.replacing(habit: Habit)` and used it in both places.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` - Pass

### CHK-1 - Required Android check failed on this task's committed code (not caused by CR-1/CR-2)

Files changed:
- `android/app/src/test/java/ch/sonensei/cockpit/habits/HabitsViewModelManageTest.kt` (ktlint formatting only)
- `android/app/src/main/java/ch/sonensei/cockpit/habits/HabitEditorDialog.kt`
- `android/app/src/main/res/values/strings_habits.xml`
- `android/app/src/test/java/ch/sonensei/cockpit/habits/HabitsManageScreenTest.kt`

Problem:
At HEAD 0ba8487a3 the check failed for three reasons:
- `ktlintTestSourceSetCheck` reported 53 style violations in the test-writer's `HabitsViewModelManageTest.kt`.
- `lintDebug` reported `NonObservableLocale` for `Locale.getDefault()` in the composable `WeekdayChoice` (`HabitEditorDialog.kt:213`).
- `lintDebug` reported `PluralsCandidate` for `habits_progress_state` ("%1$d of %2$d today").

Fix:
- Ran `./gradlew ktlintFormat`. The changes are formatting only and no assertion changed.
- `WeekdayChoice` now reads `LocalConfiguration.current.locales[0]`, the pattern used in `CalendarScreen`, `TrashScreen` and other screens.
- Reworded the English string to "Today: %1$d of %2$d" so no word follows a number. Updated the one test that checks this accessibility state text to match. That test change only follows the new wording and does not weaken anything. The German string ("heute %1$d von %2$d") was not flagged and is unchanged.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.habits.HabitsViewModelManageTest'`: Fail with the guard disabled (3 new tests fail), proving the regression tests catch the bug. Pass with the fix.

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` - BUILD SUCCESSFUL (all habits test classes 0 failures)

Lint/type-check/build:
- Included above (ktlintCheck, lintDebug)

`.taskcheck`:
- Pass (`sh scripts/check.sh` -> "all checks passed")

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
