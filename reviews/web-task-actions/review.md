# Code Review

Date: 2026-11-08

Task: web-task-actions - Web task detail: complete, delete, undo, Markdown notes

Scope: `git diff 4102a26a80c47af215943702c497901f6ee773a7..HEAD -- web/` (task commits eba1ef248, b28baf82e). Committed changes only. The test-writer's uncommitted spec files in the working tree were not reviewed.

Requirements:
- tasks/web-task-actions.md (acceptance criteria and the recorded assumptions)
- SPEC.md §4.2 (Trash, ETag/If-Match), §5.2 (task rules), §5.3
- docs/PARITY.md (row "No delete/complete in task detail, no undo, checklist rename, notes not rendered")
- AGENTS.md "Project notes", docs/ARCHITECTURE.md conventions
- Parent instructions (the decisions recorded as assumptions are intentional)

Git range:
- Branch: main
- HEAD: b28baf82e736921d2cc9dd234addb6e1d0583291
- Base/start: 4102a26a80c47af215943702c497901f6ee773a7
- Commits reviewed: 2

Summary: 0 P0, 2 P1, 1 P2, 2 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Every checklist step is struck through, not just the done ones**
    - File: `web/src/app/features/gtd/task-detail/task-checklist.scss:54-62`
    - Category: Correctness
    - Problem: Before this task the rule was `&.done .step-text { color: muted; text-decoration: line-through; }`. The change moved the `.rename` rules under `&.done` and turned the strike-through rule into a plain `.step-text` rule inside `.step`. Now every step label is muted and struck through, open or done. Also, the rename input's `min-width: 0` and font only apply when the step is done. Open steps, which are the ones usually renamed, get the default input font and can overflow the `1fr` grid column.
    - Why it matters: A task with open steps looks fully completed. The only way to tell open from done is the checkbox, and the `n/m` count no longer matches what the user sees. This is a visible regression of existing checklist behaviour (SPEC.md §5.2 checklist). The unit tests don't check computed styles, so `npm run check` passes.
    - Suggested fix: Restore `&.done .step-text { ... }` and move the `.rename { min-width: 0; font: ...; }` rule out of `&.done` so it applies to every step.

- [ ] **CR-2 | The new behaviour has almost no unit tests in the reviewed commits**
    - File: `web/src/app/features/gtd/task-detail/task-detail.spec.ts:173-176,1022-1023` (the only spec changes in the range)
    - Category: Tests
    - Problem: Acceptance criterion 6 says "Unit tests cover the new behavior". The two task commits only change `task-detail.spec.ts` so the existing tests click the new Edit button. Nothing tests: Complete/Reopen/Delete in task detail (including save-before-complete, the dirty-delete confirm and navigating back), `TaskUndo` (action vs. dismiss, a failing undo, restore from Trash), list undo in Next/Today/project detail (including the prompt closing on undo), checklist rename (Enter, blur, Escape, empty or unchanged text, a 412 or error rollback), or rendered notes (link click doesn't edit, Enter edits, notes render again after Save/Discard). CR-1 got through for the same reason.
    - Why it matters: The criterion is not met at the reviewed revision. A test-writer is working on this in parallel (uncommitted `task-undo.spec.ts` and `task-checklist.spec.ts` changes exist). Close this only after checking that their committed tests cover the behaviours listed above.
    - Suggested fix: Commit tests for each behaviour listed. Where possible, add one that checks open steps are not rendered as done (a class or computed-style check), so CR-1 can't come back.

## P2 Medium

- [ ] **CR-3 | Edits typed while Complete/Reopen runs are silently discarded**
    - File: `web/src/app/features/gtd/task-detail/task-detail.ts:721-722` (`showSaved`, lines 770-773)
    - Category: Correctness
    - Problem: `busy()` disables only the header and save buttons. The form fields stay editable while `service.complete`/`uncomplete` runs. When the answer comes back, `showSaved(result)` unconditionally runs `this.draft.set(draftOf(result))`. `save()` deliberately avoids this (`if (this.draft() === draft) ...`, comment "Edits made while the save ran stay").
    - Failure scenario: On a slow connection the user clicks Complete on a clean task, then types a note or changes the title before the response arrives. The response replaces the draft, the typed text disappears, and the user gets no warning or dirty state.
    - Suggested fix: Keep the pattern from `save()`. Capture the draft before the request. On success, set `task` and replace the draft only if `this.draft()` is still that captured draft, so later edits stay dirty against the new version.

## P3 Low

- [ ] **CR-4 | The Markdown styles for notes duplicate the project description styles**
    - File: `web/src/app/features/gtd/task-detail/task-detail-notes.scss:27-66`
    - Category: Quality
    - Problem: The `.markdown` block is a copy of `web/src/app/features/gtd/projects/project-description.scss:34-72` (headings, code, pre, blockquote, links). Only `cursor: pointer` on links was added. `task-detail.ts:25` also imports `renderMarkdown` from the sibling `projects` folder, the only import from `gtd/projects` into another gtd sub-feature.
    - Why it matters: Any later change to how Markdown renders (e.g. tables or lists in `renderMarkdown`) has to be made in two stylesheets, and the copies will drift.
    - Suggested fix: Move `renderMarkdown` and one shared `.markdown` stylesheet partial to `gtd/ui` (or a shared location), and use them from both places.

- [ ] **CR-5 | The guard against the DELETED event ends before the page navigates away**
    - File: `web/src/app/features/gtd/task-detail/task-detail.ts:668-670` together with `act()` lines 760-766
    - Category: Correctness
    - Problem: The comment says our own Delete must not let the change event replace the page. But `acting()` is cleared in `act()`'s `finally` right after `closed.emit()`. `BackNavigation.back()` navigates asynchronously. If the SSE `DELETED` event arrives after the HTTP response but before the route changes, `state` becomes `'deleted'` and the "task was deleted" view flashes before the page closes.
    - Why it matters: Small cosmetic glitch. The comment overstates what the guard covers.
    - Suggested fix: Keep a "deleted by us" marker (e.g. the deleted task id) until the component is destroyed or `taskId` changes, instead of relying on `acting()`.

## Review conclusion

Material issues found; fix before completing the task.

Checked and found sound: the `TaskUndo` resolve logic (action vs. dismiss, and a new snackbar dismissing the previous one); list undo through the store's `uncomplete` (an optimistic re-insert plus `accept`, with errors toasted by the HTTP layer); the restore from Trash via `restoreTrashItem` with type `TASK`; `reopenFromUndo` using the newest known version; and `[innerHTML]` safety (`renderMarkdown` escapes text and allows only http(s)/mailto links with `rel="noopener noreferrer"`; Angular's sanitizer also runs). Also checked: the switch-task guards in `act()`; delete navigating back via `TaskDetailPage.back()` and `BackNavigation`; and the English and German locale entries for all 14 new ids. The recorded assumptions (undo edits in next/today/projects through a root service, complete saving first, delete confirming only when dirty, the pencil button for rename) are reasonable and implemented as described.
