# Fix Report

Date: 2026-10-21

Task: and-next-today: Android Next and Today

Scope: current task. Findings CR-1..CR-6 from `reviews/and-next-today/review.md` (CR-1 and CR-2 are also TW-1 and TW-2 in `reviews/and-next-today/tests.md`). Changes stay inside the task's touches (`gtd/next`, `gtd/today`, their tests, `res/values/strings_next_today.xml`). Based on HEAD 27b65654; the changes are not committed.

## Fixed findings

### CR-1 / TW-1 — Snackbar read resources through LocalContext (lint `LocalContextResourcesRead`)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/TaskListUi.kt`

Problem:
`CompletionSnackbarEffect` used `LocalContext.current.resources`. Lint reported this as an error, which made `lintDebug` and `.taskcheck` fail.

Fix:
It now uses `LocalResources.current`. The check is not suppressed.

Validation:
- `cd android && ./gradlew lintDebug` — Pass ("No issues found.")

### CR-2 / TW-2 — Filtered count is now a plural (lint `PluralsCandidate`)

Files changed:
- `android/app/src/main/res/values/strings_next_today.xml`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextScreen.kt`

Problem:
`next_count_filtered` was a plain `<string>`. Lint reported this as an error, and a total of 1 read "1 of 1 next actions".

Fix:
`next_count_filtered` is now a `<plurals>` resource ("%1$d of %2$d next action" / "... next actions"), with the total as the quantity. `NextScreen` reads it with `pluralStringResource(R.plurals.next_count_filtered, state.total, state.tasks.size, state.total)`. The English "other" text is the same as before, so the screen tests are unchanged.

Validation:
- `cd android && ./gradlew lintDebug` — Pass
- `NextScreenTest` (part of the focused run below) — Pass

### CR-3 — Completed tasks stayed hidden forever, even after being reopened elsewhere

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/TaskCompletion.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/TodayViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/TaskCompleterTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayViewModelTest.kt`

Problem:
After a successful completion, the task id stayed in `CompletionState.hidden` for the whole life of the view model. If the task was reopened elsewhere (web, logbook), or an undo failed while the task was open on the server, Next and Today kept filtering it out even after the cache showed it as open again.

Fix:
`CompletionState` now has two parts:
- `pending`: ids whose completion or undo request is in flight. These are hidden whatever version the cache holds, as before.
- `completed`: id mapped to the version the server returned for the completion.

The new `CompletionState.hides(task)` hides a cached copy only while it is pending, or while its version is at most the completed version. That case is a list that has not reloaded yet. Once the cache holds a newer version (reopened or changed elsewhere, or after a failed undo when the reload shows it open), the task shows again and can be completed again.

A successful undo removes the id from `completed`. A failed undo only clears `pending`, so the task stays hidden while it is still completed on the server, and shows once a reload brings it back open.

Both view models filter with `done::hides`. `CompletionState.hidden` is kept as a derived property (`pending + completed.keys`), so the existing tests and their assertions are unchanged.

I used the version rather than the review's example signal ("a newer `refreshedAt`" / "the undo window ends"). The reason: a reload that fails, or an offline phase, cannot bring back a stale pre-completion copy, and the rule needs no change in `gtd/data`. The `completed` map is never pruned. It holds one UUID and Long per task completed during the view model's life, so it stays small.

New regression tests:
- `TaskCompleterTest`: "a completed task is hidden only up to the version the completion gave it"; "a task is hidden while its undo is pending, whatever its version".
- `NextViewModelTest`: "a completed task reopened elsewhere shows again once the list reloads it" (also completes it again); "after a refused undo the reloaded list shows the task as the server has it".
- `TodayViewModelTest`: "a completed task reopened elsewhere comes back to its section once the lists reload it".

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.next.*' --tests 'ch.sonensei.cockpit.gtd.today.*'` — Pass (100 tests, 0 failures; previously 95)

### CR-5 — Context map was rebuilt for every Next row

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextScreen.kt`

Problem:
`state.contexts.associateBy { it.id }` ran inside the `items` lambda, once per row.

Fix:
The map is now built once per context list with `remember(state.contexts) { ... }` before the `LazyColumn`, as `TodayContent` does.

Validation:
- `NextScreenTest` and `NextScreenInAppTest` (focused run) — Pass

### CR-6 — `toggleCreativity` was hard to read

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextViewModel.kt`

Problem:
The formatter had spread a one-line `if` over 12 lines.

Fix:
It is now a two-line expression body with the same logic as the sibling toggles: `filter.update { it.copy(creativity = if (it.creativity == creativity) null else creativity) }`. ktlint accepts it.

Validation:
- `cd android && ./gradlew ktlintCheck` — Pass
- `NextViewModelTest` "filters by energy and creativity" and "toggling a chosen value again clears only that filter" — Pass

## Not fixed

### CR-4 — A failed first load leaves Next and Today on "Loading…" forever

Status:
Out of scope

Reason:
A clean fix is not possible inside `gtd/next` and `gtd/today`. The first load runs inside `TaskRepository.observe` (`gtd/data/GtdRepositories.kt`, `cachedView`), which throws away the `RefreshResult` of its trigger-driven `refresh()`. `Cached` carries only `data`, `offline` and `refreshedAt`. After a `RefreshResult.Failed` (a non-5xx status, an unreadable body, `SQLiteException`), none of these change, so the view models cannot tell "still loading" from "load failed".

The only in-scope workaround would be for the view models to call `tasks.refresh(view)` themselves to learn the outcome. That doubles the server load on every start, because `observe` already loads once, and it duplicates `gtd/data`'s refresh responsibility.

A proper fix belongs in `gtd/data`: for example, expose the last refresh failure in `Cached`, or return the trigger results. Then a later change in `gtd/next` and `gtd/today` can show an error with a retry action. The parent worker should record an assumption or create a follow-up task touching `gtd/data`.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.next.*' --tests 'ch.sonensei.cockpit.gtd.today.*'` — Pass. 100 tests in `gtd.next` and `gtd.today`, 0 failures, 0 skipped.
- `cd android && ./gradlew lintDebug` — Pass, "No issues found."
- `cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.next.*' --tests 'ch.sonensei.cockpit.gtd.today.*'` (the combined command) — Pass on a re-run. On the first cold run, `lintAnalyzeDebugUnitTest` stopped with an internal lint error: a missing Hilt-generated file, `DaggerCaptureEntryPointsTest_HiltComponents_SingletonC.java`, while the test sources were being rebuilt in the same invocation. That is a build race, not a finding; running the tasks separately, and the combined command afterwards, passed.

Full test suite:
- Covered by `.taskcheck` (server, web and android lint plus unit tests) — Pass

Lint/type-check/build:
- `ktlintCheck`, `lintDebug` — Pass

`.taskcheck`:
- Pass (`sh scripts/check.sh` -> "== all checks passed", exit 0). The web bundle-budget warning in its output was already there and is unrelated.

Remaining unresolved findings:
1 (CR-4, out of scope: needs a change in `gtd/data`)

## Final status

Some findings remain blocked.

CR-1, CR-2, CR-3, CR-5 and CR-6 are fixed and validation passes. CR-4 needs a `gtd/data` change outside this task's touches, and the parent worker must defer it with an assumption or a follow-up task.
