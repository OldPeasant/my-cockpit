# Fix Report

Date: 2026-10-20

Task: web-links-panel - Linked items panel (shared)

Scope: current task; findings CR-1, CR-2, CR-3 from `reviews/web-links-panel/review.md` and TW-1 from `reviews/web-links-panel/tests.md` (base `2b4cd20bd437e180065cdc600de408452acd2401`, HEAD `949e8652`). Changes only in `web/src/app/shared/links/`.

## Fixed findings

### CR-1 / TW-1 — An older links load can overwrite a newer one for the same item

Files changed:
- `web/src/app/shared/links/linked-items.ts`
- `web/src/app/shared/links/linked-items.spec.ts`

Problem:
`load()` only checked `sameItem(item, this.item())`. An older list response or error for the same item (from `reload()`, the 409 path of "Add link", "Try again", or an A -> B -> A item change) that arrived last replaced the newer list or covered it with a load error.

Fix:
Each load takes a sequence number (`loadSeq`). Only the latest load applies its result or error. The now-unused `sameItem` helper was removed. The existing failing test `keeps the newer links when an older load of the same item answers last` was left as it was and now passes. Added the regression test `keeps the newer links when an older load of the same item fails last`.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/shared/links/**/*.spec.ts'` — Pass (3 files, 64 tests)

### CR-2 — Action error and open search survive an item change

Files changed:
- `web/src/app/shared/links/linked-items.ts`
- `web/src/app/shared/links/linked-items.spec.ts`

Problem:
When the `item` input changed, `actionError`, the open add-link search (`adding`, `query`, `active`) and late remove/add results from the previous item stayed in place. A remove that failed for item A showed its error on item B.

Fix:
The item effect now increments an item counter (`itemSeq`), clears `actionError` and closes the search through a new `resetSearch()`. `closeSearch()` reuses `resetSearch()` and still moves the focus to "Add link". The item-change path does not move the focus. `remove()` and `addLink()` record `itemSeq` when they start. If the item has changed by the time they finish, they ignore the result: no drop, no error, no 409 reload, no closeSearch focus move. This replaces the earlier `sameItem` check in `addLink`, so an A -> B -> A change is also treated as stale; the reload of the new item shows the server state. Added tests in a new `changing the item` group:
- `clears the action error of the previous item`
- `closes the open search without moving the focus`
- `ignores a removal error of the previous item`
- `ignores a linking error of the previous item`

Validation:
- `cd web && npx ng test --watch=false --include='src/app/shared/links/**/*.spec.ts'` — Pass (3 files, 64 tests)

## Not fixed

### CR-3 — Error and conflict paths of add and remove are untested

Status:
Invalid (no longer valid)

Reason:
The test-writer's tests (commit `949e8652`) already cover all four paths in `web/src/app/shared/links/linked-items.spec.ts`:
- 409 on add: `adding > reloads the list when the link already exists (409)`
- other add error: `adding > keeps the search open and shows the error when linking fails` (422)
- 404 on remove: `removing > drops a link that was already removed elsewhere (404) without an error`
- search error: `searching > shows the search error and recovers with the next query`, `shows a generic message when the search fails unexpectedly`

All of these pass, so no test was added.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/shared/links/**/*.spec.ts'` — Pass: 3 files, 64 tests (59 before plus 5 new)

Full test suite:
- `cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, ng build) — Pass. `ng build` prints the known initial-bundle budget warning (501.24 kB against 500 kB). The review already records it, and this task does not cause it.

Lint/type-check/build:
- Covered by `cd web && npm run check` — Pass

`.taskcheck`:
- `sh scripts/check.sh` — Pass (exit 0, "all checks passed"; web part 27 files, 423 tests). The first of three runs failed with a Gradle `BUILD FAILED` in a server or android part, and its details were not captured. The next two runs passed with no code change. That part is outside `web/`, so the failure looks transient and is not caused by this change.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
