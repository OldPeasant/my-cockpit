# Code Review

Date: 2026-10-20

Task: web-task-detail - Task detail editor

Scope: `git diff 449b9ca5..0e87a2a4` (committed code at HEAD): the `web/src/app/features/gtd/task-detail/` feature folder plus the `tasks` route line in `web/src/app/features/gtd/gtd.routes.ts`. Re-review of the final revision after the fixes for TW-1 and CR-1..CR-4 from the first review.

Requirements:
- tasks/web-task-detail.md acceptance criteria and recorded assumptions (routed page /tasks/:id, plain Markdown textarea, waiting-for as free text, move-then-patch save, client-side RRULE validator)
- SPEC.md §5.2, §4.2
- docs/ARCHITECTURE.md, AGENTS.md "Project notes"
- reviews/web-task-detail/tests.md (TW-1), reviews/web-task-detail/fixes.md (fixes and the fixer's unfixed observation about a route change during a save)

Git range:
- Branch: main
- HEAD: 0e87a2a4585709c68139b347096125c3e9df4b3f
- Base/start: 449b9ca5ef129f2bf0834c8073c0414f837f176d
- Commits reviewed: 6 (103f1d74, 6ddb6706, b94c4e7f, 93e96703, 27456db6, 0e87a2a4); no uncommitted changes at review time

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## Earlier findings: all resolved

- TW-1 (repeat mode pressed again): resolved. `setRepeatMode(mode, picker)` (`task-detail.ts:215-222`) puts the current mode back into the `Segmented` picker when it emits `undefined`, and edits the draft only when the mode really changes. Tests: `keeps the repeat mode when the chosen mode is pressed again`, `switches the repeat mode after the chosen one was pressed again`.
- CR-1 (background reload against edits and saves): resolved. A `background` answer is dropped when the form is dirty or saving when the answer arrives, or when its version is older than the one shown (`task-detail.ts:304`, `:317-320`; `version` is a number in `TaskResponse`). `save()` increments `loadSeq` (`:254`), so a GET that started before the save is discarded. The 412 path starts its own `conflict` load after that increment, and that load is applied. Tests: `keeps what the user typed while a background reload was running`, `does not replace a newer save with an older background reload`, `still applies the conflict reload when a background reload was pending`, `ignores a background answer older than the version shown`, `shows a change made elsewhere after the own save`.
- CR-2 (overlapping checklist writes): resolved. `toggleItem`/`removeItem` return while `busy()` (`task-checklist.ts:72`, `:81`). The checkbox and the remove button are disabled while busy (`task-checklist.html:19`, `:51`). All four entry points into `change()` are guarded now, so the single `busy` flag is correct. Test: `lets no second toggle or remove start while a change is saving`.
- CR-3 (field errors not linked): resolved. Every field error has an id and the matching control references it with `aria-describedby` while the error is shown (`task-detail.html:82`, `:116`, `:132`, `:203`, `:230`, `:248`, `:263-265`, `:285`). The custom-rule error is inside its row, and a server rule error shown without the rule input is linked to the Repeat select (`td-repeat-error`).
- CR-4 (save shortcut hint): resolved. `i18n="@@taskDetail.saveShortcut"` marks the hint, it reads `Ctrl/⌘ ↵` and is `aria-hidden`. The submit button sets `aria-keyshortcuts`, which matches `onKeydown` (`task-detail.html:334-338`).

The acceptance criteria are met by the code: every §5.2 field, the checklist (add/toggle/remove/reorder), the presets plus a validated custom RRULE, If-Match saves with a 412 banner and reload, and the `LinkedItems` panel. `python tasks.py verify web-task-detail` does not list any evidence for criteria 1-4 yet. The parent worker still has to record it, including a `cd web && npm run check` run on this revision for criterion 4.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A save that is still running when the route id changes puts the previous task back into the editor of the new one**
    - File: `web/src/app/features/gtd/task-detail/task-detail.ts:245-271`, `web/src/app/features/gtd/task-detail/task-detail.ts:277-281`; `web/src/app/features/gtd/task-detail/task-checklist.ts:63-66`
    - Category: Correctness
    - Problem: This confirms the fixer's observation: the problem is real. `TaskDetailPage` reuses one `TaskDetail` for every `/tasks/:id` (`task-detail-page.ts:14-16`, `paramMap`), so a new id only changes `taskId` and triggers `load(newId, 'initial')`. `save()` captures `task` and, after its awaits, runs `this.task.set(current)` and `this.draft.set(draftOf(current))` (lines 261-262, 266) without checking that `current.id` is still `this.taskId()`. If the GET for task B returns before the save of task A, the end of the save replaces B in the form with A. The URL, the checklist and the linked-items panel (`ref()`) stay on B, and change events are filtered by B. The next edit and save then PATCHes task A while the page shows B. The page is easy to reach: the editor's own Linked items panel opens a linked task through `[routerLink]="row.other.deepLink"` (`shared/links/linked-items.html:119`), as do search and the inbox. Two more paths have the same cause. A 412 on the save of A calls `load(this.taskId(), 'conflict')` (line 280), which shows B with a false "changed elsewhere" banner. In the checklist, an `addItem` that finishes after the id changed appends task A's new item to B's list (`task-checklist.ts:64-65`), and toggling or removing that item then writes to A.
    - Why it matters: The editor shows one task under the URL, links and checklist of another. The next save writes to the wrong task, which the user did not open. No test covers an id change during a save (the existing id-change tests cover only loads).
    - Suggested fix: In `save()` (and in `failed()`), apply the result only while `this.taskId() === task.id`. A move/patch that already went through for A just stays saved. Skip the conflict reload, or run it for A only, when the id has changed. In `TaskChecklist.addItem`, append the item only if `item.taskId === this.taskId()`. Add tests that change the id while a save, a 412 save and a checklist add are pending.

## P3 Low

- [ ] **CR-2 | Following a link out of the editor silently drops unsaved changes, while Close asks first**
    - File: `web/src/app/features/gtd/task-detail/task-detail.ts:145-148`, `web/src/app/features/gtd/task-detail/task-detail.ts:232-235`
    - Category: Quality (UX consistency)
    - Problem: `close()` asks with `DISCARD_CONFIRM` before dropping a dirty form. When the route id changes, the `taskId` effect runs `load(id, 'initial')` and replaces the draft without asking. Leaving the route has the same effect, because no `canDeactivate` guard exists in the app. The editor's own Linked items panel links to other tasks, so one click inside the form can lose typed notes.
    - Why it matters: The two ways out of the editor behave differently, and typed input is lost without notice. No acceptance criterion requires a confirmation, so this is low priority.
    - Suggested fix: Use the same confirmation for navigation, for example a small `canDeactivate` guard on the `:id` route that asks the component whether it is dirty, which also covers a change of the id. Alternatively, record an assumption that only Close confirms.

## Review conclusion

Material issues found; fix before completing the task. TW-1 and CR-1..CR-4 from the first review are resolved. The new CR-1 (P2) can save edits to a task other than the one shown and should be fixed. CR-2 (P3) is optional.

Note for the parent (not a code finding): `fixes.md` calls its changes "uncommitted" and refers to commit df724f18, which is not in the task history (the fixes are in 27456db6 and 0e87a2a4). The helper owns that report. Keep this in mind when you write `quality.json`.
