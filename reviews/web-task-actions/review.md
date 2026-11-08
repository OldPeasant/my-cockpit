# Code Review

Date: 2026-11-08

Task: web-task-actions - Web task detail: complete, delete, undo, Markdown notes

Scope: second review of the final code. `git diff 5f6e2826f414563c702e8634aad704fc7b218840..HEAD -- web/` (task commits 045f3500e, 926fd4d58, 01964a6f4, dd8bb50cf). Committed changes only. The test-writer's uncommitted change to `task-detail.spec.ts` in the working tree was not reviewed.

Requirements:
- tasks/web-task-actions.md (acceptance criteria and the recorded assumptions)
- SPEC.md §4.2 (Trash, ETag/If-Match), §5.2 (task rules, checklist)
- docs/PARITY.md, AGENTS.md "Project notes", docs/ARCHITECTURE.md conventions
- reviews/web-task-actions/fixes.md (fixes to verify) and the first review (CR-1..CR-5)

Git range:
- Branch: main
- HEAD: dd8bb50cf6bcb95f23b436a70cfb4b7b34e6592c
- Base/start: 5f6e2826f414563c702e8634aad704fc7b218840
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 2 P2, 1 P3

## Verification of the first review's findings

- CR-1 (every step struck through): fixed. `task-checklist.scss:54-62` has `.rename` for every step again, and the strike-through only under `&.done .step-text`. The committed tests check the `done` class and the computed `text-decoration` per step.
- CR-2 (missing tests): resolved. The committed specs (`task-detail.spec.ts` "complete, reopen and delete" and "rendered notes", `task-checklist.spec.ts`, `task-undo.spec.ts`, `next-view.spec.ts`, `today-view.spec.ts`, `list-pages.spec.ts`, `projects-pages.spec.ts`, `messages-de.spec.ts`) cover Complete/Reopen/Delete (save first, invalid changes, a failing save, 412, errors, task switch, own and foreign DELETED events), Undo, list undo, checklist rename, rendered notes and the German strings. Two gaps remain, see CR-1 and CR-2 below.
- CR-3 (edits lost during Complete/Reopen): fixed. `toggleDone` (`task-detail.ts:316-329`) and `reopenFromUndo` (`task-detail.ts:361-366`) replace the draft only when it is unchanged. `showSaved` is gone.
- CR-4 (duplicated Markdown styles, `renderMarkdown` imported from `gtd/projects`): still present. The parent worker deferred it on purpose. It is kept below as CR-3 so the decision stays visible.
- CR-5 (DELETED event guard ends too early): fixed with `deletedId` (`task-detail.ts:188, 197, 211, 343-349`). It is cleared on a failed delete and on a switch to another task.
- TW-1 (no Undo after a switch) and TW-2 (prompt closed by title): fixed (`task-detail.ts:322-325`, `project-detail-page.ts:130, 137, 156-159`).

No regressions from these fixes were found. Also checked: all 13 new i18n ids are in both `messages.xlf` and `messages.de.xlf`. `[innerHTML]` gets only `renderMarkdown`'s escaped output, and Angular's sanitizer still runs. Escape or Enter followed by the input's blur cannot save twice, because `saveRename` clears `renaming` first.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Complete continues after a save that hit a 412, and completes the server's version**
    - File: `web/src/app/features/gtd/task-detail/task-detail.ts:309-317` (with `save()` 491-496, `failed()` 507-510, `act()` 373)
    - Category: Correctness
    - Problem: With unsaved changes, `toggleDone` calls `save()` and checks only `if (this.dirty()) return;` afterwards. If that save fails with a 412, `failed()` sets `conflict` and reloads the task with `load(..., 'conflict')`. The reload replaces both `task` and `draft` with the server version, so `dirty()` becomes false and `toggleDone` goes on. `act()` then runs `this.conflict.set(false)`, which hides the "changed elsewhere" notice, and completes the version the user never saw.
    - Failure scenario: The user edits the title and clicks Complete. Meanwhile someone else changed the task on another device. The save gets a 412. Instead of seeing the conflict and the reloaded task, the user sees the task marked done, with their edit gone and no conflict notice. Only the Undo snackbar is left as a hint. The test "does not complete when saving the changes first fails" (`task-detail.spec.ts:2538`) shows this should not happen, but it covers only a 500, where the draft stays dirty.
    - Suggested fix: Continue only if the save succeeded. For example, stop when `this.conflict()` or `this.saveError()` is set after `save()`, or have `save()` return whether it saved. Add a test where the first save of Complete gets a 412: no `completeTask` call, and the conflict notice is shown.

- [ ] **CR-2 | Complete can complete another task when the editor switches during the save before it**
    - File: `web/src/app/features/gtd/task-detail/task-detail.ts:309-317`
    - Category: Correctness
    - Problem: `toggleDone` reads `this.task()` again after `await this.save()` and does not check that `taskId` is unchanged. `TaskDetailPage` reuses `TaskDetail` when the `/tasks/:id` route parameter changes (`task-detail-page.ts:20-21`), for example after following a linked task or using search. If the switch happens while the save runs, `save()` returns early (line 483). If task B's initial load answers first, `task()` is B with a clean draft, so `dirty()` is false and B gets completed.
    - Failure scenario: The user edits task A, clicks Complete, and opens linked task B before the save (a move plus a PATCH) answers. A's changes are saved, A is not completed, and B is marked done, with an Undo snackbar naming B. All other actions in this file guard against this kind of switch ("does not show the completion of the previous task in the new one", `task-detail.spec.ts:2640`). This path has no guard and no test.
    - Suggested fix: Capture `this.taskId()` (or the task) before saving, and return when it changed after `save()`. Add a test that switches tasks while the save of Complete is held.

## P3 Low

- [ ] **CR-3 | The Markdown styles for notes duplicate the project description styles (deferred)**
    - File: `web/src/app/features/gtd/task-detail/task-detail-notes.scss:27-66`, `web/src/app/features/gtd/task-detail/task-detail.ts:25`
    - Category: Quality
    - Problem: The `.markdown` block copies `gtd/projects/project-description.scss`, adding only `cursor: pointer` on links. `task-detail` imports `renderMarkdown` from the sibling feature `gtd/projects`. The parent worker deferred this on purpose (first review CR-4).
    - Why it matters: Later changes to Markdown rendering have to be made in two stylesheets, and the copies will drift.
    - Suggested fix: In a follow-up task, move `renderMarkdown` and a shared `.markdown` partial to `gtd/ui` or `shared`.

## Review conclusion

Material issues found; fix before completing the task. CR-1 and CR-2 are small guards in `toggleDone` after the save step, each with a focused test. The parent worker decides whether CR-3 stays deferred.
