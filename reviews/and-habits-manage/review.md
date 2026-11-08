# Code Review

Date: 2026-11-08

Task: and-habits-manage - Android habits: create, edit, archive, incremental check

Scope: final task revision 69e81f023 against the task base fdaebde62 (`git diff fdaebde62..69e81f023`): commits 9b4790660 (implementation), 029851306 (test-writer tests and first review), 69e81f023 (fixes for CR-1/CR-2 of the first review and lint fixes). This includes production code under `android/app/src/main/java/ch/sonensei/cockpit/habits/`, `android/app/src/main/res/values{,-de}/strings_habits.xml` and the committed habits tests. Uncommitted test files from the parallel test-writer run are not part of this review.

Requirements:
- `tasks/and-habits-manage.md` acceptance criteria and recorded assumptions
- `AGENTS.md` project notes (conventions, lint, strings)
- `reviews/and-habits-manage/fixes.md` (claimed resolution of the earlier CR-1/CR-2)
- Web reference `web/src/app/features/habits/habits-page.ts` (`changeSeq`, `reloadQuietly`, `nextCount`)

Git range:
- Branch: main
- HEAD: 69e81f023fcffe42d08f1bbe6b23f33d45da0c8d
- Base/start: fdaebde62e4db57e12a7c8fbb7359687d19d056a
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 1 P2, 2 P3

Commands run (read-only):
- `git merge-base HEAD origin/main`, `git log --oneline -4`, `git status --short` -> base fdaebde62, three task commits, clean tree
- `git diff --stat fdaebde62..HEAD` -> 16 files; the existing tests (`HabitsRepositoryTest`, `HabitsViewModelTest`, `HabitsScreenTest`, `HabitsRouteInAppTest`) are adapted to the new `check(habit)` signature and the new detail text. No test was deleted or skipped.
- `grep changeSeq/reloadQuietly web/.../habits-page.ts` -> web compares `changeSeq` without increasing `loadSeq` in `reloadQuietly`
- The reviewer did not run Gradle. The fixer reports that `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass. The parent records that evidence.

Earlier findings:
- CR-1 (stale conflict reload overwrites local changes): resolved for the case that was reported. `HabitsViewModel.kt:156/160` and `:216/220` increase `changeSeq` when a change or save starts and when it finishes. `startLoad(quiet = true)` (`:111`) drops the answer when `changeSeq` moved. The increment at the finish happens before `reloadAfterConflict`, so a change does not drop its own reload. Three regression tests (`HabitsViewModelManageTest.kt:607`, `:634`, `:659`) cover an archive finishing, a check-off finishing, and a check-off still in flight. The new guard adds a gap of its own, reported as CR-1 below.
- CR-2 (duplicated replace-in-list): resolved. `List<Habit>.replacing` (`HabitsViewModel.kt:256`) is used in `change()` (`:167`) and `saved()` (`:247`).

Verified without findings:
- Criterion 1: the create/edit form (`HabitEditorDialog.kt`) has name, schedule (daily / per week with a 1-7 stepper / weekday chips), times a day (1-1000) and a reminder (TimePicker, can be removed). `HabitDraft.toJson` sends all editable fields and only the chosen schedule's own field, the same as web `requestOf`. Client validation matches the server limits. Edit uses the latest loaded version for `If-Match`.
- Criterion 2: archive and unarchive go through the row overflow menu in both views, and the PATCH sends only `{"archived": ...}`. The "Archived" view is a segmented switch with its own empty text. An archived row has no check circle and `toggle` refuses archived habits.
- Criterion 3: `Habit.nextCount` (`HabitsRepository.kt:68`) counts up to the target and then goes back to 0, the same as web. The best streak (`habits_best_days`/`habits_best_weeks` plurals) appears in the row details.
- Criterion 4: `values/strings_habits.xml` and `values-de/strings_habits.xml` have the same keys, placeholders and plural items.
- Criterion 5: tests exist for the draft, the repository write calls, view-model create/edit/archive/conflict/race behavior, and Compose screen/form behavior, including German.
- No secrets. No logging of user content. Write errors carry only the status.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A conflict reload can replace the view-switch load and then be dropped, which leaves the view blank with no way to retry**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/habits/HabitsViewModel.kt:105` (`++loadSeq` also for `quiet`), `:111-113` (drop path), called from `:126-128`
    - Category: Correctness
    - Problem: `startLoad(quiet = true)` increases `loadSeq`, so a conflict reload makes any load still in flight obsolete, including the load started by `showArchived`. The new `changeSeq` guard can then drop the conflict reload's own answer too. In that case the code only clears `loading`. Example: the user taps two habits in the active view (both requests in flight) and switches to "Archived". `showArchived` clears the list (`loaded = false`) and starts load N. Habit A returns 412. That increases `changeSeq`, sets the CONFLICT message and starts quiet load N+1, so load N's answer is dropped (`seq != loadSeq`). Habit B then finishes, which increases `changeSeq` again, and load N+1's answer is dropped (`changes != changeSeq`). Final state: `archived = true`, `loaded = false`, `loading = false`, empty list. The screen shows only the CONFLICT banner, which says "the list was loaded again" and offers Dismiss but no Retry. The list and empty text stay hidden until the user switches views twice. The web reference does not have this gap because `reloadQuietly` reads `loadSeq` without increasing it (`habits-page.ts:225-230`), so a user or view load is never replaced by a quiet reload.
    - Why it matters: The screen gets stuck in a state that does not show the data or offer a retry (the review checklist requires every loading path to end in a recoverable screen). The message also says the list was reloaded when nothing was loaded. Slow networks make this timing realistic.
    - Suggested fix: Do as web does: a quiet reload should check `seq == loadSeq` without increasing `loadSeq`, so it never cancels a user or view load. Also, or as an alternative, never drop a quiet answer while `loaded == false`. Add a view-model test: a view switch, then a conflict, then a second change finishing before the conflict reload answer arrives, ending in a loaded view.

