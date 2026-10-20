# Test Report

Date: 2026-10-20

Task: p0-web-api-client - Generated API client and HTTP infrastructure

Scope: `git log 30655eee92f5b374649adf61822dd6b31a376dfa..6ea05d05fc4edd7cbc7480ce110a501411f183f3` on branch `main` (base = `git merge-base HEAD origin/main`, supplied by the parent). Task commits: e9e7cbd, 88b773f (implementation, rebased versions of fc6b97d and dee03e6), 320b0ab (tests, test report), b55c454 (review report), 6ea05d0 (fixer: `web/src/locale/messages.xlf` i18n extract, two lines of `docs/ARCHITECTURE.md`, fix report). The working tree was clean when measured.

Re-run on the final revision (2026-10-20): since the previous version of this report (measured at dee03e6 plus the then-uncommitted tests), no TypeScript, test or build file changed. Only the regenerated i18n catalog `web/src/locale/messages.xlf` (four new trans-units for the `$localize` strings in `api-error.ts` and `notification.service.ts`) and docs changed. The sha256 of every task production and spec file listed under Coverage matches the previous snapshot exactly. The XLF is a non-executable translation catalog: Vitest does not exercise it, and the production build (`ng build` in `npm run check`) compiles successfully with it. No further tests are needed.

Requirements:
- `tasks/p0-web-api-client.md` acceptance criteria and recorded assumptions (returnUrl param, public paths, SKIP_LOGIN_REDIRECT, SILENT_ERRORS, generic ApiError by status, rootUrl '')
- `docs/ARCHITECTURE.md` "Coverage"
- `AGENTS.md` project notes (generated files, lint contract, fictitious test data)

## Test files changed

(No test file changed in this re-run. Files added or changed by this task in 320b0ab:)

- `web/src/app/core/http/interceptors.spec.ts` (extended)
- `web/src/app/core/http/api-error.spec.ts` (new)
- `web/src/app/core/http/notification.service.spec.ts` (new)
- `web/src/app/app.config.spec.ts` (new)

## Tests added or updated

Count: 0 in this re-run. Earlier in the task: 36 new tests (the interceptor spec had 13 tests before, now 23; api-error.spec.ts 22, notification.service.spec.ts 3, app.config.spec.ts 1). No existing test was changed or weakened. The only change to existing code was the imports at the top of interceptors.spec.ts and two extra test routes.

New tests:
- interceptors.spec.ts: `redirects on a 401 without a problem body`, `still redirects for SILENT_ERRORS requests`, `does not redirect on 403 but shows the problem`, `keeps the original returnUrl when several requests fail with 401 at once`, `uses the URL being navigated to when a 401 arrives during a navigation` (a resolver request fails during navigation), `uses the redirect target as returnUrl when the navigation was redirected`, `non-HTTP errors` (3 tests: problem interceptor and login interceptor pass the error through, and the login interceptor rethrows the raw HttpErrorResponse), `generated API client ... empty root URL so calls go to /api on the same origin`.
- api-error.spec.ts: Error/name/message/userMessage; HTTP status takes precedence over body status; problem status is used when the response status is 0; non-string optional fields are ignored; malformed field errors are filtered; non-array `errors`; 10 non-problem bodies (null, empty, malformed JSON, HTML, a JSON string, an array, missing title, missing status, string status, numeric title) fall back to a generic error; generic titles at status boundaries 0/400/499/500/504.
- notification.service.spec.ts: `info` and `error` call `MatSnackBar.open` with the Dismiss action, the duration and (for errors) assertive politeness; the real MatSnackBar renders the message and the action.
- app.config.spec.ts: `appConfig.providers` wire provideHttp: XSRF header on POST, Problem Details become an ApiError plus a toast, ApiConfiguration.rootUrl is ''.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `npm run api:gen` generates `src/app/api/generated/` from `../api/openapi.yaml`; generated code committed | `api:check` step (regenerates into a temp dir and compares with the committed files) | `cd web && npm run check` | Pass ("src/app/api/generated is up to date.") |
| AC1: generated code excluded from lint | `ng lint --max-warnings=0` step | `cd web && npm run check` | Pass ("All files pass linting." while the generated files are not lint-clean, e.g. `var`) |
| AC1: the check fails when generation is out of date | none (negative path) | Not run: I tried to run the script on a drifted temp copy, but the sandbox refused the commands to create a temp dir. Code inspection: missing/stale/changed files set `process.exitCode = 1`. | Not run: sandbox |
| AC2: `withXsrfConfiguration` for `XSRF-TOKEN` | `sends the XSRF-TOKEN cookie as X-XSRF-TOKEN header on mutating requests`, `appConfig provides the HTTP infrastructure for the generated client` | `cd web && npm run test:ci` | Pass |
| AC2: 401 navigates to `/login` keeping the return URL | `navigates to /login with the current URL as returnUrl`, `redirects on a 401 without a problem body`, `still redirects for SILENT_ERRORS requests`, `keeps the original returnUrl when several requests fail with 401 at once`, `uses the URL being navigated to when a 401 arrives during a navigation`, `uses the redirect target as returnUrl when the navigation was redirected`, `does not redirect when already on the login page`, `does not redirect from the setup page`, `does not redirect for SKIP_LOGIN_REDIRECT requests`, `does not redirect on 403 but shows the problem` | `cd web && npm run test:ci` | Pass |
| AC2: Problem Details become a typed `ApiError` shown in a toast/snackbar | Problem Details block (8 tests), api-error.spec.ts (22 tests), notification.service.spec.ts (3 tests), non-HTTP errors block (3 tests), app.config.spec.ts | `cd web && npm run test:ci` | Pass |
| AC3: `npm start` proxies `/api` and `/actuator` to `localhost:8080` | none | Not run: running it needs a long-lived dev server plus a backend on :8080. Config inspected: `web/proxy.conf.json` maps both prefixes to `http://localhost:8080`, and `angular.json` serve `options.proxyConfig` references it. | Not run: needs dev server + backend |
| AC4: Vitest tests cover the interceptors (401 redirect, problem mapping) | all of the above | `cd web && npm run test:ci` | Pass (6 files, 56 tests) |
| AC4: `cd web && npm run check` passes | api:check + lint + test:ci + ng build | `cd web && npm run check` | Pass |
| Assumption: generated client uses rootUrl '' | `is configured with an empty root URL so calls go to /api on the same origin`, app.config.spec.ts | `cd web && npm run test:ci` | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/app.config.spec.ts' --include='src/app/core/http/**/*.spec.ts'` (previous run, Pass: 4 files, 49 tests; not repeated because the spec and source files are byte-identical, and the full suite below covers them)

