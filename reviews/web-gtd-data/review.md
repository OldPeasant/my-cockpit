# Code Review

Date: 2026-10-20

Task: web-gtd-data - Web GTD data layer and shared task components

Scope: `git diff f43a070a..HEAD -- web/` (second review, on the final code after fixes). Signal-based GTD stores in `web/src/app/features/gtd/data`, shared task UI in `web/src/app/features/gtd/ui`, and their specs. The review focused on the fix commit bfec77d5 and the test additions in d63a6324 since the first review (HEAD e0ca00be). Committed changes only.

Requirements:
- tasks/web-gtd-data.md acceptance criteria 1-4
- SPEC.md §4.2 (version + ETag, updates require If-Match, 409/412 on conflict), UI strings externalized
- docs/ARCHITECTURE.md (Angular standalone, signals, zoneless, `@angular/localize`, feature folders)
- AGENTS.md project notes
- Previous report (first revision of this file) and reviews/web-gtd-data/fixes.md
- Parent instructions: verify the fixes for CR-1 and CR-2, the acceptance of CR-3, CR-4 coverage, and look for new issues

Git range:
- Branch: main
- HEAD: bfec77d57c5c7a555e23ab8a63a023fe17265e35
- Base/start: f43a070a4c7e449d542c2516b3222ee31b1d1ae8
- Commits reviewed: 5 (b9662dc7, e5ad6f05, e0ca00be, d63a6324, bfec77d5)
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Verification of earlier findings

- CR-1 (If-Match used the newest known version), fixed. `ListStore.ifMatch` (`list-store.ts:145-147`) now returns `etag(item.version)` from the caller's copy. Every mutation in the task, project and context stores goes through `ifMatch(<caller's item>)`, so a stale copy now gets a 412 from the server instead of a silent overwrite, as SPEC.md §4.2 requires. `mutate` rolls back the overlay in `finally` and rethrows the error, so the 412 reaches the caller and the UI returns to the server state. The test `task-store.spec.ts:223-232` asserts that a stale v1 copy sends `"1"` and `find('a')` sends `"3"`. No production code outside these stores calls `ifMatch`.
- CR-2 (`ensureLoaded` resolved early during a running load), fixed. `context-store.ts:26-37` and `project-store.ts:34-45` share one `pendingLoad` promise among concurrent callers and clear it in `finally`. `load()` never rejects (`refresh` catches errors), so a failed load resolves with `loaded() === false` and can be retried. The new tests cover a waiting second caller (including the multi-page project load) and a retry after a failure. The changed `void store.ensureLoaded()` in the existing project test still asserts that only one request is sent. The fixer documented the remaining rare case: an SSE refetch that supersedes the first load makes `ensureLoaded` resolve before the newer answer applies. Because the newer refetch then fills `items()` reactively, I do not report it as a separate finding.
- CR-3 (no coalescing of change-event refetches): the parent worker accepted it. It is a performance concern and not an acceptance criterion, so I am not reopening it.
- CR-4 (ProjectStore and ContextStore untested), resolved. `project-store.spec.ts` (277 lines) and `context-store.spec.ts` (186 lines) now exist in the range. They cover `setNextStep` with optimistic `stalled` and rollback, related-type TASK events refetching instead of dropping, the `ensureLoaded` paths, and the filters.

## New issues checked

- Two quick mutations of one item: the optimistic overlay keeps the old `version`, so a second mutation sent while the first is still running carries the old ETag and gets a 412 with rollback. The old `max()` code behaved the same way, because `server()` updates only when the answer arrives. This is not a regression, and it matches the locking contract. No finding.
- After an answer is accepted, `items()` holds the new version, so views that render from the store send the current ETag. This is consistent with the new doc comment.

I could not run the test suite: the sandbox denied `npx ng test`. Evidence for the tests comes from fixes.md (79 focused tests and `npm run check` passing) and from reading the specs. The parent worker must run the tests and record the results.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. CR-1 and CR-2 are fixed correctly with matching tests, CR-4 is covered, and CR-3 was accepted by the parent worker. All 4 acceptance criteria are still unticked in `tasks.py verify web-gtd-data`. The parent worker must record evidence for each one, including actually running `cd web && npm run check` for criterion 4, before `done`.
