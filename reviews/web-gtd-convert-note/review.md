# Code Review

Date: 2026-10-21

Task: web-gtd-convert-note - Inbox/task detail: to reference

Scope: final code at HEAD for the task, `git diff 7ce21cc0..HEAD -- web` (commits 4e0c4b53 implementation, 59c9af2b tests, 21ae7bdb fixes for CR-3/CR-4 of review-1.md). Uncommitted spec files from a parallel test-writer were ignored.

Requirements:
- tasks/web-gtd-convert-note.md (acceptance criteria and the two recorded assumptions)
- SPEC.md §5.3 (Inbox processing action "To reference (note)") and the §5 Reference row
- api/openapi.yaml `POST /api/v1/tasks/{id}/convert-to-note` (convertTaskToNote, If-Match, NoteResponse)
- server `gtd/convert/domain/TaskToNoteService.kt` (conversion semantics: the task goes to the Trash, the note gets title and notes)
- docs/ARCHITECTURE.md, AGENTS.md conventions
- reviews/web-gtd-convert-note/review-1.md and fixes.md (earlier findings and their dispositions)

Git range:
- Branch: main
- HEAD: 21ae7bdbe3757db5e2efc894907ef9c830c67620
- Base/start: 7ce21cc072525cedb46378ee80989f449382cd1a
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.

What I checked:
- `TaskStore.convertToNote` (`web/src/app/features/gtd/data/task-store.ts`) uses `mutate` to remove the item optimistically and sends If-Match from the caller's copy. The request callback deliberately returns nothing, so `mutate` drops the task and does not `accept` the NoteResponse into the task list. That is also why the note comes back through a captured variable (`note!`). On failure the overlay is rolled back and the error is rethrown. The specs cover success, rollback with rethrow, and a list answer that crosses the conversion.
- Inbox (`inbox-page.ts` `run()`): the `reference` action no longer has `disabled` and is not a prompt action, so the `R` key and the button both run it directly. The inline clarification changes are not saved, as the recorded assumption says. `lastNote` shows a `role="status"` line with an "Open note" link to `['/notes', id]`. It is now cleared after any later action that succeeds and kept when one fails (fix for review-1 CR-3). The `processed` count and the busy set behave like the other actions. Specs cover the key and the button, the If-Match value, inline changes not being saved, several conversions, a failure with rollback, and the item staying out of the queue while its conversion runs.
- Task detail (`task-detail.ts` `toReference()`): it asks for confirmation, with a separate message when the form has unsaved changes. It is guarded against running during a save or a second conversion, and `save()` is guarded against running during a conversion. Older loads are cancelled through `loadSeq++`. Change events no longer start a background reload while `converting()` is set (fix for review-1 CR-4). After success it navigates to `/notes/<id>`. Errors go through the existing `failed()` path (a 412 sets the conflict state and reloads; other errors set `saveError`), and `converting` is reset in `finally`. `TaskDetailService.convertToNote` uses `silent()`, which matches the service's other calls. Specs cover confirm accepted and declined, the dirty message, the running state, the save/convert exclusion, the 412 path, a generic error, and the change event during a conversion.
- Contracts: the generated `convertTaskToNote` function and `NoteResponse` are used as generated. No changes to `api/openapi.yaml` or the generated client were needed or made. Files outside the declared `touches` (`gtd/data/task-store.ts` and its spec) are covered by a recorded assumption.
- Security: no new HTML injection (the note title is rendered through interpolation), no logging, no secrets.

Accepted or open items, not counted as findings:
- review-1 CR-2 (the `/notes/:id` route comes from web-notes-editor, which is still in progress) was accepted by the parent worker and is recorded as an assumption. Until that route lands, criterion 1's "open the note" step goes to the Not Found page. When recording evidence for criterion 1, state this limit instead of claiming the full flow works end to end.
- `python tasks.py verify web-gtd-convert-note` currently shows both criteria unverified. The parent worker still has to record evidence: the commands it ran (for example `cd web && npm run check`) and their results.
