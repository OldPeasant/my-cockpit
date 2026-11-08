# Code Review

Date: 2026-11-08

Task: and-project-tasks - Android: add tasks directly to a project and reorder them

Scope: second round, final revision. All task changes under `android/` since base dbf4324fbbac3cdfc1292715157fa189f61bc96a (`git diff dbf4324fb -- android`). This covers commits 04a480ed8, 1a625cac1, d55eefc66, 6a521fbac (fixer) and f2a6d3820 (reports only), plus the uncommitted test additions in `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectTaskListTest.kt`, which the parent will commit unchanged. The main focus is checking the fixes for CR-1..CR-3 from the first round (reviews/and-project-tasks/fixes.md).

Requirements:
- tasks/and-project-tasks.md (acceptance criteria and the two recorded assumptions)
- SPEC.md, docs/ARCHITECTURE.md, docs/PARITY.md, AGENTS.md project notes
- First-round review and fix report: reviews/and-project-tasks/review.md (previous version), reviews/and-project-tasks/fixes.md
- Web reference: web/src/app/features/gtd/projects/project-detail-page.{html,ts}, project-task-store.ts

Git range:
- Branch: main
- HEAD: f2a6d38200dca6d2783e0a9922598dd63cbf06cd
- Base/start: dbf4324fbbac3cdfc1292715157fa189f61bc96a
- Commits reviewed: 5 (plus the uncommitted ProjectTaskListTest.kt additions)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Verification of first-round findings

- CR-1 (a cancelled drag was saved or shown as saved): fixed. `longPressDrag` (ProjectTaskList.kt:302-331) now calls `onDrop` only when the tracked pointer lifts with an up that is not already consumed (`lifted`). Every other way out calls `onCancel`, which only runs `drag.stop()`: coroutine cancellation when the keys change (`ids`) or the modifier is removed (read-only), a missing pointer (`?: break`), or a system cancel. Compose sends the system cancel as an up that is already consumed. No ancestor in this screen handles pointers in the Initial pass, so a real lift is not seen as consumed. `dropped` is only written in `onDrop`, so after a cancel the list shows the state's order again (`drag.order` null, then `dropped` == `ids`). After a successful drop, the view model's pending order changes `ids`, and `remember(ids)` resets `dropped` to that order. A refused save resets it to the server's order. So the Move actions, `onStart` and the view model now all work from the same order. Tests cover a task change mid-drag, going read-only mid-drag, a system cancel (`cancel()`), and that the next drag after an interruption starts from the state's order (ProjectTaskListTest.kt:344-406, the last two are the uncommitted additions).
- CR-2 (Move actions on a node TalkBack does not focus): fixed. `customActions` are now set through the `modifier` of `LiveTaskRow` (ProjectTaskList.kt:349). `LiveTaskRow` puts that modifier on the `ListItem` together with `TaskListTags.row(id)` and the `opensTask` clickable (gtd/lists/ListUi.kt:122), so the actions end up on the merged, clickable node that a screen reader focuses. The test checks that this node has `OnClick` and the custom actions, and that the outer item node no longer has them (ProjectTaskListTest.kt:140-166). It was not checked with TalkBack on a device.
- CR-3 (overlapping order saves): fixed. `saveOrder` runs `work.reorder` inside `orderSaves.withLock` (ProjectsViewModel.kt). Saves are launched on the view model's immediate main dispatcher, and kotlinx `Mutex` is fair, so requests are sent one at a time in call order and the server applies the last order last. Every queued order is still sent, not only the newest. That is still correct and keeps one message per Move action. The pending-order rules did not change (newest wins, a refusal rolls back only when it is still the current order), and I traced them again for both orders of failure. The new view model test checks that the newer request is held back until the older one finishes (ProjectsViewModelTest.kt:1509-1532), and the renamed test was adjusted to the serialized flow.

## Review conclusion

No material issues found.

Notes (no findings):
- All acceptance criteria are covered by the code. Criterion 1: AddTaskField with quick entry, the field stays open and clears only for the text that was added, and "Make it the next step" is offered only while the project has a next step (recorded assumption). Criterion 2: long-press drag plus Move up/Move down accessibility actions, saved through `orderProjectTasks` like on web. Criterion 3: rows open task detail through `opensTask`, and the list label is kept for Inbox, Waiting, Someday and Scheduled. Criterion 4: all 8 new keys exist in `values/` and `values-de/`.
- Validation was run by the parent, not by this reviewer (sub-agents cannot run Gradle). `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests '*gtd.projects*'` on the final code -> ktlint passed, 228 tests, 0 failures, including the two uncommitted ProjectTaskListTest tests. The full `ktlintCheck lintDebug createDebugUnitTestCoverageReport` and `sh scripts/check.sh` runs were still going when this review was written. The parent must record their results for criterion 5 before running `done`.
- Whether TalkBack offers "Move up"/"Move down" on a real device or emulator has not been checked. The Compose semantics test is strong evidence but not that check. If the parent cannot run it, record it as a skip or an assumption under criterion 2 rather than claiming it.
