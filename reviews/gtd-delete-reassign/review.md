# Code Review

Date: 2026-10-26

Task: gtd-delete-reassign - Server-side reassignment when deleting a context or area

Scope: committed task changes `622d30a690e3f98a5a951d6f586c621ee12d0a7d..7cf0249f2` limited to `server`, `web`, `api` (commits cd9f614ab server/API/generated client, 7cf0249f2 web settings). These are the rebased versions of the 75968e4c9 / 04b6f5c1d commits named by the parent worker. Test files the parallel test-writer has not yet committed are out of scope.

Requirements:
- tasks/gtd-delete-reassign.md acceptance criteria and recorded assumptions (self-target -> 400; dialog count still counts visible items only)
- SPEC.md §4.2 conventions (Problem Details, ETag/If-Match), §5.1 contexts/areas
- docs/ARCHITECTURE.md, AGENTS.md project notes (module boundaries, generated API files, lint)
- Existing code conventions in `gtd` module and `web/src/app/features/settings/gtd`

Git range:
- Branch: main
- HEAD: 7cf0249f2
- Base/start: 622d30a690e3f98a5a951d6f586c621ee12d0a7d
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A replacement target deleted elsewhere gives an unhelpful 400 and leaves the stale list in place**
    - File: `web/src/app/features/settings/gtd/gtd-settings.ts:326`
    - Category: Correctness
    - Problem: The new server contract answers `replaceWith`/`moveTo` naming a context/area that does not exist (for example one deleted on another device after the list loaded) with a 400 field error. Its Problem Details `detail` is the generic "Request validation failed" (`InvalidRequestException` default message, `server/.../core/web/ApiExceptions.kt:38`). `isStale()` treats only 404/412 as stale, so for this 400 the section shows "Request validation failed" and does not reload the list. The deleted target stays in the list and can be chosen again in the next delete dialog.
    - Why it matters: This failure mode is new with the parameter. The user gets a message that does not explain what went wrong, and the screen keeps offering a target that no longer exists until a manual reload. Recovery is still possible because a different target can be chosen, so this is low severity.
    - Suggested fix: When a delete with a target fails with a 400 whose field error is on `replaceWith`/`moveTo`, reload the list and show a specific localized message (for example "The context to move to no longer exists; the list now shows the current state."). Another option is to give the server-side exception a specific `detail`.

## Notes (verified, no finding)

- Server: `ContextService.delete` and `AreaService.delete` check the target (self or nonexistent -> `InvalidRequestException` on the parameter field) after the If-Match check and before any write. Both methods run in the class-level `@Transactional`, so a 400 changes nothing. `findAllWithContext` / `findByAreaId` include tickler and Trash rows, and `Task.contextIds` is a `MutableSet`, so a task that already has the replacement keeps it only once. Change events are published only for rows not in the Trash, the same as the existing delete behavior. Tasks have no area, so moving projects is the full area reassignment.
- Integration tests (`AreaContextWebTests`, `@SpringBootTest` + MockMvc against the real DB) cover reassignment of plain, already-has-target, tickler and trashed tasks, trashed projects, version bump, events, unknown/self target -> 400 with nothing changed, and a malformed UUID for `replaceWith`.
- OpenAPI: both query parameters and the 400 response are documented. The regenerated `web/src/app/api/generated/fn/projects/delete-{context,area}.ts` match the spec. No hand edits found.
- Web: `GtdSettingsService` sends `replaceWith`/`moveTo` (omitted when `null`) and no longer patches tasks/projects. `ReassignError`, its messages, and the tickler/Trash hint with its `aria-describedby` and `.hint` style are removed, with no references left in `web/src`. `tasksWithContext`/`projectsInArea`/`PAGE_SIZE` are still used for the dialog count, which matches the recorded assumption. The Vitest specs assert the query parameters and that no PATCH or list call is made.
- Evidence: `python tasks.py verify gtd-delete-reassign` shows neither criterion recorded yet. The parent worker must record the server test run, `cd web && npm run check`, and the openapi regeneration before `done`.

## Review conclusion

No material issues found.
