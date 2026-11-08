# Code Review

Date: 2026-11-08

Task: and-project-tasks - Android: add tasks directly to a project and reorder them

Scope: task commits ad642d981 and fc78e00c9 on top of base 05855c3ae (`git diff 05855c3ae..HEAD -- android`), committed changes only

Requirements:
- tasks/and-project-tasks.md (acceptance criteria and the two recorded assumptions)
- SPEC.md, docs/ARCHITECTURE.md, docs/PARITY.md, AGENTS.md project notes
- Web reference: web/src/app/features/gtd/projects/project-detail-page.{html,ts}, project-task-store.ts
- Server contract: ProjectService.orderTasks (server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/ProjectService.kt:123)

Git range:
- Branch: main
- HEAD: fc78e00c9
- Base/start: 05855c3ae0705c9237c32572055329581de3bf1b
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 2 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A drag that is cancelled, not dropped, is still saved (or shown as saved)**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectTaskList.kt:314` (also `:181`, `:186-188`, `:215`)
    - Category: Correctness
    - Problem: `longPressDrag` calls `onEnd()` in a `finally` block, so it runs both when the finger lifts and when the gesture coroutine is cancelled. `onEnd` always takes `drag.stop()`, writes it to `dropped` and calls `reorder(result)`. The coroutine is cancelled while the finger is still down in two normal situations. (a) The `pointerInput` keys include `ids` (`:181`), so any change to the project's open task ids during a drag restarts it. That happens when a task change arrives from the server or another device (`taskChanges()` reloads), when a task is completed elsewhere, or when an earlier save is refused and `pendingOrder` goes back to null (ProjectsViewModel.kt:586-590). The half-finished drag order is then sent to the server as if the user had dropped it, built from the old `ids`. (b) If the screen becomes read-only mid-drag (offline, or a failed load), `canReorder` turns false and `.then(...)` drops the modifier (`:215`). The node detaches, `onEnd` sets `dropped = result`, and `saveOrder` refuses because of read-only (ProjectsViewModel.kt:581). `ids` has not changed, so `remember(ids)` keeps the unsaved order on screen with no message. The Move up/down actions and `onStart` then work from different orders: the VM uses `detail.tasks` while the list shows `dropped`.
    - Why it matters: Criterion 2 says "the order is saved like on web". Here an order the user never dropped can be saved, or an order that was never saved stays on screen as if it were. The code comment at `:166-167` says a restart should drop the gesture, but the restart commits it instead.
    - Suggested fix: Commit only when the finger lifts. For example, set a flag when `changedToUpIgnoreConsumed()` is seen and call `reorder` only in that case. On cancellation or a lost pointer, only call `drag.stop()` and leave `dropped` alone. Also, when the view model does not take the order, `dropped` should not keep showing it (for example, reset it to `ids` when `reorder` is refused, or derive the shown order only from the view model's state). Add a Compose test that changes `detail.tasks` (and one that sets read-only) during a drag and asserts that `reorder` is not called and the shown order equals the state's order.

- [ ] **CR-2 | TalkBack may not reach the Move up/Move down actions: they are on a non-focusable parent of the row**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectTaskList.kt:194-208`
    - Category: Correctness (accessibility)
    - Problem: `customActions` are set on the outer `Column` that wraps the row and its divider. That `Column` does not merge descendants and is not a leaf, so Compose does not mark it as focusable for screen readers. TalkBack focus lands on the `ListItem` inside it, which is clickable through `opensTask` (`gtd/lists/ListUi.kt:122`, `gtd/next/TaskListUi.kt:166`) and merges only its own descendants. TalkBack's actions menu lists the focused node's actions, so it does not show the parent's custom actions. The Compose tests call the actions on the `ProjectTaskTags.item` node in the semantics tree, which works whether or not a screen reader can reach it. So the passing tests do not show that the actions are usable. This is inferred from how Compose exposes semantics to TalkBack and has not been checked with TalkBack on a device.
    - Why it matters: Criterion 2 requires "accessibility actions move up/down". If TalkBack does not offer them, screen-reader users have no way to reorder, because a long-press drag is not usable for them.
    - Suggested fix: Put the `customActions` on the same node TalkBack focuses. `LiveTaskRow` already takes a `modifier` that is applied on the same layout node as the `opensTask` clickable, so the semantics merge into that node. Keep the `testTag` where the tests need it, then check once on a device or emulator with TalkBack that the actions menu shows "Move up"/"Move down".

## P3 Low

- [ ] **CR-3 | Overlapping order saves are not serialized; the last save the server applies wins, which may not be the user's last move**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt:584-585`
    - Category: Correctness (concurrency)
    - Problem: Each `saveOrder` starts its own `orderProjectTasks` request right away, even while an earlier one is still running. Two quick Move down actions, or a drop shortly after another, send two full orders at the same time. If the server applies them in the other order (parallel HTTP/2 streams, separate transactions), the older order wins on the server. The UI first shows the newer pending order and its "Moved ... to position n of m" message, then switches to the server's older order after the next load. The newest-wins guard (`current !== pending`) only protects the local pending state, not the server's result. Web has the same pattern (`project-task-store.ts:28-39`), so this matches web rather than breaking parity. No test covers two saves in flight.
    - Why it matters: A reorder the user just made, and was told about, can be silently undone. This is most likely with the Move actions, where one press is one request.
    - Suggested fix: Serialize saves per project: queue the latest requested order and send it after the running request finishes, dropping orders in between. Add a view model test with two saves in flight that checks the final order is sent last.

## Review conclusion

Material issues found; fix before completing the task.

Notes (no findings): the add-task flow follows web. The field clears once the task exists, also when setting the next step fails, so a retry adds no duplicate (ProjectsViewModel.kt:540-551, ProjectWorkRepository.kt:134-154). It runs once at a time through `run(project.id)`, and it trims the title and refuses blank ones. The pending-order lifecycle keeps the newest order, rolls back on refusal, and is cleared by the first successful load after saving. Read in full, `inOrder` and the server's `orderTasks` contract (duplicates and foreign ids are rejected, other tasks keep their order after the given ones) agree. English and German strings are complete for every new key. The stale-layout guard in `DragState.by` (`dragged.index != from`) and keeping the same modifier chain whether or not a row is dragged both look correct. Evidence for criterion 5 (the full Gradle run and `sh scripts/check.sh`) was still running when this review was written. The parent must record it before running `done`.
