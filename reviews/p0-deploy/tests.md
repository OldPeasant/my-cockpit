# Test Report

Date: 2026-10-20

Task: p0-deploy - Docker image, compose stack, Caddy

Scope: task commits 171cd93, ed889c6, df189bb, b37b9e0 (`git log 39a2396..HEAD`), base
39a2396a330e2519e7ca417b825d3bea53505aea, measured HEAD b37b9e04a4f4fa4844004b68093e590bf194d8a5, branch main.
Files: Dockerfile, .dockerignore, deploy/*, server/.../core/spa/SpaWebConfiguration.kt and its tests.
Uncommitted inputs: only the test changes below (no production changes in the working tree).

Requirements:
- tasks/p0-deploy.md acceptance criteria and notes (two recorded assumptions: @WithMockUser until p0-auth-login; Caddy proxies only /actuator/health)
- SPEC.md §11 (via the task), docs/ARCHITECTURE.md "Coverage", AGENTS.md
- Parent instructions (tests only in server/src/test/kotlin/ch/sonensei/cockpit/core/spa)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/spa/SpaFallbackTests.kt` (extended; existing tests unchanged, the fixture now lives in a `web/` subfolder of the temp dir with a `media/` subfolder and a sibling secret file)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/spa/SpaFallbackResourceResolverTests.kt` (new)

## Tests added or updated

Count: 10 (6 in SpaFallbackTests, 4 in SpaFallbackResourceResolverTests)

- `fallback index html is served as no-cache html`: the fallback for `/inbox` is `text/html` with `Cache-Control: no-cache`
- `file in a subdirectory is served and a missing one is 404`: `/media/logo-XYZ.svg` is served; `/media/logo-GONE.svg` gives 404
- `head on a client route answers like get`: HEAD `/inbox` gives 200
- `existing api endpoint is not shadowed by the catch-all handler`: a test-only `@RestController` at `/api/v1/spa-probe` still answers JSON
- `path traversal cannot read files outside the web build`: `/../secret.txt`, `/media/../../secret.txt`, `/%2e%2e/secret.txt` and `/..%2fsecret.txt` never return 200 or the content of the sibling file
- `api root with trailing slash is 404, not index html`: `/api/`
- Resolver unit tests (no Spring context): an existing file resolves to itself; a client route resolves to `index.html`; `api/v1/tasks`, `actuator/` and a missing `.js` resolve to nothing; with no `index.html` (dev `bootRun` without a web build) a client route resolves to nothing, so it gives 404 and not an error

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC2: server serves the SPA, falls back to `index.html` for non-`/api` paths (test in `core/spa`) | SpaFallbackTests (13), SpaFallbackResourceResolverTests (4) | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.spa.*' ktlintCheck` | Pass (17/17, ktlint clean) |
| AC2: `/api` and `/actuator` paths are never shadowed by the fallback | `unknown api path is 404...`, `actuator is not shadowed...`, `existing api endpoint is not shadowed...`, `api root with trailing slash...`, resolver `server paths and missing files resolve to nothing` | same | Pass |
| AC2 (security): static serving does not leak files outside the web build | `path traversal cannot read files outside the web build` | same | Pass |
| AC4: health UP through Caddy, plus the Caddy actuator restriction | manual probe of the running stack (not an automated test) | `node .tmp/probe.js 18443 /actuator/health /actuator/env /actuator /inbox /api/v1/does-not-exist /main-GONE.js` (podman-compose stack, COCKPIT_DOMAIN=localhost, Caddy internal CA, full TLS verification) | Pass: `/actuator/health` 200 `{"status":"UP"}` with `via: 1.1 Caddy`; `/actuator/env` and `/actuator` 404 from Caddy; `/inbox`, `/api/...` 401 (Boot default security, the recorded assumption) |
| AC3: Caddy security headers (HSTS, CSP with frame-ancestors, X-Content-Type-Options) | same manual probe | same | Pass: `strict-transport-security: max-age=31536000; includeSubDomains`, `content-security-policy: ...frame-ancestors 'none'`, `x-content-type-options: nosniff` on both proxied and Caddy-generated (404) responses; `Server` header removed |
| AC3: `deploy/gen-env.sh` (random master key + db password, mode 600, refuses overwrite without `--force`) | none | `sh .tmp/genenv-test/gen-env.sh localhost` on a temp copy | Not run: the permission system denied running the script (also from a temp copy). Read only: the code uses `umask 077`, 32 random bytes base64 for the key, 24 bytes hex for the password, refuses an existing `.env` without `--force` |
| AC1: `docker build .` with Node 24 / JDK 21, non-root | none | none | Not run by test-writer (outside the test scope; the parent records the build) |
| AC5: `host-backup.sh`, `deploy/README.md` | none | none | Not testable here (example script for a host with restic; documentation) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.spa.*' ktlintCheck`

Result:

Pass (SpaFallbackTests 13/13, SpaFallbackResourceResolverTests 4/4, ktlintCheck BUILD SUCCESSFUL)

## Full test execution

Command:

`cd server && ./gradlew test jacocoTestReport --rerun-tasks`

Result:

Pass: 118 tests in 24 suites, 0 failures, 0 errors, 0 skipped. Web and Android suites were not run, because the task changes nothing there.

## .taskcheck

Result:

Not run: the permission system denied `sh scripts/check.sh`. The server part (ktlintCheck plus the full test suite) ran above. The parent must run `.taskcheck`.

## Bugs found

None.

Observation (not a defect): `SpaFallbackResourceResolver` does not reject `..` itself. Called directly with
`../secret.txt` it returns the file outside the location, because `PathResourceResolver.checkResource`
only compares URL prefixes. Over HTTP the request never reaches it: Spring Security's firewall and
`ResourceHttpRequestHandler`'s path validation stop it first, and the MockMvc traversal test confirms this.
This only matters if someone later uses the resolver outside a `ResourceHttpRequestHandler`. I deleted the
first unit test I wrote for this, because it called the resolver directly and so expected a guarantee the
resolver never made, not one this task requires.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured as a separate figure. The file is new in this task (all its lines are added lines), so whole-file coverage equals changed-line coverage here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p0-deploy; base 39a2396a330e2519e7ca417b825d3bea53505aea; HEAD b37b9e04a4f4fa4844004b68093e590bf194d8a5; commits and file scope from the parent, cross-checked with `git diff --name-only 39a2396..HEAD -- server/src/main web/src android/app/src/main` (only output: SpaWebConfiguration.kt)
- Source/test snapshot identifier (including uncommitted inputs): sha256 SpaWebConfiguration.kt 571ee77788c1ec9a664f711d176c9dbbb31642b81720421750d5167fcc6a4e29 (committed, unchanged); SpaFallbackTests.kt d3297cb6b6c38053d0386d2fea0f12e1334724d260cb8a1ff3808034fc6ac84b; SpaFallbackResourceResolverTests.kt 3289dbd7652bed3d022ce8d5fc0c73ac8903bd6204b11fc063ff206f33609d21 (both uncommitted); the hashes were the same before and after the run
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/core/spa/SpaWebConfiguration.kt`. Excluded (not JVM code, no configured coverage provider): Dockerfile, .dockerignore, deploy/* (compose, Caddyfile, shell scripts, env example, README), docs/ARCHITECTURE.md. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (default tool version of Gradle 9.8.1); `./gradlew test jacocoTestReport --rerun-tasks` in `server/`; full server test suite
- Run start/completion time and exit status: 2026-10-20T11:27:43+02:00 to 11:28:02+02:00, BUILD SUCCESSFUL (exit 0)
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` (mtime 2026-10-20 11:28:02, sha256 978eca80a640a549e414833d12faafe4d12921b31f90d5f27b929f0d257ba4b9)
- Evidence outputs are fresh and inputs stayed unchanged: yes (`--rerun-tasks` forced new test and report tasks; the report mtime matches the run's completion time; the input hashes were the same before and after)
- Final run after latest test additions and production fixes: yes (no production fixes were made; the run came after the last test edit)

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/spa/SpaWebConfiguration.kt` | 113/113 instructions (100%) | 8/8 (100%) | 7/7 methods (100%) | 19/19 (100%) |

Aggregate (one measured file): instructions 113/113, branches 8/8, methods 7/7, lines 19/19. Unmeasured: the
non-JVM deploy files listed above (no coverage provider applies to them).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `SpaWebConfiguration.kt`: none | n/a | n/a |
| `deploy/gen-env.sh`: not executed | AC3 (key and password generation, mode 600, overwrite refusal) | The permission system blocked running it. The parent or a human should run it in a temp copy and record the result |

## Unable to test

- `deploy/gen-env.sh`: running it was denied by the permission system, also from a copy under `.tmp/`. Follow-up: the parent should check that a temp copy produces `.env` with the domain, a key that base64-decodes to 32 bytes, a 48-character hex password, mode 600, exit 1 on a second run without `--force`, and exit 2 without a domain. Note from reading the code: the domain is put straight into a `sed` replacement, so a domain containing `|` or `&` would corrupt the output. Real host names never contain these characters, so this is Low and informational, not a defect.
- `.taskcheck` (`sh scripts/check.sh`): denied by the permission system. The parent must run it.
- `docker build .` / compose up: owned by the parent (AC1 and AC4 evidence). I only probed the stack that was already running and did not take it down or change it.
- `deploy/host-backup.sh`: needs restic and a host install; it is an example script. Not testable here.
- With real security (p0-auth-login), anonymous access to the SPA is untested by design: the tests use `@WithMockUser` (recorded assumption).

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. `gen-env.sh` and `.taskcheck` could not be run under the current permissions and need the parent to run them.
