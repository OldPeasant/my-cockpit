# Code Review

Date: 2026-10-20

Task: p0-deploy - Docker image, compose stack, Caddy

Scope: re-review of the final revision. Covers all committed changes of task p0-deploy, `git diff 39a2396..f8dcb3e` (Dockerfile, .dockerignore, deploy/*, server core/spa main + test, docs/ARCHITECTURE.md edits). It also checks the fixes for CR-1..CR-3 from the first review (of b37b9e0) and the tests the test-writer added in 351ad92.

Reviewed revision: f8dcb3e5f224ec05a6edfa9182c1b26b192c30c1

Requirements:
- tasks/p0-deploy.md acceptance criteria 1-5 and recorded assumptions (SPA behind Boot default security until p0-auth-login; Caddy exposes only /actuator/health*, confirmed by a human)
- SPEC.md §4.1 (security headers via Caddy + Spring, sandboxed HTML email with its own CSP, master key), §11 (deployment and operations)
- docs/ARCHITECTURE.md (runtime versions, stack table, run instructions), AGENTS.md project notes
- reviews/p0-deploy/fixes.md, reviews/p0-deploy/tests.md
- Parent agent instructions (scope, base commit, final revision, stack verification context)

Git range:
- Branch: main
- HEAD: f8dcb3e5f224ec05a6edfa9182c1b26b192c30c1
- Base/start: 39a2396a330e2519e7ca417b825d3bea53505aea
- Commits reviewed: 6 (171cd93, ed889c6, df189bb, b37b9e0, 351ad92, f8dcb3e)
- Uncommitted changes: none at review start

Summary: 0 P0, 0 P1, 0 P2, 0 P3 (open). The 3 findings from the first review are all resolved.

## Status of earlier findings

- [x] **CR-1 | SPA fallback fills an unbounded resource cache (P2, Security): resolved**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/spa/SpaWebConfiguration.kt:31`
    - Verified: `.resourceChain(false)` registers only `SpaFallbackResourceResolver`, with no `CachingResourceResolver`, so client-route URLs no longer add cache entries. The KDoc explains why. The new test `resource chain keeps no unbounded resolver cache` (`SpaFallbackTests.kt:177`) reads the `/**` handler from `resourceHandlerMapping` and asserts that no caching resolver is in the chain and that the SPA resolver is. It tests the actual configured chain, not a mock. The existing route, static-file, 404, traversal and `no-cache` tests still cover behavior without the cache.

- [x] **CR-2 | Caddy's deferred CSP overwrote every server CSP (P2, Integration): resolved**
    - File: `deploy/Caddyfile:24`
    - Verified: `?Content-Security-Policy "..."` sets the app-wide CSP only when the upstream sent none. HSTS, X-Content-Type-Options, Referrer-Policy, Permissions-Policy and COOP are still forced via `defer`, and `-Server`/`-X-Powered-By` are still removed. The comment (lines 8-11) and `deploy/README.md` now describe this precedence. They also warn that a server CSP must bring its own `frame-ancestors`. The fixer's `caddy adapt` output (require `Content-Security-Policy: null` around the set) matches Caddy's documented `?` semantics. The parent reports the stack was rebuilt from f8dcb3e and answers health UP through Caddy, so the changed Caddyfile loads.

- [x] **CR-3 | gen-env.sh used the domain argument in sed unchecked (P3, Quality): resolved**
    - File: `deploy/gen-env.sh:14-20`
    - Verified by reading the code: the POSIX `case` pattern `*[!A-Za-z0-9.-]* | [.-]*` rejects any character outside letters, digits, `.` and `-`, which covers `|`, `&`, `\`, `/`, `:`, spaces and newlines. It also rejects a leading `.` or `-`. So nothing that could break the sed replacement reaches line 35. The check runs before any file is written, and the error goes to stderr with exit code 2. The documented calls (`cockpit.example.org`, `localhost`) pass. A side effect is that `host:port` values are rejected. That is correct, because ports are set separately (`COCKPIT_HTTP(S)_PORT`) and the README never passes a port in the domain. I could not run the pattern in this session, because the permission system denied running a shell snippet.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Notes (not findings)

- Re-checked on the final diff and still correct: the Dockerfile builds web with Node 24 (`.nvmrc`) and the server with JDK 21 (Gradle toolchain). It copies `web/dist/web/browser` into `src/main/resources/static/` before `bootJar`, runs as uid/gid 10001 with a `/data` it owns, and uses a layered JRE runtime. In the compose file, only Caddy publishes ports, Postgres has a healthcheck and the server waits for it, the four named volumes include the data dir, and required secrets fail fast via `:?`. The `.env.example` placeholder master key fails `MasterKey.decode` at startup, and `deploy/.gitignore` keeps `.env` out of Git. `host-backup.sh` stages the files in a 700 directory, cleans up on exit and needs the restic credentials. Its restore paths in the README match the staging layout. The ARCHITECTURE.md stack table and run table match the files.
- Test-writer additions (351ad92) assert behavior: `no-cache` HTML fallback, files in subdirectories and missing ones, HEAD, a real API controller not shadowed by `/**`, path traversal (raw and encoded) not reaching a file outside the web root, `/api/` stays 404, and resolver unit tests including a server started without a web build. No tests were weakened or removed.
- Still not verified by anyone (also noted in the first review): whether the CSP `script-src` hash matches the inline Angular/beasties script of the current build. A curl health check doesn't evaluate CSP. If the hash is wrong, the non-critical stylesheet stays `media="print"`. Checking once in a browser (no CSP violation in the console) would confirm it. This doesn't block criterion 3, which asks for the headers to be configured.
- `python tasks.py verify p0-deploy` shows criteria 1 and 4 recorded, while 2, 3 and 5 are not yet recorded. The parent worker still has to record them, for example criterion 2 with the `core.spa` test run, and criteria 3 and 5 with the files and the Caddy validate run.
- `reviews/p0-deploy/fixes.md` says "Changes are uncommitted in the working tree". The changes are now committed in f8dcb3e. This is only wording in a helper report, not a code issue.

## Review conclusion

No material issues found.
