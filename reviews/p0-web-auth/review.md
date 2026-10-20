# Code Review

Date: 2026-10-20

Task: p0-web-auth - Web setup and login pages

Scope: committed changes of the task at the final revision 1d7dd9c129338970f1ced186ab542f11ee0c7499, `git diff babb06f4..1d7dd9c -- web docs` (commits e0d4772, 677d9ec, 75edee3, 2a12481, 1d7dd9c). This is the third review pass. The second pass fully reviewed revision 2a12481 (all task code, fixes for CR-1 to CR-4). This pass reviews the delta `2a12481..1d7dd9c`, which adds two tests from the test-writer's second pass and updated reports. It also confirms that no production code changed after 2a12481. Uncommitted working-tree changes were not reviewed (there were none at review time).

Requirements:
- tasks/p0-web-auth.md acceptance criteria 1-4 and recorded assumptions (#1-#6)
- SPEC.md §4.1 (setup flow, session cookie, CSRF double-submit, remember device, login backoff)
- api/openapi.yaml (auth, setup endpoints; 429 with `Retry-After`)
- docs/ARCHITECTURE.md (web stack, i18n via `@angular/localize`, conventions), AGENTS.md project notes
- reviews/p0-web-auth/fixes.md (fixer report for CR-1 to CR-4)

Git range:
- Branch: main
- HEAD: 1d7dd9c129338970f1ced186ab542f11ee0c7499
- Base/start: babb06f4ff5c279f43422fb658dd7be5eb9bacfc
- Commits reviewed: 5 (e0d4772, 677d9ec, 75edee3, 2a12481, 1d7dd9c). The second pass reviewed e0d4772 to 2a12481. This pass reviewed 1d7dd9c.

Commands run (read-only):
- `git rev-parse HEAD`: 1d7dd9c129338970f1ced186ab542f11ee0c7499
- `git status --short`: clean
- `git diff --stat 2a12481 HEAD`: only `reviews/p0-web-auth/review.md`, `reviews/p0-web-auth/tests.md`, `web/src/app/app.spec.ts` (+16) and `web/src/app/core/auth/auth.service.spec.ts` (+17)
- `git diff --stat 2a12481 HEAD -- . ':!reviews'`: only the two spec files. No production code, config, dependency or generated file changed after 2a12481.
- `git diff 2a12481 HEAD -- web`
- I read the test setup of both spec files and checked them against `auth.service.ts` (`setupRequired` cache) and `auth.guards.ts` (catch paths).
- The reviewer tried to run the two specs with `npx ng test --watch=false --include ...`, but the sandbox denied the command, so they were not run here. Criterion 4 evidence must come from the parent's own `cd web && npm run check` / `sh scripts/check.sh` run on 1d7dd9c.

Previous passes (unchanged):
- Second pass on 2a12481: 0 P0, 0 P1, 0 P2, 0 P3.
- First-review findings CR-1 to CR-4 were all resolved.

This pass: 0 P0, 0 P1, 0 P2, 0 P3.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of previous findings

- [x] **CR-1 (P2) | Guard failures other than 401 leave a blank page or a half-logged-out shell**: Resolved in 2a12481. Unchanged since then. The two new tests strengthen the coverage of this fix (see below).
- [x] **CR-2 (P3) | Backoff countdown re-announced every second**: Resolved in 2a12481. Unchanged.
- [x] **CR-3 (P3) | New i18n messages not extracted**: Resolved in 2a12481. Unchanged.
- [x] **CR-4 (P3) | Download test leaks global `URL` stubs**: Resolved in 2a12481. Unchanged.

## Review of the delta 2a12481..1d7dd9c (tests only)

- `web/src/app/app.spec.ts`: new test "shows the login form (not a blank page) while the server fails, and recovers afterwards".
    - It uses the real app routes with a mocked `AuthService`. `setupRequired` rejects with a 502, and the test asserts the redirect to `/login?returnUrl=%2Finbox`, that no shell is rendered, and that the login form is rendered.
    - The test then restores the mock, sets a valid session and navigates again. It asserts that `/inbox` opens in the shell.
    - This covers CR-1's outage path and the recovery path end-to-end through the router. It asserts behavior (URL, rendered elements), not implementation.
    - Per-test isolation holds: `state` and `auth` are recreated in `beforeEach`, so `mockImplementation` does not leak into other tests.
- `web/src/app/core/auth/auth.service.spec.ts`: new test "does not cache a failed setup status: shows a toast and asks again next time".
    - A 502 from `/api/v1/setup/status` rejects with `ApiError` (status 502) and shows exactly one error toast. A second call sends a new request and returns its result.
    - This matches `auth.service.ts:45-50`, which only assigns the cache after a successful response. It pins the "only a successful answer is cached" behavior that the guard recovery relies on.
    - `afterEach(() => backend.verify())` ensures that no unexpected request is left open.
- Neither test weakens, skips or removes existing tests. No debug output or test-data concerns.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. Since 2a12481, only tests and reports have changed: no production code changed. The two added tests are correct and meaningful regression tests for the CR-1 outage and recovery behavior.

The conclusion from the second pass still holds on revision 1d7dd9c129338970f1ced186ab542f11ee0c7499: the code meets the acceptance criteria. The parent worker must still record its own `npm run check` / `.taskcheck` run on this revision as evidence for criterion 4.
