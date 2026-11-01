# Fix Report

Date: 2026-11-01

Task: and-logbook - Android: Logbook (completed tasks, reopen)

Scope: current task; finding CR-1 from `reviews/and-logbook/review.md`

## Fixed findings

### CR-1 — Logbook keeps reloading all shown pages on every task change while it is not on screen

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/LogbookViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/LogbookViewModelTest.kt`

Problem:
The reload pipeline (`merge(repository.changes(), reloads).collectLatest { reload() }`) ran in `init` on
`viewModelScope`. Because of that, it ran for the whole ViewModel lifetime, even while the Logbook was off screen
(back stack or saved tab state). Every TASK change or stream reconnect then reloaded all shown pages.

Fix:
The `init` block is gone. The pipeline is now a private `reloading` flow that is combined into the `uiState`
upstream, which is shared with `SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS)`. This is the same pattern
`LiveTaskListViewModel` uses. The `reloading` flow first emits `Unit`, so `combine` can produce values, and then
runs `collectLatest { reload() }` without emitting again. Later updates come from `state`, which is still a
`combine` input, so state changes from reload, loadMore, reopen, open/close and dismiss still reach `uiState`.
The pipeline now starts only with the first subscriber. It stops 5 s after the last one leaves. When the screen
subscribes again, it reloads once, with as many pages as were shown, because `GtdRefreshTriggers.on` (behind
`LogbookRepository.changes()`) starts with exactly one `flowOf(Unit)` emission.

Correctness checks:
- **First subscription:** loads once and does not load twice. `changes()` emits once at start, and `emit(Unit)`
  only seeds `combine`. Covered by the test `nothing is loaded before the screen subscribes`, which expects
  `page(null)` exactly once.
- **loadMore and reopen:** these still run on `viewModelScope` and are not affected. A reload that `reopen`
  requests while nobody is subscribed is dropped, because `reloads` has no replay. This is harmless: the next
  subscription reloads anyway.
- **Reload cancelled by the stop timeout:** the state stays consistent. Either nothing was loaded yet and
  `loading` stays true, or the last loaded list is kept. The next subscription reloads.
- **Tests:** two new ViewModel tests cover the fix. The first shows that nothing is loaded before the screen
  subscribes. The second covers three cases: changes are still followed within the stop timeout; changes and
  `retry()` are ignored past the timeout; resubscribing reloads both shown pages exactly once. All existing
  LogbookViewModelTest tests still pass without changes.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — Pass (BUILD SUCCESSFUL)

## Not fixed

None.

## Validation summary

Focused tests:
- `LogbookViewModelTest` (part of `testDebugUnitTest`): 29 tests, 0 failures, 0 skipped

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`: Pass. 2664 unit tests, 0 failures, 0
  errors, 0 skipped. ktlint is clean. lintDebug reports only 2 `Hint`-severity `AutoboxingStateCreation` entries
  in unrelated files (`journal/JournalScreen.kt`, `notes/NoteEditorScreen.kt`).

Lint/type-check/build:
- Included in the command above: Pass

`.taskcheck`:
- Not run. The parent worker runs it before `done`. The Android part of it is the command above.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