Result:

Pass

## Full test execution

Command:

`npm --prefix web run check` (api:check, lint, test:ci, ng build) at HEAD 6ea05d0, 2026-10-20T09:26:44Z and again at 09:27:12Z, with the exit status captured (`&& echo CHECK-EXIT-0`)

Result:

Pass, exit 0: api:check printed "src/app/api/generated is up to date.", lint printed "All files pass linting.", Vitest ran 6 files and 56 tests (all passed), and the production build succeeded with the regenerated messages.xlf.

## .taskcheck

Result:

Not run. The task touches only `web/`, and `npm run check` is the web part of `scripts/check.sh`. The parent runs `.taskcheck` before `done`.

## Bugs found

None. No production defect was found.

Observation, not a defect against the acceptance criteria: `asProblem` in `web/src/app/core/http/api-error.ts` only parses object and string bodies. A Problem Details error on a request with `responseType: 'blob'` or `'arraybuffer'` (for example a future generated download operation) becomes the generic "The request failed (HTTP n)." message, and its detail is lost. No such operation exists yet (`api/openapi.yaml` has `paths: {}`). Severity: Low. It may need attention when binary endpoints are added.

## Coverage

Status: verified for the hand-written task files. Generated files are reported for information. Barrels and type-only files are absent from the report because they contain no executable code.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. Note: `api-error.ts`, `interceptors.ts`, `notification.service.ts` and `provide-http.ts` are entirely new in this task, so for them whole-file equals changed-line coverage. `app.config.ts` has a single changed statement.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p0-web-api-client; base 30655eee92f5b374649adf61822dd6b31a376dfa (`git merge-base HEAD origin/main`); measured HEAD 6ea05d05fc4edd7cbc7480ce110a501411f183f3; task commits e9e7cbd, 88b773f, 320b0ab, b55c454 and 6ea05d0, confirmed with `git log 30655ee..HEAD` and `git diff --name-status 30655ee HEAD -- web`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 6ea05d0 with a clean working tree (no uncommitted inputs). sha256, identical to the previous run at dee03e6 plus the then-uncommitted tests: interceptors.spec.ts d705592193f0816d32d3e922a8d741bd3ee9a1bf174528884035e85a1f528107, api-error.spec.ts 236ffab6eff2b1543f8b3b78589c891dad8b2bd132c12955bd9e962d2b89084c, notification.service.spec.ts b9405c502b602277a88ef258c079e21e9c82275861c0c61ba1087ab9d1f14dd7, app.config.spec.ts ba9bac858e1a0f8fbead43ebcae488cc3b0afe23cb3b6c9484775eed1ab56a14. The production files are unchanged from HEAD: interceptors.ts 37e495c0..., api-error.ts be2c0253..., notification.service.ts 33fa228e..., provide-http.ts 2cf1354c..., app.config.ts cfac7344.... The new non-executable input is `web/src/locale/messages.xlf` sha256 6a869b4c52c9688bae70cbe9548ebce9c13b7b3125daa820172cb8273bdbbcb8 (blob 44154a4, matches HEAD).
- Exact included files: `web/src/app/core/http/{api-error,interceptors,notification.service,provide-http}.ts` and `web/src/app/app.config.ts`. Generated and reported separately: `web/src/app/api/generated/{api-configuration,api,request-builder}.ts`. Excluded: `web/src/app/core/http/index.ts` and the generated `index.ts`, `functions.ts`, `models.ts`, `models/*.ts` and `strict-http-response.ts`, which contain only re-exports or types and have no executable statements, so they are absent from the v8 report. `web/scripts/api-gen-check.mjs` is a Node script outside Vitest; it is exercised by `npm run check` (clean path only). `web/proxy.conf.json`, `web/ng-openapi-gen.json`, `web/angular.json`, `web/eslint.config.js` and `web/package.json` are configuration files. `web/src/locale/messages.xlf` is a generated translation catalog with no executable code.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8 5.0.5; `npm run coverage` in `web/`; the full web unit suite (6 files, 56 tests). The script runs `ng test --watch=false --coverage` with the text-summary, json-summary, json and lcov reporters.
- Run start/completion time and exit status: 2026-10-20T09:27:31Z to 09:27:35Z. Exit 0, all 56 tests passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 f089f5200b57f81250e40db686ba8568d59d839d307f8ee4ccf4c36f394ab163, `web/coverage/web/coverage-final.json` sha256 e876a5b2afc6767d874a60da92acf565df99449f80e4fbe2fd4891360694339f. Both are Git-ignored. Both files were rewritten during this run (mtime 2026-10-20 09:27:35Z) and are byte-identical to the previous run, as expected for identical inputs. Repository total in this run: statements 130/268, branches 83/215, functions 31/53, lines 105/237. It includes files outside the task (app.ts, app.html, app.routes.ts), so it is not a task metric.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report files' mtime falls inside the run window. `git status --short` was empty after the run, and the XLF blob still matched HEAD.
- Final run after latest test additions and production fixes: yes. It ran on 6ea05d0, after the fixer's changes (XLF and docs only; no production code fixes).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/core/http/api-error.ts` | 31/31 (100%) | 35/35 (100%) | 6/6 (100%) | 28/28 (100%) |
| `web/src/app/core/http/interceptors.ts` | 32/32 (100%) | 20/20 (100%) | 12/12 (100%) | 25/25 (100%) |
| `web/src/app/core/http/notification.service.ts` | 7/7 (100%) | 5/5 (100%) | 2/2 (100%) | 4/4 (100%) |
| `web/src/app/core/http/provide-http.ts` | 1/1 (100%) | N/A (0/0) | 1/1 (100%) | 1/1 (100%) |
| `web/src/app/app.config.ts` | 1/1 (100%) | N/A (0/0) | N/A (0/0) | 1/1 (100%) |
| Aggregate, hand-written (5 files above) | 72/72 (100%) | 60/60 (100%) | 21/21 (100%) | 59/59 (100%) |
| `web/src/app/api/generated/api-configuration.ts` (generated) | 7/8 (87.5%) | 3/5 (60%) | 1/1 (100%) | 5/5 (100%) |
| `web/src/app/api/generated/api.ts` (generated) | 13/14 (92.85%) | 7/7 (100%) | 6/7 (85.71%) | 10/11 (90.9%) |
| `web/src/app/api/generated/request-builder.ts` (generated) | 24/160 (15%) | 5/134 (3.73%) | 2/23 (8.69%) | 24/155 (15.48%) |
| Aggregate, all 8 measured files | 116/254 (45.7%) | 75/206 (36.4%) | 30/52 (57.7%) | 98/230 (42.6%) |

