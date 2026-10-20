# Code Review

Date: 2026-10-20

Task: p0-web-api-client - Generated API client and HTTP infrastructure

Scope: committed changes of the task, `git diff 2b07bd1..HEAD` (ng-openapi-gen config and generated client, `web/scripts/api-gen-check.mjs` in `npm run check`, eslint ignore, `web/proxy.conf.json` + `angular.json` serve option, `web/src/app/core/http/*`, `app.config.ts` wiring, tests). The test report `reviews/p0-web-api-client/tests.md` was read but is not under review.

Requirements:
- `tasks/p0-web-api-client.md` acceptance criteria and its three recorded assumptions (`python tasks.py assumptions`)
- SPEC.md §4.1 (session cookie, CSRF double-submit cookie with Angular's `XSRF-TOKEN` convention), §4.2 (Problem Details)
- docs/ARCHITECTURE.md (stack, Run, Testing, API sections, i18n convention), AGENTS.md project notes, `api/README.md`

Git range:
- Branch: main
- HEAD: ee787fb
- Base/start: 2b07bd1bf0e34eeaa49578cf34b735215c56e16e
- Commits reviewed: 3 (fc6b97d, dee03e6, ee787fb)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

Verification done by the reviewer: `cd web && npm run -s check` -> api:check reported the generated client up to date, lint passed, 6 test files and 56 tests passed, production build succeeded. The working tree was clean before the review.

Criterion check:
- #1: `api:gen` runs `ng-openapi-gen` with `web/ng-openapi-gen.json` (input `../api/openapi.yaml`, output `src/app/api/generated`). The generated code is committed and ignored by `eslint.config.js`. `api:check` generates into a temp dir and compares the file sets and contents exactly (missing, stale and changed files), and it runs first in `npm run check`. The logic is correct. No automated test shows it failing on drift, so the parent should record a manual demonstration as evidence.
- #2: `withXsrfConfiguration({cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN'})` is set. The interceptor order is correct: errors reach `loginRedirectInterceptor` first as the raw `HttpErrorResponse`, then `problemInterceptor` maps them to `ApiError`. A 401 navigates to `/login?returnUrl=...` using the in-flight navigation target if there is one. Repeated 401s and the public paths are handled. Problem Details become a typed `ApiError` with defensive parsing, and the toast goes through `NotificationService` (MatSnackBar). `rootUrl ''` keeps calls same-origin, so Angular's XSRF interceptor adds the header.
- #3: `proxy.conf.json` proxies `/api` and `/actuator` to `http://localhost:8080`, and `angular.json` `serve.options.proxyConfig` wires it in. Only the config was reviewed. This review did not run `npm start` against a running server.
- #4: interceptor tests cover the 401 redirect (including edge cases), problem mapping, the fallbacks, SILENT_ERRORS, XSRF and the app config wiring. `npm run check` passes.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | New i18n strings not extracted into `messages.xlf`**
    - File: `web/src/app/core/http/api-error.ts:72`, `web/src/app/core/http/notification.service.ts:10`
    - Category: Quality
    - Problem: The task adds four `$localize` messages with explicit ids (`http.error.unreachable`, `http.error.server`, `http.error.request`, `notification.dismiss`). The committed `web/src/locale/messages.xlf` still contains only `app.scaffold` and `app.title`. Last change: p0-web-deps.
    - Why it matters: ARCHITECTURE.md says all user-facing strings are externalized, and `npm run i18n:extract` writes `src/locale/messages.xlf`. Because the committed source catalog is now out of date, translators and later diffs of the file miss these strings.
    - Suggested fix: Run `cd web && npm run i18n:extract` and commit the updated `messages.xlf`. This is a one-file addition.

- [ ] **CR-2 | ARCHITECTURE.md still describes the dev proxy as future work and leaves out `api:check`**
    - File: `docs/ARCHITECTURE.md:93`, `docs/ARCHITECTURE.md:127`
    - Category: Quality
    - Problem: The Run table still says "A dev proxy from `/api` to `:8080` comes with the first API task". The proxy now exists and also covers `/actuator`. The Testing table describes `npm run check` as "web lint + tests + production build", but it now also runs the generated-client freshness check (`api:check`).
    - Why it matters: The review checklist asks that docs on how to run and test match the configuration. An agent whose `npm run check` fails on `api:check` gets no hint from ARCHITECTURE.md. The fix is in `api-gen-check.mjs` output and `api/README.md`.
    - Suggested fix: Make two small edits to the shared doc. Line 93 becomes "`npm start` proxies `/api` and `/actuator` to `:8080` (`proxy.conf.json`)". Line 127 becomes "generated-client check (`api:check`) + lint + tests + production build".

## Review conclusion

No material issues found. The two P3 items are small documentation and catalog follow-ups. None of the criteria has evidence recorded with `tasks.py verify` yet. The parent should record:
- for #1, a real `api:check` failure after drift, followed by a clean pass;
- for #3, a real `npm start` proxy check, or a skip with the reason.
