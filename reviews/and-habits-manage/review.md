# Code Review

Date: 2026-11-08

Task: and-habits-manage - Android habits: create, edit, archive, incremental check

Scope: final task revision 9194c59d4 against the task base 05a81302e (`git diff 05a81302e..9194c59d4`). Commits: 555ede064 (implementation), 0674f7466 (test-writer tests and first review), 6b378af7b (fixes for the first review), cfe6a2903 (more tests and second review), 91298724b (WIP fixes for the second review's CR-1/CR-3), 9194c59d4 (fixes for the second review: quiet reload, retry, locale). Production code is under `android/app/src/main/java/ch/sonensei/cockpit/habits/` and `android/app/src/main/res/values{,-de}/strings_habits.xml`, plus the committed habits tests. Uncommitted test files from the parallel test-writer are not reviewed.

Requirements:
- `tasks/and-habits-manage.md` acceptance criteria and recorded assumptions
- `AGENTS.md` project notes (conventions, lint, strings)
- Second review (earlier version of this file) and `reviews/and-habits-manage/fixes.md`
- Web reference `web/src/app/features/habits/habits-page.ts` (`changeSeq`, `reloadQuietly`)

Git range:
- Branch: main
- HEAD: 9194c59d4c48ca59febbd8f0e103506896974485
- Base/start: 05a81302e9745e259183803d9de0c775f11d56d1
- Commits reviewed: 6

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Commands run (read-only):
- `git log --oneline 05a81302e..9194c59d4`, `git diff --stat 05a81302e..9194c59d4`, `git diff --stat 91298724b..9194c59d4` -> 6 task commits. The last commit changes only `HabitsViewModel.kt`, `HabitsViewModelManageTest.kt` and `fixes.md`.
- Read in full: `HabitsViewModel.kt`. Read the relevant parts: `HabitEditorDialog.kt` (locale), `HabitsScreen.kt` (message banner, retry), `HabitsViewModelManageTest.kt` (conflict and race tests at lines 607-790), and web `habits-page.ts` `reloadQuietly`.
- The reviewer did not run Gradle; shell access was restricted partway through the review. `fixes.md` reports that `./gradlew ktlintCheck testDebugUnitTest lintDebug` passes, with `HabitsViewModelManageTest` at 36 tests and 0 failures. `sh scripts/check.sh` has not been run yet. The parent must record both runs with `tasks.py verify`.

Status of the second review's findings:
- CR-1 (a conflict reload replaced the view load and was then dropped, which left the view blank with no retry): resolved. `startLoad(quiet = true)` now reads `loadSeq` without increasing it (`HabitsViewModel.kt:111`), so a quiet reload never cancels a user or view load. This matches web `reloadQuietly`. The quiet answer is also no longer dropped while the view has no list (`state.value.loaded` in the guard at `:118`). Regression tests: `HabitsViewModelManageTest.kt:708` (the view load answers first, then a later change finishes, and the conflict reload is dropped, run again and the view stays loaded) and `:752` (the scenario from the review: a view switch, a conflict and a second change, ending loaded and not loading).
- CR-2 (a dropped conflict reload was not retried): resolved, and the fix goes beyond web. When a quiet answer is dropped, `startLoad` runs it again right away if no change is in flight (`:122-124`). Otherwise it sets `reloadPending` and clears `loading` (`:119-121`), and `reloadIfPending()` (`:148-150`) runs it once the last check, archive or save finishes (`:197`, `:258`). The tests at `:607`, `:640` and `:671` now check the re-run and the fresh version, and the protection against undoing a change is kept.
- CR-3 (`Locale.getDefault()` in `ReminderField`): resolved. `HabitEditorDialog.kt:271/276` uses `LocalConfiguration.current.locales[0]` with `withLocale(locale)`.

Checks on the pending-retry logic (no defects found):
- No loop. A dropped quiet answer runs again only when `changeSeq` moved. Each re-run reads the current `changeSeq`, so it drops again only after a further change by the user.
- No interleaving. Each finish block (`changeSeq++`, the `state.update` that removes the id from `checking` or clears `saving`, then `reloadIfPending`) runs without suspending on the main dispatcher. So `changing()` sees the finished change correctly, whichever comes first: the quiet answer or the change's finish.
- A conflict's own reload is not dropped. `changeSeq` increases before `reloadAfterConflict()`, so the reload captures the value that already includes it.
- The flag is cleared by every non-quiet load (`:112`) and by every conflict reload (`:139`). A view switch or user reload therefore cancels a pending retry, and the in-flight quiet answer is then dropped by `seq != loadSeq` without touching `loading`.
- Every path ends with `loading` false. The pending path clears it. The immediate re-run keeps it true until its own answer arrives, and the view-load path is unchanged.
- Two quiet reloads in flight (two conflicts) share a `seq`. The older one is dropped by the `changeSeq` guard because the second conflict increased `changeSeq` before it started its reload. So an older quiet answer cannot overwrite a newer one.
- `showArchived` closes the editor even while a save is in flight, so `changing()` can return false during that save. In the worst case this causes one extra quiet reload, which corrects itself. A save answered after the form was closed is ignored, as the test at `:843` checks.

Acceptance criteria:
- Criterion 1 (create/edit with name, schedule daily / N per week / weekdays, target per day, reminder): implemented in `HabitEditorDialog.kt` and `HabitDraft`. Earlier reviews verified this, and the code has not changed since apart from the locale fix.
- Criterion 2 (archive/unarchive and an "Archived" view): implemented through the row overflow menu and a segmented view switch. An archived habit has no check circle and cannot be checked.
- Criterion 3 (incremental check up to the target, then reset; best streak shown): `Habit.nextCount` in `HabitsRepository.kt` and the best-streak plurals in the row details. A test (`HabitsViewModelManageTest.kt:792`) covers counting from the last answer up to the target and then resetting.
- Criterion 4 (strings in English and German): the earlier review confirmed that the English and German string files have the same keys and plurals. Neither file changed after that review.
- Criterion 5 (tests and gradle/check pass): the tests cover the draft, the repository writes, the view-model create/edit/archive/conflict/race behavior and the Compose screen/form. The reviewer did not run Gradle or `scripts/check.sh` (see above). The parent must run both on the final revision and record the results.

Outside the scope of this review: `HabitsScreen.kt:366` still formats weekday names with `Locale.getDefault()`. That line was there before this task (it is a context line in the diff), so it is not reported.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. The second review's CR-1, CR-2 and CR-3 are resolved, and the new pending-retry logic in `HabitsViewModel` is correct for the orderings examined above. The parent still has to run `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` on 9194c59d4 and record them with `tasks.py verify`.