Not in the aggregates: the barrel and type-only files and the Node script `web/scripts/api-gen-check.mjs` (see Evidence).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/api/generated/request-builder.ts`: most of lines 12-357 (parameter serialization, body encodings) | Third-party generated code (ng-openapi-gen). The spec has no operations yet, so nothing calls these paths. | Left uncovered. This task does not own the generator's behavior; feature tasks that add operations should test their calls. |
| `web/src/app/api/generated/api.ts:39` (rootUrl setter) | Generated; not used | Left uncovered |
| `web/src/app/api/generated/api-configuration.ts:24` (decorator/default branches) | Generated; the default rootUrl '/' is overridden by provideApiConfiguration('') | Left uncovered. The override is tested (`is configured with an empty root URL ...`). |

## Unable to test

- The failure path of `api:check` when generated code is out of date: the sandbox refused the commands to build a drifted temp copy. Follow-up: the parent can verify it manually, e.g. by appending a line to a generated file, running `npm run api:check` (expect exit 1 with "changed: ..."), then restoring the file.
- `npm start` proxying `/api` and `/actuator` to localhost:8080: this needs a running dev server and backend. The configuration was inspected only. Follow-up: verify it manually or skip it with a reason via `tasks.py verify --skip`.

## Summary

Re-run on the final revision 6ea05d0: no further tests are needed, because no TypeScript, test or build input changed since the previous run. `npm run check` and `npm run coverage` pass (exit 0, 56 tests). Tests and task-scoped coverage are sufficient. Task-scoped coverage of the hand-written files is 100% for every metric, and no production defects were found.
