# Code Review

Date: 2026-10-20

Task: web-gtd-data - Web GTD data layer and shared task components

Scope: The full task scope is `git diff f43a070a..307eb6e5 -- web/`: signal-based GTD stores in `web/src/app/features/gtd/data`, shared task UI in `web/src/app/features/gtd/ui`, and their specs. This is the third review pass, on the final code. Since the second pass at bfec77d5, the only new commit is 307eb6e5. It changes only `web/src/app/features/gtd/data/context-store.spec.ts`, `web/src/app/features/gtd/data/project-store.spec.ts` and `reviews/`. Production code has not changed since the second pass, so the earlier production-code review still holds. This pass reviewed `git diff bfec77d5..307eb6e5 -- web/` in detail. Committed changes only.

Reviewed revision: 307eb6e56bae4ce9452245f7ed82d139b574ecee

Requirements:
- tasks/web-gtd-data.md acceptance criteria 1-4
- SPEC.md §4.2 (version + ETag, updates require If-Match, 409/412 on conflict), UI strings externalized
- docs/ARCHITECTURE.md (Angular standalone, signals, zoneless, `@angular/localize`, feature folders)
- AGENTS.md project notes
- Previous revisions of this report and reviews/web-gtd-data/fixes.md
- Parent instructions: review the spec-only commit 307eb6e5 and confirm that the reviewed revision is final

Git range:
- Branch: main
- HEAD: 307eb6e56bae4ce9452245f7ed82d139b574ecee
- Base/start: f43a070a4c7e449d542c2516b3222ee31b1d1ae8
- Commits reviewed: 6 (b9662dc7, e5ad6f05, e0ca00be, d63a6324, bfec77d5, 307eb6e5)
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Verification of earlier findings

- CR-1 (If-Match used the newest known version): fixed in bfec77d5. `ListStore.ifMatch` builds the ETag from the version of the caller's copy. 307eb6e5 adds tests for the same rule in ContextStore (`update` and `remove` with a stale v1 copy against a loaded v4 copy send `"1"`) and in ProjectStore (`setNextStep`, `update` and `remove` with a stale copy send `"1"`, and `find('p')` sends `"5"`). These tests close the remaining coverage gap for the project and context mutations.
- CR-2 (`ensureLoaded` resolved before a running load finished): fixed in bfec77d5. 307eb6e5 adds failure-path tests. In ContextStore, two waiting callers both settle when the shared load fails, `loaded()` stays false, `error()` is set, and a retry by two callers sends only one request. In ProjectStore, the second page fails during a multi-page shared load, both callers settle, `loading()` returns to false, and a retry starts again at the first page with no `cursor`. These tests match `ensureLoaded` in `project-store.ts:41-45` (`pendingLoad` is cleared in `finally`) and the existing `refresh` error handling.
- CR-3 (no coalescing of change-event refetches): the parent worker accepted this earlier. It is a performance concern, not an acceptance criterion, and it is not reopened.
- CR-4 (ProjectStore and ContextStore untested): resolved earlier, and 307eb6e5 adds more coverage.

## New issues checked

- The new tests check behavior through the mocked `Api.invoke` boundary: request parameters and store signals. They do not check private state. The helpers set default `version: 1`, which the stale-copy assertions depend on. The call-index assertions (`toHaveLength(2)` and `toHaveLength(3)`) match the number of requests in each scenario.
- Some mutation promises in the If-Match tests stay pending (`void store.update(...)`). They never reject, so no unhandled rejection leaks between tests. No finding.
- No production, configuration or dependency files changed in 307eb6e5.

I did not run the test suite in this pass, because the reviewer runs only read-only commands. The parent worker must run `cd web && npm run check` and record the result.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. The final revision 307eb6e56bae4ce9452245f7ed82d139b574ecee only adds sound tests for the CR-1 and CR-2 fixes, and production code is unchanged since the previous pass. Before `done`, the parent worker must record evidence for each acceptance criterion with `tasks.py verify`, including an actual run of `cd web && npm run check` for criterion 4.
