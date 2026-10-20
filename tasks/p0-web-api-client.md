---
title: Generated API client and HTTP infrastructure
status: in_progress
owner: dell26/agent-2
deps: [p0-web-deps,p0-api-conventions]
touches: [web/src/app/api,web/src/app/core/http,web/proxy.conf.json,web/ng-openapi-gen.json]
prio: 1
lease_until: 2026-10-20T09:47:38Z
---
## Goal
Typed Angular services generated from `api/openapi.yaml`, plus interceptors.

## Acceptance criteria
- [x] `npm run api:gen` (ng-openapi-gen) generates `web/src/app/api/generated/` from `../api/openapi.yaml`; generated code committed, excluded from lint; a check in `npm run check` fails if generation is out of date
  - verified (2026-10-20 11:31+0200 by dell26/agent-2): cd web && npm run api:gen -> generates src/app/api/generated/ (models FieldError, ProblemDetail, Api, RequestBuilder) from ../api/openapi.yaml, committed; eslint.config.js ignores src/app/api/generated/** (npm run lint: All files pass); drift test: edited generated models/field-error.ts + added models/stale.ts -> npm run api:check exit 1 ('changed: models/field-error.ts', 'stale: models/stale.ts'); npm run api:gen -> api:check 'up to date', clean tree. api:check is the first step of npm run check
- [x] Interceptors: `withXsrfConfiguration` for `XSRF-TOKEN`; 401 → navigate to `/login` (keeping the return URL); Problem Details → a typed `ApiError` surfaced through a toast/snackbar service
  - verified (2026-10-20 11:31+0200 by dell26/agent-2): provideHttp() in app.config.ts: withXsrfConfiguration(XSRF-TOKEN/X-XSRF-TOKEN), loginRedirectInterceptor (401 -> /login?returnUrl=<current url>), problemInterceptor (Problem Details -> ApiError + NotificationService/MatSnackBar toast); cd web && npm run test:ci -> interceptors.spec.ts/api-error.spec.ts/notification.service.spec.ts/app.config.spec.ts pass (XSRF header on POST, 401 redirect with returnUrl, problem mapping, toast)
- [x] `npm start` proxies `/api` and `/actuator` to `localhost:8080` (proxy.conf.json)
  - verified (2026-10-20 11:31+0200 by dell26/agent-2): node stub HTTP server on 127.0.0.1:8080 + cd web && npm start -- --port 4321 (angular.json serve proxyConfig=proxy.conf.json) -> fetch GET localhost:4321/api/tasks?x=1 -> 200 'stub-backend GET /api/tasks?x=1'; POST /actuator/health -> 200 'stub-backend POST /actuator/health'; GET / -> 200 Angular index.html (not proxied)
- [x] Vitest component/service tests cover the interceptors (401 redirect, problem mapping); `cd web && npm run check` passes
  - verified (2026-10-20 11:31+0200 by dell26/agent-2): cd web && npm run check at final revision -> api:check up to date, lint all files pass, Vitest 6 files / 56 tests passed (incl. 401 redirect + problem mapping specs), ng build ok; coverage of task production files 100% statements/branches (reviews/p0-web-api-client/tests.md)

## Notes
- ASSUMPTION (2026-10-20 11:16+0200 by dell26/agent-2): 401 redirect goes to /login?returnUrl=<current URL incl. query>; no redirect when already on /login or /setup, or when the request sets HttpContext SKIP_LOGIN_REDIRECT (for login/session probes). p0-web-auth should read the 'returnUrl' param.
- ASSUMPTION (2026-10-20 11:16+0200 by dell26/agent-2): Every non-401 HTTP error is toasted via NotificationService (MatSnackBar) with problem.detail ?? title; callers that show errors inline set HttpContext SILENT_ERRORS. Non-problem responses map to a generic ApiError by status (0 = unreachable).
- ASSUMPTION (2026-10-20 11:16+0200 by dell26/agent-2): Generated client uses rootUrl '' (provideApiConfiguration('')) since spec paths begin with /api; generator config ignoreUnusedModels=false so schemas without paths (ProblemDetail) are generated. The api:check freshness script lives in web/scripts/api-gen-check.mjs (outside declared touches, like package.json/angular.json/eslint.config.js/app.config.ts wiring).
