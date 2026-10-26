# Code Review

Date: 2026-10-26

Task: gtd-delete-reassign - Server-side reassignment when deleting a context or area

Scope: committed task changes `b8313a9c1697e6a9772b7ab5a848d8eeeef74155..6b98e9b573e9b8c2a8bca317f9068889120617d6` (server, `api/openapi.yaml`, generated web client, web settings, tests). Review reports under `reviews/` and any uncommitted test-writer files are out of scope.

Requirements:
- tasks/gtd-delete-reassign.md acceptance criteria and recorded assumptions (self-target -> 400; the dialog count still counts visible items only)
- SPEC.md §4.2 conventions (Problem Details, ETag/If-Match)
- docs/ARCHITECTURE.md and AGENTS.md project notes (module boundaries, generated API files, i18n, lint)
- reviews/gtd-delete-reassign/fixes.md (fix of the earlier finding CR-1)
- Existing conventions in the `gtd` server module and `web/src/app/features/settings/gtd`

Git range:
- Branch: main
- HEAD: 6b98e9b573e9b8c2a8bca317f9068889120617d6
- Base/start: b8313a9c1697e6a9772b7ab5a848d8eeeef74155
- Commits reviewed: 4 (a2a31ae9c, 833f9a771, 4bf184acd, 6b98e9b57)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Notes (verified, no finding)

- Server: `ContextService.delete` and `AreaService.delete` load the row (404), check If-Match (428/412), and then validate the target. A self or nonexistent target raises `InvalidRequestException` on the `replaceWith`/`moveTo` field (400). All of this happens before any write. Both services have class-level `@Transactional`, so the reassignment and the delete run in one transaction. `findAllWithContext` (a join on `task_context`) and `findByAreaId` have no list, tickler or Trash filter, so hidden rows are reached. `Task.contextIds` is a `MutableSet`, so a task that already has the replacement keeps it only once. Change events go out only for rows not in the Trash, the same as the existing delete without a target. Tasks have no area, so moving projects is the complete area reassignment.
- Integration tests (`AreaContextWebTests`, MockMvc against the real DB) cover: plain, already-has-target, tickler, trashed and done tasks; trashed and completed projects; untouched rows of other contexts/areas; version bumps; change events (none for Trash rows); unknown, self and malformed targets -> 400 with nothing changed; 428/412 taking precedence over target validation; 404 for an unknown context/area; and a replacement without tasks keeping its ETag.
- OpenAPI and client: both optional query parameters and the 400 Problem Details response are documented. The regenerated `web/src/app/api/generated/fn/projects/delete-{context,area}.ts` match the spec and show no hand edits. Per fixes.md, `npm run check` includes `api:check`.
- Web: `GtdSettingsService` sends `replaceWith`/`moveTo` (omitted when `null`) and no longer patches tasks/projects. `ReassignError`, its messages, the tickler/Trash hint, its `aria-describedby` and its `.hint` style are all removed, and nothing in `web`, `server` or `android` still references them. `tasksWithContext`/`projectsInArea` still feed the dialog count, as the recorded assumption says.
- Fix of the earlier CR-1 (6b98e9b57): `isMissingTarget` matches only a 400 whose field errors name the parameter for that list kind. `save()` then reloads the list and shows a localized, specific message. Any other 400 still shows the server message and does not reload. Loading/busy state is cleared in `run()`'s `finally` on every path. The Vitest specs cover the context and area variants and the negative case (a 400 with a different field). New `$localize` ids follow the existing `@@gtdSettings.*` scheme. `messages.xlf` is extracted separately in this repo, so not updating it is consistent.
- Evidence: `python tasks.py verify gtd-delete-reassign` still shows both criteria unrecorded. Before `done`, the parent worker must record the actual server integration test run (`cd server && ./gradlew check` or the focused `AreaContextWebTests`), `cd web && npm run check`, and the OpenAPI regeneration check.

## Review conclusion

No material issues found.
