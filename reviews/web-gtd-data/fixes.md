# Fix Report

Date: 2026-10-20

Task: web-gtd-data - Web GTD data layer and shared task components

Scope: current task. These are the findings CR-1 and CR-2 from `reviews/web-gtd-data/review.md`, in `web/src/app/features/gtd/data`. The parent worker chose to leave CR-3 and CR-4 alone (see "Not fixed").

## Fixed findings

### CR-1 — If-Match sent the newest known version, not the caller's copy (P2)

Files changed:
- `web/src/app/features/gtd/data/list-store.ts`
- `web/src/app/features/gtd/data/task-store.spec.ts`

Problem:
`ListStore.ifMatch(item)` sent `etag(max(item.version, <version in the list>))`. Suppose a caller kept an old copy and a refetch then loaded a newer version. The caller's change would carry the newer ETag, and the server would accept it, overwriting the newer version. This breaks optimistic locking (SPEC.md §4.2).

Fix:
`ifMatch(item)` now returns `etag(item.version)`, the version of the caller's copy. The doc comment now explains that a stale copy gets a conflict (412). It also says that callers who want the latest state should pass `find(id)`, and that views render from `items()`, so their copies are current. In `task-store.spec.ts`, the test "sends the newest known version as If-Match" is replaced by "sends the version of the caller's copy as If-Match, so a stale copy gets a conflict". It checks that a stale v1 copy sends `"1"` while the list holds v3, and that `store.find('a')` sends `"3"`. No other spec relied on the old behavior: every other If-Match assertion uses a copy whose version matches the list. The project and context stores use the same `ifMatch`, so they get this fix too.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/gtd/**/*.spec.ts'` — Pass (8 files, 79 tests)

### CR-2 — `ensureLoaded()` returned before the data arrived when a load was already running (P3)

Files changed:
- `web/src/app/features/gtd/data/context-store.ts`
- `web/src/app/features/gtd/data/project-store.ts`
- `web/src/app/features/gtd/data/context-store.spec.ts`
- `web/src/app/features/gtd/data/project-store.spec.ts`

Problem:
`if (!this.loaded() && !this.loading()) await this.load({})` returned at once for a second caller while a load was running, so that caller would see an empty `items()`.

Fix:
Each store keeps its running `ensureLoaded` load in `private pendingLoad?: Promise<void>`. Callers that arrive during the load get the same promise. The promise is cleared in `finally`, so a failed load can be retried, and once the store is loaded the method returns a resolved promise. Doc comments are updated.

Tests:
- `context-store.spec.ts`: the test "does not start a second request while loading" is replaced by "lets a second caller wait for the running load instead of starting another". It checks that only one request is sent and that the second caller does not resolve before the answer arrives, then sees the items. A retry-after-failure test is also added.
- `project-store.spec.ts`: the new test "lets a second caller wait until the running load has all pages" checks that the second caller is still waiting after the first page and resolves only after the last cursor page. The existing test "loads all projects once" did `await store.ensureLoaded()` on the second call. With the fix, that call now correctly waits for the load, which would deadlock the test, so it was changed to `void store.ensureLoaded()`. The assertion that only one request is made is kept.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/gtd/**/*.spec.ts'` — Pass (8 files, 79 tests)

Remaining edge case, not covered:
If a change event triggers `refresh()` while the first load is running, the first answer is superseded, and `load()` (and so `ensureLoaded()`) resolves as soon as its own request settles, not when the newer one does. This is rare: an SSE event has to arrive during the first load. Fixing it would mean `ListStore.refresh()` tracking the newest request's promise, which goes beyond the requested fix.

## Not fixed

### CR-3 — Change events refetch with no coalescing

Status:
Out of scope (the parent worker accepted it as is)

Reason:
The parent worker told me not to address it.

### CR-4 — ProjectStore and ContextStore were untested

Status:
Invalid (finding no longer valid)

Reason:
The parent worker says it is already covered: `project-store.spec.ts` and `context-store.spec.ts` now exist. The CR-2 tests above add to them.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/gtd/**/*.spec.ts'` — Pass (8 files, 79 tests)

Full test suite:
- `cd web && npm run check` — Pass (lint clean, 41 files / 628 tests passed, build ok). The build shows a warning that the initial bundle exceeds its budget (599.10 kB of 500 kB). These fixes did not cause it; they only change store logic.

Lint/type-check/build:
- `cd web && npm run lint` — Pass ("All files pass linting.")

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("all checks passed")

Remaining unresolved findings:
0 of the findings assigned to the fixer (CR-3 was accepted as is, CR-4 was already covered)

## Final status

All validated findings fixed and validation passed.
