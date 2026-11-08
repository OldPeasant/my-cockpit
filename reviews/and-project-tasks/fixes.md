# Fix Report

Date: 2026-11-08

Task: and-project-tasks - Android: add tasks directly to a project and reorder them

Scope: current task; findings CR-1, CR-2, CR-3 from reviews/and-project-tasks/review.md and TW-1 (same as CR-1) from reviews/and-project-tasks/tests.md

## Fixed findings

### CR-1 / TW-1 - A drag that is cancelled, not dropped, is still saved (or shown as saved)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectTaskList.kt`

Problem:
`longPressDrag` called `onEnd()` in `finally`. `onEnd()` saved and showed the drag order. That also happened when the gesture coroutine was cancelled: the `pointerInput` keys changed because the tasks changed, or the modifier was removed because the screen went read-only. An order the user never dropped was then saved, or an unsaved order stayed on screen.

Fix:
`longPressDrag` now takes two callbacks, `onDrop` and `onCancel`, instead of `onEnd`. A local `lifted` flag is set only when the tracked pointer goes up and that up is not already consumed. A gesture the system cancels is delivered as an up that is already consumed, so it does not count as a drop. In `finally`, `onDrop()` runs if `lifted` is set, otherwise `onCancel()` runs. Every other way out counts as a cancel: coroutine cancellation (keys changed or modifier removed), the pointer missing from an event (the `?: break` path), or a consumed up. `onDrop` keeps the old behavior (`drag.stop()`, set `dropped`, call `reorder` if the order changed). `onCancel` only calls `drag.stop()`. It leaves `dropped` alone, so the list shows the state's order again.

Validation:
- Regression tests (written by the test-writer, kept unchanged): `a drag interrupted by a change of the tasks is not saved` and `a drag interrupted by going read-only is not saved and the list keeps the state's order` in `ProjectTaskListTest`. They failed before this fix and should now pass.
- `cd android && ./gradlew ktlintCheck testDebugUnitTest` - Not run by the fixer (sub-agents cannot run Gradle). The parent runs it.

### CR-2 - The Move up/Move down actions sit on a node TalkBack does not focus

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectTaskList.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectTaskListTest.kt`

Problem:
The `customActions` were set on the outer item `Column`. That node does not merge its descendants and is not focusable. TalkBack focuses the clickable `ListItem` inside `LiveTaskRow`, so its actions menu did not list the move actions.

Fix:
`ProjectTaskList` builds the action list as before (only when `canReorder`, and only for the directions the task can move) and passes it to `ProjectTaskRow` as a new `actions` parameter. `ProjectTaskRow` passes `Modifier.semantics { if (actions.isNotEmpty()) customActions = actions }` to `LiveTaskRow(modifier = ...)`. `LiveTaskRow` applies that modifier on the same layout node as `TaskListTags.row(id)` and the `opensTask` clickable. The actions are now part of the merged, clickable node that TalkBack focuses. The item `Column` keeps its testTag and the long-press drag but has no semantics block any more. `ListUi.kt` is unchanged. The `ProjectTaskTags.item` KDoc now says where the actions are.

Tests touched:
- `ProjectTaskListTest`: the `actions({task})` and `action({task}, {label})` helpers now read `SemanticsActions.CustomActions` from `TaskListTags.row(id)` (merged tree) instead of `ProjectTaskTags.item(id)`. Every test that uses them now checks the actions on the focused node.
- `the move actions are offered where a task can move, and not while read-only` gained two assertions: the row node has `SemanticsActions.OnClick` (it is the clickable node a screen reader focuses), and the item node no longer has `CustomActions`. Added the import `org.junit.Assert.assertNull`.

Validation:
- `cd android && ./gradlew testDebugUnitTest` - Not run by the fixer. The parent runs it.
- Checking that TalkBack shows the actions on a real device was not possible here (no device). The parent should record this as a skip or assumption if it needs that evidence.

### CR-3 - Overlapping order saves are not serialized

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModelTest.kt`

Problem:
Each `saveOrder` sent its `work.reorder` request at once, even while an earlier request was still running. The server could then apply an older order last.

Fix:
A new `private val orderSaves = Mutex()` (`kotlinx.coroutines.sync`), and `saveOrder` now runs `orderSaves.withLock { work.reorder(...) }`. The kotlinx `Mutex` is fair (FIFO). The coroutines are launched in call order on the view model's dispatcher, so the requests run one at a time in the order they were made. The pending-order handling did not change: the newest pending order shows at once, an older result never replaces it (`current !== pending`), and a refusal of the newest order rolls back. Each save still posts its message and reload after its own request. No new dependency (kotlinx-coroutines-core is already on the classpath).

Tests touched:
- New: `a newer save sends its order only after the older one finished, so the server applies the last order last`. It starts two saves and checks that the newer order shows at once. It then checks that only the older request was sent (`coVerify(exactly = 0)` for the newer one). After the older request is completed, it checks that the newer request was sent, and finally that the server's order replaces the pending one on the next load.
- Renamed `an older save answering after a newer one was saved does not undo the newer order` to `an older save failing after the newer one was answered does not undo the newer order`, with a comment. With serialization the newer request cannot answer first any more: the test now completes the newer answer in advance, and that request is only sent after the older one fails. The assertions did not change.
- `an older save that fails while a newer one runs does not undo the newer order` is unchanged. It still holds: the newer request starts when the older one fails.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests '*ProjectsViewModelTest'` - Not run by the fixer. The parent runs it.

## Not fixed

None.

## Validation summary

Focused tests:
- Not run by the fixer: sub-agents cannot run Gradle in this environment. The parent should run `cd android && ./gradlew testDebugUnitTest --tests '*ProjectTaskListTest' --tests '*ProjectsViewModelTest' --tests '*ProjectsScreenTest'`.

Full test suite:
- Not run (same reason). The parent runs `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`.

Lint/type-check/build:
- Not run (same reason). The code was written in ktlint style: trailing commas, lines of at most 140 characters, no wildcard or unused imports.

`.taskcheck`:
- Not run (the parent runs `sh scripts/check.sh`).

Remaining unresolved findings:
0

## Final status

All validated findings fixed. Validation (Gradle tests, ktlint, `.taskcheck`) is still to be run by the parent worker.
