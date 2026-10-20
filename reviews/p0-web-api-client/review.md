# Code Review

Date: 2026-10-20

Task: p0-web-api-client - Generated API client and HTTP infrastructure

Scope: re-review of all committed changes of the task at the final revision, `git diff 30655ee..8b0ae52`. This includes the ng-openapi-gen config and generated client, `web/scripts/api-gen-check.mjs` in `npm run check`, the eslint ignore, `web/proxy.conf.json` and the `angular.json` serve option, `web/src/app/core/http/*`, the `app.config.ts` wiring, tests, `web/src/locale/messages.xlf` and `docs/ARCHITECTURE.md`. It also checks the fixes for CR-1 and CR-2 from the previous review of revision ee787fb, using `reviews/p0-web-api-client/fixes.md`. The test and fix reports were read but are not under review.

Requirements:
- `tasks/p0-web-api-client.md`: acceptance criteria and the three recorded assumptions
- SPEC.md §4.1 (session cookie and CSRF `XSRF-TOKEN` convention) and §4.2 (Problem Details)
- docs/ARCHITECTURE.md (Run, Testing, API and i18n conventions), AGENTS.md project notes, `api/README.md`

Git range:
- Branch: main
- HEAD: 8b0ae525be87a4bf9003b42b9d551f1006094e1b
- Base/start: 30655eee92f5b374649adf61822dd6b31a376dfa (`git merge-base HEAD origin/main`)
- Commits reviewed: 6 (e9e7cbd, 88b773f, 320b0ab, b55c454, 6ea05d0, 8b0ae52). The first three are the rebased versions of the commits reviewed before. The other three are the review report, the fixes and the re-test report.
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Changes since the previous review:
- `git diff ee787fb..HEAD -- web api server` shows only `web/src/locale/messages.xlf` (+32 lines). The new base (30655ee) differs from the old base (2b07bd1) only in `tasks/*.md` files, so the rebase did not change any reviewed production or test code, and the earlier review of that code still holds.
- CR-1 fixed: `messages.xlf` now has trans-units for `http.error.unreachable`, `http.error.server`, `http.error.request` (with the `status` placeholder) and `notification.dismiss`. Their source texts and line numbers (api-error.ts:72/74/75, notification.service.ts:10/14) match the current `$localize` calls. The existing `app.scaffold` and `app.title` entries are unchanged.
- CR-2 fixed: the ARCHITECTURE.md Run row for Web now says `web/proxy.conf.json` forwards `/api` and `/actuator` to `localhost:8080`, which matches `proxy.conf.json`. The Testing row for `npm run check` now lists `api:check` (and `npm run api:gen` to regenerate) before lint, tests and the production build, which matches the `check` script in `web/package.json`.

What the reviewer ran on 8b0ae52:
- `cd web && npm run -s api:check` -> "src/app/api/generated is up to date."
- `cd web && npm run -s check` -> the chained steps all completed (api:check, lint, Vitest with 6 files and 56 tests passed, production build succeeded).

Criterion check (same results as the previous review, code unchanged):
- #1: generation config, committed generated client, lint exclusion and the exact-compare freshness check as the first step of `npm run check` are all in place.
- #2: XSRF config is set. The 401 redirect to `/login?returnUrl=...` and the mapping of Problem Details to a typed `ApiError` toasted through `NotificationService` work, and the interceptor order is correct.
- #3: the proxy config is wired through `angular.json`. The review checked the configuration only and did not run it against a live server.
- #4: the interceptor tests cover the 401 redirect and problem mapping, and `npm run check` passes.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.

`python tasks.py verify p0-web-api-client` still shows all four criteria unverified. The parent must record evidence before `done`:
- #1: a real `api:check` failure after drift, followed by a clean pass;
- #3: a real `npm start` proxy check, or a skip with the reason;
- #2 and #4: the `npm run check` result.

`.taskcheck` has not been run on this revision (the fixer could not run it). The parent must run it.