## P3 Low

- [ ] **CR-2 | A dropped conflict reload is not retried, so the conflicting habit keeps its stale version while the messages say the latest version was loaded**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/habits/HabitsViewModel.kt:111-114`; texts `habits_conflict` / `habits_save_conflict` in `res/values{,-de}/strings_habits.xml:35,66`
    - Category: Correctness
    - Problem: When the quiet reload is dropped because another change started or finished, nothing reloads afterwards. The conflicting habit keeps its old `version`, and the regression tests at `HabitsViewModelManageTest.kt:628/654/684` assert this stale state. The edit form's conflict text says "Its latest version is loaded now; save again to overwrite it", but saving again then sends the stale version and gets another 412. Example: a check of habit A is in flight while the user edits habit B and gets a conflict, and A finishes before the reload answer arrives. The next attempt usually succeeds because it triggers a fresh reload, and web behaves the same, so the impact is small.
    - Why it matters: The user sees a second, unexpected "changed elsewhere" error, and the message promises something that did not happen.
    - Suggested fix: When a quiet answer is dropped, start one more quiet reload once no change is in flight (`checking` is empty and no save is running), or remember that a reload is pending and run it after the last change finishes. Otherwise, record the web-parity behavior as a deliberate disposition.

- [ ] **CR-3 | The reminder time label uses `Locale.getDefault()` in a composable, unlike the locale fix applied to `WeekdayChoice`**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/habits/HabitEditorDialog.kt:276`
    - Category: Quality
    - Problem: The fix for `NonObservableLocale` changed `WeekdayChoice` (`:214`) to `LocalConfiguration.current.locales[0]`, but the new `ReminderField` in the same file still formats the time with `Locale.getDefault()`. Lint does not flag this call, but the time label does not recompose when the locale changes, and the file now mixes two patterns.
    - Why it matters: The behavior is inconsistent on a locale change, and the pattern the project uses for composables is not followed.
    - Suggested fix: Read the locale once from `LocalConfiguration.current.locales[0]` and pass it to `withLocale(...)`.

## Review conclusion

Material issues found; fix before completing the task. The earlier CR-1 and CR-2 are resolved for the cases that were reported, and all acceptance criteria are implemented. The new `changeSeq` guard together with the `loadSeq` increase in the quiet reload adds a path that can leave the archived (or active) view blank with no retry (CR-1, P2). Fix it, or record a deliberate disposition for it. CR-2 and CR-3 are minor.
