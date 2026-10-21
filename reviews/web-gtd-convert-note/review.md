# Code Review

Date: 2026-10-21

Task: web-gtd-convert-note - Inbox/task detail: to reference

Scope: implementation commit 2a6e5e33 on top of base 3e6a11a2 (`git diff 3e6a11a2..2a6e5e33 -- web`); later commits up to HEAD touch only `tasks/`. Uncommitted spec changes by the parallel test-writer (`task-store.spec.ts`, `inbox-actions.spec.ts`) were left out.

Requirements:
- tasks/web-gtd-convert-note.md (acceptance criteria and the two recorded assumptions)
- SPEC.md §5.3 (Inbox processing action "To reference (note)"), §5 Reference row ("move to reference" converts the item into a Note)
- api/openapi.yaml `POST /api/v1/tasks/{id}/convert-to-note` (operationId convertTaskToNote, optional If-Match, 201 NoteResponse)
- docs/ARCHITECTURE.md and AGENTS.md project conventions

Git range:
- Branch: main
- HEAD: a5a0b1c1b92d93c31d9a7f2661f496c5125ab695
- Base/start: 3e6a11a2f490f4a81e1666d0496a146a832875d2
- Commits reviewed: 1 code commit (2a6e5e33); the rest are task-state commits

Summary: 0 P0, 1 P1, 1 P2, 2 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | The committed specs still say "To reference" is disabled, and nothing tests the new action**
    - File: `web/src/app/features/gtd/inbox/inbox-page.spec.ts:357`, `web/src/app/features/gtd/inbox/inbox-actions.spec.ts:44` and `:79` (as committed in 2a6e5e33), `web/src/app/features/gtd/task-detail/` (no spec mentions `toReference`/`convertToNote`)
    - Category: Tests
    - Problem: 2a6e5e33 removes `disabled: true` from the `reference` action (`inbox-actions.ts:93`). The committed tests still expect the old behavior. They check that `button('To reference').disabled` is true and that pressing `r` makes no call (inbox-page.spec.ts:357-367), that only reference and calendar are disabled, and that `actionForKey('r')` returns null (inbox-actions.spec.ts). So `npm run check` fails at the implementation commit. No committed test covers the inbox reference action: it should call convertTaskToNote, remove the item from the queue, show the "Open note" link, and bring the item back on an error. Nothing covers `TaskDetail.toReference` either: the confirm (clean or dirty), the navigation to `/notes/<id>`, and the error/412 path.
    - Why it matters: Criterion 2 needs Vitest component/service tests showing the action calls convert and updates the inbox, and it needs `npm run check` to pass. Neither is true for the committed code. The test-writer's uncommitted edits only touch `task-store.spec.ts` and `inbox-actions.spec.ts`. `inbox-page.spec.ts:357` and the task-detail specs are still not addressed.
    - Suggested fix: Update `inbox-page.spec.ts:357` so only To calendar is disabled. Add inbox-page tests for `r` and the button: convert is called with If-Match, the item leaves the queue, `processed` goes up, the note link shows, and a failed convert rolls back. Add a TaskDetail test for toReference covering confirm accepted and declined, the dirty message, navigation to `['/notes', id]`, and a failure showing `saveError` or a 412 conflict reload. Then run `cd web && npm run check`.

## P2 Medium

- [ ] **CR-2 | "Open the note" leads to "Page not found" until web-notes-editor adds `/notes/:id`**
    - File: `web/src/app/features/gtd/task-detail/task-detail.ts:256`, `web/src/app/features/gtd/inbox/inbox-page.html:73`; route table `web/src/app/features/notes/notes.routes.ts:3-9`
    - Category: Integration
    - Problem: The task detail navigates to `/notes/<id>` and the inbox links there, but `notes.routes.ts` only has the `''` route. Today both land on the `**` Not Found page (`app.routes.ts`). The route is planned in web-notes-editor (in progress, see its assumption). That task is not in this task's `deps` (`[web-inbox, web-task-detail, gtd-convert-note]`). The note-card in the notes list already uses the same link, so the URL follows the convention, but the open step of criterion 1 cannot work on the current main.
    - Why it matters: Criterion 1 ("navigates/offers to open the note") cannot be shown end to end yet. If this task is marked done first, a user who converts a task in the task detail is sent to a Not Found page right after the task went to the Trash.
    - Suggested fix: Say so when verifying criterion 1: the convert and the navigation target are verified, and the note opens once web-notes-editor lands. Keep the existing assumption, which already names the route owner. Optionally add the dependency through the proper task tooling instead of editing it by hand. No code change is required if that ordering is accepted.

## P3 Low

- [ ] **CR-3 | The "Moved to reference" status stays up while later items are processed with other actions**
    - File: `web/src/app/features/gtd/inbox/inbox-page.ts:235` (set), `:133-137` (only reset in `startProcessing`)
    - Category: Correctness
    - Problem: `lastNote` is cleared only when processing starts. If you convert item A, then move item B to Next and item C to Someday, the banner still says "Moved to reference as the note A" above item D. It reads as if it describes the last action.
    - Why it matters: The message is slightly misleading during a long processing run. The link itself still works.
    - Suggested fix: Clear `lastNote` when any other action on a later item succeeds (for example in `run()` before or after a non-reference action), or word the banner so it clearly refers to an earlier item.

- [ ] **CR-4 | A change event can still start a background reload while the conversion runs**
    - File: `web/src/app/features/gtd/task-detail/task-detail.ts:163` and `:252-253`
    - Category: Correctness
    - Problem: `toReference` runs `loadSeq++` with the comment "A background reload would replace the form while it turns into a note". That only cancels loads started earlier. The TASK event handler (line 163) checks `dirty()` and `saving()` but not `converting()`. A non-DELETED event arriving during the conversion therefore starts a new load. If that load answers 404 (the task is already trashed), the view switches to "not found" before the navigation. `isStale` (line 353) does not check `converting()` either.
    - Why it matters: A short, confusing view switch in a rare race. No data is lost.
    - Suggested fix: Add `&& !this.converting()` to the event handler condition, or to `isStale`, so the guard matches the comment.

## Review conclusion

Material issues found; fix before completing the task.

The production logic is sound and follows the existing patterns. `TaskStore.convertToNote` uses `mutate` for an optimistic removal with rollback and sends If-Match from the caller's copy. `TaskDetailService.convertToNote` uses `silent()` and sends errors through the existing `failed()` path, so a 412 shows the conflict banner and reloads. Both call the right endpoint and use `NoteResponse` correctly, and the inline clarification changes are deliberately not saved before converting. The blocking item is CR-1: the committed tests contradict the new behavior, so `npm run check` fails at the implementation commit, and the new action has no tests yet.
