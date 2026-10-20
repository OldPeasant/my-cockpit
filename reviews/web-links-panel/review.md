# Code Review

Date: 2026-10-20

Task: web-links-panel - Linked items panel (shared)

Scope: Final revision of the task, round 2: `git diff 3a4ef451..9bff3e37 -- web/` (all in `web/src/app/shared/links/`). Commits 91ae04e7 (implementation), dbab6d3a (test-writer tests, round-1 reports), 9bff3e37 (fixes for CR-1/TW-1 and CR-2). No uncommitted changes.

Requirements:
- tasks/web-links-panel.md acceptance criteria and recorded assumptions
- SPEC.md §4.3 (Universal items & links), §4.4 (Global search)
- api/openapi.yaml: `GET/POST /api/v1/links`, `DELETE /api/v1/links/{id}`, `GET /api/v1/search`
- docs/ARCHITECTURE.md, AGENTS.md project notes
- Round-1 review (this file, previous revision) and reviews/web-links-panel/fixes.md

Git range:
- Branch: main
- HEAD: 9bff3e37cc169c267365a3651f807c3c09d60b7d
- Base/start: 3a4ef4513a7beaedd6216d2976f96e2ab7b61a86
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Verification done by the reviewer: `npm --prefix web run check` (api check, lint, e2e typecheck, `test:ci`, `ng build`) passed: 27 test files, 426 tests passed. The build prints the known initial-bundle budget warning (501.24 kB against 500 kB). This task does not cause it, because no route or eagerly loaded module imports the panel yet.

Status of the round-1 findings:
- CR-1 (an older load overwrites a newer one): Fixed. `load()` now takes a sequence number (`loadSeq`, linked-items.ts:282). It applies its result or error only when it is still the latest load. Two tests cover the case where the older load of the same item resolves last and where it fails last (linked-items.spec.ts:434, :455). Verified.
- CR-2 (action error and open search survive an item change): Fixed. The item effect increments `itemSeq`, clears `actionError` and calls `resetSearch()` without moving the focus (linked-items.ts:150-159). `remove()` and `addLink()` ignore results that finish after an item change (lines 182-189, 252-262). The `changing the item` tests cover this, including an A -> B -> A change while a link is being created. Verified.
- CR-3 (error and conflict paths untested): Resolved. The fixer rejected it as already covered, and that is correct. The test-writer's tests cover all four paths: 409 on add (spec:984), another add error (spec:961), 404 on remove (spec:635), and a failed search (spec:771, :794).

Acceptance criteria assessment:
1. Listing (type icon, title, type label plus subtitle, `routerLink` deep link, `.unavailable` greying with an "Unavailable item" fallback) and removal of MANUAL links are implemented. SYNC links show "In text", as the recorded assumption says. Met.
2. "Add link" runs a debounced `switchMap` search over all types (no `types` filter). It excludes the item itself and items it is already linked to, and creates the link with `POST /links` by mouse or keyboard. The create-linked slot uses `[appLinkedCreate]` content projection plus a public `reload()`. Met.
3. Vitest component and service tests cover listing, adding via search, removal, and the error, race and focus paths. `npm run check` passes. Met.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-4 | A link added while the list is still loading can vanish when that load answers**
    - File: `web/src/app/shared/links/linked-items.ts:259`
    - Category: Correctness
    - Problem: "Add link" works while the list is still loading. That happens on first load, after `reload()`, after "Try again", or after an item change. On success, `addLink` appends the created link with `this.links.update(...)`. If a load started before the POST is still pending, it is still the latest load (`seq === this.loadSeq`). When it answers, it calls `this.links.set(links)` with a list that does not contain the new link. The link was created on the server but disappears from the panel until the next reload. While `links()` is null, the append also shows a one-row list in place of "Loading links…".
    - Why it matters: Two writers change `links` without a shared request identity, so the panel can briefly misreport state after a successful action. The window is small (load latency) and a reload repairs it, so this does not block the task.
    - Suggested fix: When `addLink` succeeds while a load is pending (`links() === null`), call `void this.reload()` instead of appending, or increment `loadSeq` and reload. Add a test that adds a link while the initial list request is still open.

## Review conclusion

No material issues found. The round-1 findings CR-1 and CR-2 are fixed and covered by tests. CR-3 was already covered. The one remaining finding, CR-4, is minor (P3) and can be fixed or recorded with a reason.
