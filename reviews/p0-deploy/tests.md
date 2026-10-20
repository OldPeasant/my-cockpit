# Test Report

Date: 2026-10-20

Task: p0-deploy - Docker image, compose stack, Caddy

Scope: re-run on the final revision after the fixer (CR-1, CR-2, CR-3 in `reviews/p0-deploy/fixes.md`).
The parent asked for f8dcb3e (base 39a2396a330e2519e7ca417b825d3bea53505aea). During this run the clone was
rebased (reflog: `pull --rebase` / `rebase` at 2026-10-20 11:33:39 +0200) onto a newer main. Measured HEAD is
203e5d5c1dcb7a999511e0be23d7a960613bcb16 on branch main, and its new task base is 06bf261d1cb88c432839a6f4d874bec172401e2a.
Task-owned commits (from `git range-diff 39a2396..f8dcb3e 06bf261..203e5d5`): 14d33c7, b304018, d649545, 5a5ac0d, 69e9d36, 203e5d5.
They are the same patches as 171cd93, ed889c6, df189bb, b37b9e0, 351ad92 and f8dcb3e. Only d649545 (docs/ARCHITECTURE.md)
has a different conflict resolution, in the dev proxy line that came from main. `git diff f8dcb3e 203e5d5 -- server deploy Dockerfile .dockerignore`
is empty, so every file this report tests or measures is byte-identical at f8dcb3e and 203e5d5.
Uncommitted inputs: none in the tested files (`git status`: only `reviews/p0-deploy/review.md` modified, by the reviewer).

Requirements:
- tasks/p0-deploy.md acceptance criteria and notes (assumptions: @WithMockUser until p0-auth-login; Caddy proxies only /actuator/health, confirmed by a human)
- reviews/p0-deploy/review.md findings CR-1..CR-3 and reviews/p0-deploy/fixes.md (the changed behavior to check)
- SPEC.md §11 (through the task), docs/ARCHITECTURE.md "Coverage", AGENTS.md
- Parent instructions: add tests only in server/src/test/kotlin/ch/sonensei/cockpit/core/spa and only if needed; don't take the stack down or edit deploy/.env

## Test files changed

None in this run. The fixer added `resource chain keeps no unbounded resolver cache` to
`server/src/test/kotlin/ch/sonensei/cockpit/core/spa/SpaFallbackTests.kt` (committed in f8dcb3e / 203e5d5), and I reviewed it.

## Tests added or updated

Count: 0 (this run). The task has 18 tests in total: SpaFallbackTests 14, SpaFallbackResourceResolverTests 4.

Why no new test was needed:
- CR-1 (`.resourceChain(false)`): the fixer's test reads the real `/**` `ResourceHttpRequestHandler` from the
  `resourceHandlerMapping` bean. It asserts that no `CachingResourceResolver` is in the chain and that
  `SpaFallbackResourceResolver` is. This is the behavior the finding asked for. It would fail with
  `.resourceChain(true)`, because Spring then puts a `CachingResourceResolver` first. The existing behavior tests
  (client-route fallback, static files, no-cache, 404 for `/api`, `/actuator` and missing assets, traversal) run
  without the cache and all pass, so dropping the cache did not change the served results. A count-based test
  (N distinct URLs leave no N cache entries) would need the cache's internals and would add nothing.
