# Code Review

Date: 2026-10-21

Task: gtd-convert-note - Convert task to reference note

Scope: implementation commit a9786319 on top of base 86d5edde (`git diff 86d5edde..a9786319`); production code reviewed at a9786319, committed changes only

Requirements:
- tasks/gtd-convert-note.md acceptance criteria and recorded assumptions (soft delete to Trash; links copied as MANUAL with same direction/label, links to missing/trashed items skipped; only title + notes carried over; If-Match optional)
- SPEC.md §4.2 (ETag/If-Match, Problem Details), §4.3 (items and links), §5.1 (Reference converts into a Note), §5.4 (API sketch)
- docs/ARCHITECTURE.md conventions (modules talk through services/events, package layout)
- AGENTS.md project notes; existing conventions in gtd/api/TaskController.kt, notes/domain/NoteService.kt, core/items/domain/LinkService.kt

Git range:
- Branch: main
- HEAD: a9786319
- Base/start: 86d5edde2c3121699da919b3da5fe2af207425ee
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | No regression test for a task that is linked to the same item in more than one way, or whose notes carry inline links**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/gtd/convert/TaskToNoteWebTests.kt:120`
    - Category: Tests
    - Problem: `TaskToNoteService.convert` (`server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert/domain/TaskToNoteService.kt:45-56`) relies on two things the tests don't cover. First, `LinkService.linksOf` returns one link per linked item. Second, `NoteService.create` adds only SYNC links for inline `[[type:id]]` links in the body, so they never collide with the copied MANUAL links. The link test only uses three distinct items, each with a single link, and a body without inline links.
    - Why it matters: Suppose a later change to `linksOf` makes it return every link instead of one per item. A task with both a manual link and an inline backlink to the same item would then call `LinkService.create` twice for one pair. The second call throws `ConflictException`, which rolls back the whole conversion and returns 409, so the user can never convert that task. Nothing would catch that regression.
    - Suggested fix: Add a test where the task has a manual link to item X and X also has a SYNC link back to the task. Add a second case where the task's notes contain `[[note:X]]` and the task also has a manual link to X. Assert that the conversion succeeds with 201 and that the note ends up with exactly the expected links.

## Notes (not findings)

- Correctness: the conversion runs in one transaction because the service class is `@Transactional`. The trash step, note creation and link copies all join that transaction, and the rollback test shows that a failure after the note was created undoes both the note and the trash step. The check that a linked item still exists is done before `LinkService.create`, so the 400 that `create` would throw for a missing item cannot mark the transaction rollback-only. `TaskService.get` returns 404 for unknown and trashed tasks. If-Match is checked inside `TaskService.delete` before any change, matching the optional-If-Match workflow actions (`complete`/`move`). Two concurrent conversions of the same task end in a 404, or in an optimistic-lock 409 for the second one, never in two notes. Task and note limits match (title 500, body/notes 100 000), so the documented 400 is a safeguard only.
- Integration: the new `openapi.yaml` path, operationId, responses and `Location`/`ETag` headers match the controller. The diff only adds a path, which fits a regenerated spec. This review did not run `OpenApiContractTest` (it is criterion 3 and needs evidence from the parent worker).
- Architecture: `gtd.convert` uses only the services of other modules (`NoteService`, `LinkService`, `ItemResolverRegistry`) and no repositories. It stays within the task's `touches`.
- Evidence: `python tasks.py verify gtd-convert-note` currently shows all three criteria unverified. The parent worker still has to record them.

## Review conclusion

No material issues found.
