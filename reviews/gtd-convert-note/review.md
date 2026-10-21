# Code Review

Date: 2026-10-21

Task: gtd-convert-note - Convert task to reference note

Scope: final re-review of the task's commits on top of origin/main 817fff8a up to HEAD b65f2b89: cfd3feef (POST /tasks/{id}/convert-to-note), 131e5c9f (review report), 94409aa9 (edge-case tests and test report), b65f2b89 (regenerated web API client). Committed changes only. Files reviewed in full: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert/api/TaskToNoteController.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert/domain/TaskToNoteService.kt`, `server/src/test/kotlin/ch/sonensei/cockpit/gtd/convert/{TaskToNoteWebTests,TaskToNoteEdgeCaseTests,TaskToNoteRollbackTests}.kt`, `web/src/app/api/generated/fn/tasks/convert-task-to-note.ts`, `web/src/app/api/generated/functions.ts`. Context read: `core/items/domain/LinkService.kt` and `gtd/domain/TaskService.kt`.

Requirements:
- tasks/gtd-convert-note.md acceptance criteria and the three recorded assumptions (soft delete to the Trash; links copied as MANUAL links with the same direction and label, links to missing or trashed items skipped; only title and notes carried over, 201 + Location, optional If-Match)
- SPEC.md §4.2 (ETag/If-Match, Problem Details), §4.3 (links), §5.1 (Reference converts into a Note)
- AGENTS.md project notes (API changes: server first, regenerate `api/openapi.yaml`, then clients; generated files are never hand-edited)
- The previous review (CR-1, P3), `reviews/gtd-convert-note/tests.md` (TW-1) and `reviews/gtd-convert-note/fixes.md`

Git range:
- Branch: main
- HEAD: b65f2b894ddcc464c186ce32905e49537547d246
- Base/start: 817fff8a1c6f1769b8cb775fc0c7db3aa499404b (origin/main, merge base)
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Notes (not findings)

- **Review limitation.** Shell access (git, grep, gradle, npm, tasks.py) was denied in this session. I took HEAD and the base from `.git/refs/heads/main` and `.git/refs/remotes/origin/main`, and the commit list from the repository status the parent supplied. I read the files directly instead of diffing them. I did not run any test, did not search `api/openapi.yaml` for the new path, and could not list the recorded criterion evidence with `tasks.py verify gtd-convert-note`. The parent worker must make sure all three criteria have recorded evidence. Criterion 3 needs both the `UPDATE_OPENAPI=1` regeneration and a run without the flag; the test report says it did not run the regeneration itself.
- **Previous CR-1 is resolved.** `TaskToNoteEdgeCaseTests.kt:233-285` covers three cases: a manual link plus a SYNC backlink to the same item, an inline `[[note:X]]` plus a manual link to X, and all three together through the real `NoteService` and `LinkService`. Each test asserts 201 and the exact set of links on the note. A change that made `LinkService.linksOf` return more than one link per item would make the third test fail with 409.
- **TW-1 is resolved.** `web/src/app/api/generated/fn/tasks/convert-task-to-note.ts` has the generator header. It posts to `/api/v1/tasks/{id}/convert-to-note` with an optional `If-Match` header and returns `NoteResponse`, which matches the controller. `functions.ts:236-237` exports it in operation order. `fixes.md` records that `npm run api:gen` changed only these three files and that `npm run check` (including `api:check`) passed.
- **Correctness (unchanged since the previous review, re-checked).** Because `TaskToNoteService` is `@Transactional`, the trash step, note creation and link copies all run in one transaction. `TaskToNoteRollbackTests` shows that a failing link copy undoes both the note and the soft delete. The service reads the links before `TaskService.delete`. It checks with `ItemResolverRegistry` that each linked item still exists before calling `LinkService.create`, so a 400 from `requireKnown` cannot mark the transaction rollback-only. `checkVersion` runs inside `TaskService.delete` before any change, and the 412 tests confirm that no events, search-index changes or links are written. The note's inline links become SYNC links, which never collide with the copied MANUAL links.
- **Architecture.** `gtd.convert` calls only the other modules' services (`TaskService`, `NoteService`, `LinkService`, `ItemResolverRegistry`) and no repositories. The production code stays within the task's `touches`. The change to `web/src/app/api/generated/` is the client regeneration that AGENTS.md requires.
- **Security.** The endpoint goes through the standard security chain. Tests cover an anonymous request (401) and a missing CSRF token (403), and confirm that nothing changes in either case. Nothing is logged. Test data is fictitious.

## Review conclusion

No material issues found.