- CR-2 (Caddyfile `?Content-Security-Policy`) and CR-3 (`gen-env.sh` domain check) are not JVM code, and
  `core/spa` has no test harness for them. CR-2 is covered in part by the live probe below. CR-3 could not be run (see "Unable to test").

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC2: server serves the SPA, falls back to `index.html` for non-`/api` paths (test in `core/spa`) | SpaFallbackTests (14), SpaFallbackResourceResolverTests (4) | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.spa.*' ktlintCheck --rerun-tasks` | Pass (18/18, 0 failures/errors/skipped; ktlint clean) |
| AC2: `/api` and `/actuator` are never shadowed by the fallback | `unknown api path is 404...`, `actuator is not shadowed...`, `existing api endpoint is not shadowed...`, `api root with trailing slash...`, resolver `server paths and missing files resolve to nothing` | same | Pass |
| AC2 (security): static serving does not leak files outside the web build | `path traversal cannot read files outside the web build` | same | Pass |
| CR-1: no unbounded resolver cache in the SPA chain | `resource chain keeps no unbounded resolver cache` | same | Pass |
| AC4: `/actuator/health` UP through Caddy, other actuator paths blocked | live probe (manual, not an automated test) | `node .tmp/probe.js 18443 /actuator/health /actuator/env /actuator /inbox /api/v1/does-not-exist /main-GONE.js` against the running stack (parent: rebuilt from f8dcb3e with fresh volumes; COCKPIT_DOMAIN=localhost, Caddy internal CA, full TLS verification against the refreshed `.tmp/caddy-root.crt`), 2026-10-20 09:34:50 GMT | Pass: `/actuator/health` 200 `{"groups":["liveness","readiness"],"status":"UP"}` with `via: 1.1 Caddy`; `/actuator/env` and `/actuator` 404 from Caddy (no `via`); `/inbox`, `/api/v1/does-not-exist`, `/main-GONE.js` 401 from the server (Boot default security, the recorded assumption) |
| AC3: Caddy security headers (HSTS, CSP with frame-ancestors, X-Content-Type-Options) | same probe | same | Pass: `strict-transport-security: max-age=31536000; includeSubDomains`, `content-security-policy: ...frame-ancestors 'none'`, `x-content-type-options: nosniff`, plus Referrer-Policy, Permissions-Policy and COOP, on proxied responses and on Caddy's own 404s; no `Server` header |
| CR-2: Caddy sets its CSP only as a default (`?`) and keeps a CSP from the server | same probe (default case only) | same | Partly: the default case passes (the server sends no CSP today, and Caddy's CSP is present on every response). The case where the server sends its own CSP was not run: no server endpoint sets a CSP yet (that comes with the HTML-email task). The fixer's `caddy adapt` output shows `"require":{"headers":{"Content-Security-Policy":null}}` |
| CR-3 / AC3: `deploy/gen-env.sh` (random key + db password, domain check, mode 600, no overwrite without `--force`) | none | none | Not run: running shell scripts is blocked by the permission system in this session (as in the previous run). Read only: the `case` pattern `*[!A-Za-z0-9.-]* \| [.-]*` comes before the `.env` existence check and exits 2 |
| AC1: `docker build .` with Node 24 / JDK 21, non-root | none | none | Not run by test-writer (the parent owns the build evidence; see the note on the rebase under "Unable to test") |
| AC5: `host-backup.sh`, `deploy/README.md` | none | none | Not testable here (an example script for a host with restic, and documentation) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.spa.*' ktlintCheck --rerun-tasks`

(A first run without `--rerun-tasks` was all UP-TO-DATE, so it executed nothing and is not counted.)

Result:

Pass: BUILD SUCCESSFUL, 13 tasks executed. SpaFallbackTests 14/14 (suite timestamp 2026-10-20T09:33:47Z), SpaFallbackResourceResolverTests 4/4, ktlintCheck clean.

## Full test execution

Command:

`cd server && ./gradlew test jacocoTestReport --rerun-tasks`

Result:

Pass: 119 tests in 24 suites, 0 failures, 0 errors, 0 skipped (118 before plus the fixer's new test). Web and Android
suites were not run: the task changes nothing there. The rebase brought web/android changes from main, but those belong to other tasks.

## .taskcheck

Result:

Not run: running `sh scripts/check.sh` is blocked by the permission system in this session. The server part (ktlintCheck and the full
server suite) ran above. The parent must run `.taskcheck` on 203e5d5 (or later).

## Bugs found

None.

Observation (unchanged from the previous run, not a defect): `SpaFallbackResourceResolver` does not reject `..`
itself. Over HTTP, Spring Security's firewall and `ResourceHttpRequestHandler`'s path checks stop such requests first, and the
MockMvc traversal test confirms it. `.resourceChain(false)` does not change this.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. SpaWebConfiguration.kt is new in this task (all its lines are added lines), so
whole-file coverage equals changed-line coverage. The fixer's changed line 31 (`.resourceChain(false)`) shows `ci="2" mi="0"` in the report.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p0-deploy. Original base 39a2396a330e2519e7ca417b825d3bea53505aea; after the rebase the base is 06bf261d1cb88c432839a6f4d874bec172401e2a. Measured HEAD 203e5d5c1dcb7a999511e0be23d7a960613bcb16, with the same task files as the requested f8dcb3e5f224ec05a6edfa9182c1b26b192c30c1 (empty diff for server/, deploy/, Dockerfile, .dockerignore). The task-owned commits come from `git range-diff` (see Scope). Production file scope: `git diff --name-only 06bf261..203e5d5` lists only `server/src/main/kotlin/ch/sonensei/cockpit/core/spa/SpaWebConfiguration.kt` as JVM production code. A diff from the original base 39a2396 now also lists web/android files from other tasks merged into main. They are excluded as not task-owned.
- Source/test snapshot identifier (including uncommitted inputs): sha256 SpaWebConfiguration.kt 5ca3d3862050a7b92aee0da269df178b2fd54a87fa47478851f932c230529a6d; SpaFallbackTests.kt e4326be96c5521be7edc5df975917b148f527a2ea5422a128bcafd3d48ec5836; SpaFallbackResourceResolverTests.kt 3289dbd7652bed3d022ce8d5fc0c73ac8903bd6204b11fc063ff206f33609d21; deploy/Caddyfile ea19c884ed1c748a124e0be8b83de59adec0ba414e6dfaedbcbc67e4cfe16bb7; deploy/gen-env.sh 22678de39e23f95c91fb13adbd732e4d4f188ab0c6513c832a78c10a452cab2a. All committed, and the hashes were identical before and after the runs.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/core/spa/SpaWebConfiguration.kt`. Excluded (not JVM code, no configured coverage provider): Dockerfile, .dockerignore, deploy/* (compose, Caddyfile, shell scripts, env example, README), docs/ARCHITECTURE.md. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (default tool version of Gradle 9.8.1); `./gradlew test jacocoTestReport --rerun-tasks` in `server/`; full server test suite.
- Run start/completion time and exit status: about 2026-10-20T11:34:09+02:00 to 11:34:31+02:00 (BUILD SUCCESSFUL in 22s, 7 tasks executed), exit 0. This was after the 11:33:39 rebase.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, mtime 2026-10-20 11:34:31.509 +0200, sha256 89ac140139e7c529ab12467d7e23d7011505880e4a85b3b075a1b253d0edc390.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `--rerun-tasks` forced new test and report tasks, the report mtime matches the run's completion time, and the input hashes were identical before and after.
- Final run after latest test additions and production fixes: yes (it ran after the fixer's commit; no test changes since).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/spa/SpaWebConfiguration.kt` | 113/113 instructions (100%) | 8/8 (100%) | 7/7 methods (100%) | 19/19 (100%) |

Aggregate (one measured file): instructions 113/113, branches 8/8, methods 7/7, lines 19/19. Unmeasured: the non-JVM
deploy files listed above (no coverage provider applies to them).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `SpaWebConfiguration.kt`: none | n/a | n/a |
| `deploy/gen-env.sh`: not executed (including the new CR-3 domain check) | AC3 (key and password generation, mode 600, overwrite refusal); CR-3 (an invalid domain is rejected before anything is written) | Running scripts is blocked by the permission system here. The parent or a human should run it on a temp copy (see below) |
| `deploy/Caddyfile`: the CR-2 branch where the server sends its own CSP | the server's CSP must be kept and not overwritten | Not reachable yet: no server endpoint sets a CSP. Recheck when the sandboxed HTML-email endpoint (SPEC.md §4.1) exists |

## Unable to test

- `deploy/gen-env.sh`: blocked by the permission system. Follow-up for the parent, on a temp copy of `deploy/`: `sh gen-env.sh localhost` should write `.env` with mode 600, a key that base64-decodes to 32 bytes and a 48-character hex password. A second run without `--force` should exit 1. No argument, `https://x.example`, `a|b`, `a&b`, `.example` and `-x` should each exit 2 and write no `.env`.
- `.taskcheck`: blocked by the permission system. The parent must run it.
- Server-sent CSP through Caddy (CR-2, the case where the server sends one): nothing on the server sends a CSP yet.
- Stack and image freshness after the rebase: the running stack was built from f8dcb3e. Server, deploy and Dockerfile are identical at 203e5d5, but the rebase brought `web/` changes from main (p0-web-api-client), so the image's web bundle does not match 203e5d5. The probe results above therefore describe f8dcb3e. I could not check the containers (`podman ps` is blocked here). If the AC1/AC4 evidence must name the final revision, the parent should rebuild on 203e5d5. I did not take the stack down or touch `deploy/.env`.
- `deploy/host-backup.sh`: needs restic and a host install; it is an example script.
- Anonymous access to the SPA with real security (p0-auth-login) is untested by design: the tests use `@WithMockUser` (recorded assumption).

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The fixer's CR-1 test covers the final
SPA configuration, and the 18 SPA tests, the full server suite (119 tests), ktlint and JaCoCo (100% on SpaWebConfiguration.kt) pass on 203e5d5, which has the same task files as f8dcb3e.
The live probe confirms health UP through Caddy, the actuator restriction and the security headers.
The parent still has to run three things: `gen-env.sh` (including the CR-3 check), `.taskcheck`, and, if the evidence must name the rebased revision, a rebuild of the stack on 203e5d5.
