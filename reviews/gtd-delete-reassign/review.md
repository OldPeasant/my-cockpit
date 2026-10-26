# Code Review

Date: 2026-10-26

Task: gtd-delete-reassign - Server-side reassignment when deleting a context or area

Scope: committed task changes `01da15b13629869cc93cdd0ba96c94bfc8e9ab57..4d2582b4785c9e5feb8580e06fa0d96ee335164b` (server `gtd` module, `api/openapi.yaml`, generated web client, `web/src/app/features/settings/gtd`, server and web tests). The heartbeat commit ec7f5b466 (`tasks/mail-connection-errors.md`) is in the range but does not belong to the task and was not reviewed. Reports under `reviews/` and the uncommitted `reviews/gtd-delete-reassign/tests.md` were read as context only. This review covers the whole final code. It looks most closely at the two commits added after the previous review: the new `AreaContextDeleteRollbackTests.kt` and the helper tidy in `AreaContextWebTests.kt`.

Requirements:
- tasks/gtd-delete-reassign.md acceptance criteria 1 and 2, and the recorded assumptions (a self target is a 400 like an unknown id; the dialog count still counts visible items only)
- SPEC.md §4.2 conventions (Problem Details, ETag/If-Match)
- docs/ARCHITECTURE.md and the AGENTS.md project notes (module boundaries, generated API files, lint, fictitious test data)
- reviews/gtd-delete-reassign/fixes.md (fix of the earlier finding CR-1)
- Existing conventions in the `gtd` server module tests (per-class `reset()` SQL cleanup, `@MockitoSpyBean` rollback tests such as `convert/TaskToNoteRollbackTests.kt`) and in `web/src/app/features/settings/gtd`

Git range:
- Branch: main
- HEAD: 4d2582b4785c9e5feb8580e06fa0d96ee335164b
- Base/start: 01da15b13629869cc93cdd0ba96c94bfc8e9ab57
- Commits reviewed: 6 (e803354d2, 415528dee, ec174c9aa, b909c8570, d1e0a22a8, 4d2582b47)
- Uncommitted changes: only the untracked `reviews/gtd-delete-reassign/tests.md` (not reviewed as code)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## History

- An earlier review of the pre-rebase range ending at 6b98e9b57 reported CR-1. When the server rejected a target that had been deleted elsewhere, the web settings showed a generic error and did not reload. The fixer resolved it (see fixes.md): `isMissingTarget` now matches the 400, the list reloads and a localized message explains what happened. A re-review on that range found no issues. That fix is still in place and correct on the current HEAD (`web/src/app/features/settings/gtd/gtd-settings.ts:279-283,331-346`).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-2 | `AreaContextWebTests` helpers are only partly used and sit among the tests**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/gtd/AreaContextWebTests.kt:338`, `:359-368`
    - Category: Quality
    - Problem: The tidy commit 4d2582b47 replaced the local `contextsOf` with the class-level helper. However, `deleting an area with moveTo moves all its projects, also those in the Trash` still inlines the same `select area_id::text from project ...` query that `areaOf` (line 367) wraps. The new private helpers `versionOf`, `contextsOf` and `areaOf` are also declared between two `@Test` methods, not in the class's existing `// --- helpers` section (line 51).
    - Why it matters: The tidy is incomplete. The duplicated SQL and helpers placed among the tests make the file harder to scan and to keep in step.
    - Suggested fix: Use `areaOf(it) shouldBe family.id()` at line 338. Move the three helpers into the `// --- helpers` section. This is optional and changes only test code.

## Notes (verified, no finding)

- **Criterion 1, server:** `ContextService.delete` and `AreaService.delete` run in this order: load the row (404), check If-Match (428/412), then validate the target. A self or nonexistent target raises `InvalidRequestException` on the `replaceWith`/`moveTo` field (400 Problem Details). All checks happen before any write. A malformed UUID gives a 400 through Spring's type conversion, and tests cover it. Both services are class-level `@Transactional`. `findAllWithContext` and `findByAreaId` do not filter on list, tickler or Trash. `contextIds` is a set, so a task that already has the replacement keeps it only once. Change events are sent only for rows that are not in the Trash, as in the existing delete without a target.
- **New `AreaContextDeleteRollbackTests`:**
    - *Correctness:* The `@MockitoSpyBean` on `ChangeEventPublisher` stubs `publish(String, UUID, ChangeOp)` with `eq("CONTEXT"|"AREA")` and `DELETED`. That matches the overload the services call: `CONTEXT` and `AREA` are `String` constants, `ContextService.kt:146` and `AreaService.kt:119`. The services flush the reassignment and the delete before they publish. The test therefore proves that SQL that already reached the database is rolled back, both the context/area links and the version bumps, and that the context or area still exists afterwards. The `?:` fallbacks around `eq`/`any` follow the established Kotlin and Mockito null workaround. Each test includes a Trash row, so it also covers hidden items.
    - *Isolation and cleanup:* `@BeforeEach`/`@AfterEach` `reset()` uses the same table list as `AreaContextWebTests` (`next_task_id` is cleared before rows are deleted, which respects the FKs). `@MockitoSpyBean` resets its stubbing after each test by default. The extra Spring context follows the precedent of `TaskToNoteRollbackTests`/`TaskToEventRollbackTests`, and `PostgresTestBase` allows 300 connections for cached contexts.
    - *Flakiness and data:* The tests have no clock or ordering dependence. Each element list has a single entry, and the data is fictitious ("Call the plumber", "Renovate kitchen", "fictitious event bus failure").
- **`AreaContextWebTests` additions:** MockMvc against the real DB. They cover plain, already-has-target, tickler (`tickler_date` 2099), trashed and done tasks; trashed and completed projects; untouched rows of other contexts and areas; version bumps; change events (none for Trash rows); unknown, self and malformed targets -> 400 with nothing changed or announced; 428/412 checked before the target; 404 for an unknown id; and a replacement without tasks that keeps its ETag. The `shouldContainExactly` order of the context and area lists follows the user's ordering, which is deterministic here.
- **Criterion 2:** `api/openapi.yaml` documents both optional query parameters and the 400 response. The regenerated `web/src/app/api/generated/fn/projects/delete-{context,area}.ts` match the spec. `GtdSettingsService` sends `replaceWith`/`moveTo`, omitting each when it is `null`, and no longer patches tasks or projects. The tickler/Trash hint, its `aria-describedby`, its `.hint` style and `ReassignError` are removed, and nothing in `web`, `server` or `android` still references them. Vitest specs were rewritten for the new behavior. The removed cases tested behavior that no longer exists, and none is skipped or `.only`.
- **Concurrency:** if the target context or area is deleted concurrently between `existsById` and the reassignment write, a foreign-key error could result instead of a 400. This is a narrow race in a single-user app, and the transaction still rolls back, so it is not reported as a finding.
- **Evidence:** `python tasks.py verify gtd-delete-reassign` still shows both criteria unrecorded. Before `done`, the parent worker must record the server integration test run it reported (`server/gradlew -p server test --tests 'ch.sonensei.cockpit.gtd.*' ...` -> 419 tests, 0 failures) and the `npm --prefix web run check` result, which includes `api:check`.

## Review conclusion

No material issues found.
