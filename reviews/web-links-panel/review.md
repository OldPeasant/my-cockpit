# Code Review

Date: 2026-10-20

Task: web-links-panel - Linked items panel (shared)

Scope: Task commit 8569b14f on top of base 2b4cd20b (`git diff 2b4cd20b..8569b14f`), all in `web/src/app/shared/links/`

Requirements:
- tasks/web-links-panel.md acceptance criteria and recorded assumptions
- SPEC.md §4.3 (Universal items & links), §4.4 (Global search)
- api/openapi.yaml: `GET/POST /api/v1/links`, `DELETE /api/v1/links/{id}`, `GET /api/v1/search`, schemas `LinkResponse`, `LinkEndResponse`, `SearchGroupResponse`, `SearchItemResponse`
- docs/ARCHITECTURE.md (web stack, i18n conventions), AGENTS.md project notes

Git range:
- Branch: main
- HEAD: 8569b14f
- Base/start: 2b4cd20bd437e180065cdc600de408452acd2401
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 1 P2, 2 P3

Verification done by the reviewer: `cd web && npm run check` ran: api check, lint, e2e typecheck, `test:ci` (26 files, 376 tests passed), and `ng build` all passed. The build printed an initial-bundle budget warning (exceeded by 1.24 kB). This task did not cause it: no route or eagerly loaded module imports the panel yet.

Acceptance criteria assessment:
1. Listing (icon by type, title, type and subtitle, `routerLink` deep link, `.unavailable` greying with "Unavailable item" fallback) and removal of MANUAL links are implemented. They match the `LinkEndResponse` contract, where unavailable ends carry only type and id. SYNC links are not removable, as the recorded assumption says, which matches the documented 409 on DELETE. Met.
2. "Add link" uses a debounced (200 ms) `switchMap` search over all types (no `types` parameter, limit 20). Results exclude the item itself and items it is already linked to. The selected result is created through `POST /links`, by mouse or keyboard. The create-linked slot uses content projection of `[appLinkedCreate]` together with a public `reload()`. Met.
3. Vitest tests cover listing, unavailable/SYNC rendering, the empty state, removal and its error, search plus add via mouse and keyboard, Escape, the create slot and reload, item change, and load error with retry. The service tests cover all four calls and that errors are not toasted. `npm run check` passes. Met. Smaller gaps are listed under CR-3.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | An older links load can overwrite a newer one for the same item**
    - File: `web/src/app/shared/links/linked-items.ts:260`
    - Category: Correctness
    - Problem: `load()` only checks that the response is for the current item (`sameItem(item, this.item())`). It does not check that the response belongs to the latest request. Several things can start overlapping loads for the same item: `reload()` from a feature's create-linked action, the 409 path in `addLink` (`void this.reload()`, line 251), the "Try again" button, or the item input going A -> B -> A. When an older request resolves last, it replaces the newer list. A link created moments earlier can then disappear from the panel. If the older request fails last, `loadError` is set over a successful newer result. The template gives `loadError` precedence, so the user sees an error screen for data that actually loaded.
    - Why it matters: Shared state can be overwritten by out-of-order async results. This is the specific stale-response defect the review checklist names, including the case where the older request fails. Every detail view will host this panel, and features are told to call `reload()` after creating linked items, so overlapping loads are a normal-use case.
    - Suggested fix: Give each load a request identity, e.g. `const seq = ++this.loadSeq;`. Apply the result or the error only when `seq === this.loadSeq`, and use that instead of (or in addition to) `sameItem`. Add a test with two overlapping loads where the first one resolves (or fails) last.

## P3 Low

- [ ] **CR-2 | Action error and open search survive an item change**
    - File: `web/src/app/shared/links/linked-items.ts:145`
    - Category: Correctness
    - Problem: When `item` changes, the effect calls `load()`, which resets only `links` and `loadError`. `actionError` (for example "Remove it in its source." from item A), `adding`/`query` and the search results stay in place. A remove that fails for item A after the switch also writes its error into item B's panel (lines 178-179).
    - Why it matters: When a detail view reuses the panel for another item (a route parameter change), the user sees an error that belongs to the previous item.
    - Suggested fix: In the item effect, also clear `actionError` and close the search (`adding`, `query`, `active`). Optionally ignore remove errors whose request started for a different item.

- [ ] **CR-3 | Error and conflict paths of add and remove are untested**
    - File: `web/src/app/shared/links/linked-items.spec.ts`
    - Category: Tests
    - Problem: No test covers four handled paths: `addLink` receiving 409 (closes the search and reloads; line 248), `addLink` failing with another error (shows `actionError` and keeps the search open), `remove` receiving 404 (drops the row; line 178), and a failed search (shows `searchError` in the status line).
    - Why it matters: These are the deliberate recovery behaviours for concurrent edits from another tab or device, and they would regress silently. The acceptance criteria name only listing, adding and removal, so this does not block the task.
    - Suggested fix: Add one focused component test per path, flushing the matching Problem Details status through `HttpTestingController`.

## Review conclusion

Material issues found; fix before completing the task. CR-1, the stale-load race, should be fixed and covered by a test. CR-2 and CR-3 are minor and can be fixed now or recorded with a reason.
