---
title: Generated API client and HTTP infrastructure
status: in_progress
owner: dell26/agent-2
deps: [p0-web-deps,p0-api-conventions]
touches: [web/src/app/api,web/src/app/core/http,web/proxy.conf.json,web/ng-openapi-gen.json]
prio: 1
lease_until: 2026-10-20T09:32:11Z
---
## Goal
Typed Angular services generated from `api/openapi.yaml`, plus interceptors.

## Acceptance criteria
- [ ] `npm run api:gen` (ng-openapi-gen) generates `web/src/app/api/generated/` from `../api/openapi.yaml`; generated code committed, excluded from lint; a check in `npm run check` fails if generation is out of date
- [ ] Interceptors: `withXsrfConfiguration` for `XSRF-TOKEN`; 401 → navigate to `/login` (keeping the return URL); Problem Details → a typed `ApiError` surfaced through a toast/snackbar service
- [ ] `npm start` proxies `/api` and `/actuator` to `localhost:8080` (proxy.conf.json)
- [ ] Vitest component/service tests cover the interceptors (401 redirect, problem mapping); `cd web && npm run check` passes

## Notes
