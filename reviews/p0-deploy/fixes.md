# Fix Report

Date: 2026-10-20

Task: p0-deploy - Docker image, compose stack, Caddy

Scope: findings CR-1, CR-2, CR-3 from `reviews/p0-deploy/review.md` (review of HEAD b37b9e0), as assigned by the parent worker. Changes are uncommitted in the working tree.

## Fixed findings

### CR-1 — SPA fallback fills an unbounded resource cache

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/spa/SpaWebConfiguration.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/spa/SpaFallbackTests.kt`

Problem:
`.resourceChain(true)` put Spring's `CachingResourceResolver` (an unbounded `ConcurrentMapCache`) in front of `SpaFallbackResourceResolver`. Every distinct extension-less URL resolved to `index.html` became a permanent cache entry.

Fix:
Changed to `.resourceChain(false)`, which keeps the custom `SpaFallbackResourceResolver` but drops the caching resolver. Added a KDoc sentence explaining why. Added the test `resource chain keeps no unbounded resolver cache`. It takes the `/**` `ResourceHttpRequestHandler` from the `resourceHandlerMapping` bean and asserts two things: no `CachingResourceResolver` is in the chain, and `SpaFallbackResourceResolver` is. The existing client-route, static-file, 404 and traversal tests in `SpaFallbackTests` still pass without the cache.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.spa.*' ktlintCheck` — Pass (SpaFallbackTests 14/14 including the new test, SpaFallbackResourceResolverTests 4/4, ktlint clean)
- A negative check (temporarily switching back to `true` to watch the new test fail) was blocked by the permission system, so it was not run.

### CR-2 — Caddy's deferred CSP overwrites every CSP the server sets

Files changed:
- `deploy/Caddyfile`
- `deploy/README.md`

Problem:
The deferred `header` block set `Content-Security-Policy` unconditionally, so it replaced any stricter per-response CSP from Spring (for example the one for the sandboxed HTML email, SPEC.md §4.1).

Fix:
Changed the line to `?Content-Security-Policy "..."`, so Caddy sets its CSP only when the upstream response has none. HSTS, X-Content-Type-Options, Referrer-Policy, Permissions-Policy and COOP are still forced with `defer`, and `-Server` and `-X-Powered-By` are still removed. Updated the Caddyfile comment to say the CSP is a default. It also says a server-sent CSP replaces Caddy's completely, so it must include its own `frame-ancestors`. Added a matching note in `deploy/README.md`.

Validation:
- `docker compose -f deploy/docker-compose.yml run --rm --no-deps caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile` — Pass ("Valid configuration")
- `docker compose -f deploy/docker-compose.yml run --rm --no-deps caddy caddy adapt --config /etc/caddy/Caddyfile --adapter caddyfile` — the adapted JSON has `"require":{"headers":{"Content-Security-Policy":null}}` with `"set":{"Content-Security-Policy":[...]}`, so the CSP is set only when the response has none. The running stack was not restarted, so the live Caddy still runs the old config until it is reloaded or restarted.

### CR-3 — gen-env.sh puts the domain argument into sed without checking it

Files changed:
- `deploy/gen-env.sh`

Problem:
`$domain` went straight into a `sed` replacement. `|`, `&` or `\n` in the argument could corrupt `deploy/.env`, and the script still reported success.

Fix:
Added a POSIX `case` check after the empty-argument check: `*[!A-Za-z0-9.-]* | [.-]*` prints `invalid domain '...'` and the usage line to stderr, then exits 2. This allows host names such as `cockpit.example.org` and `localhost`, and rejects URLs (`https://...`), spaces, `|`, `&`, `\` and a leading dot or hyphen. The check runs before the `.env` existence check, so an invalid argument never writes anything.

Validation:
- Not run. The permission system blocked running the script (`sh` on a temp copy, and `sh -n` for a syntax check). Only the diff was reviewed.

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.spa.*' ktlintCheck` — Pass (18 tests, 0 failures)
- Caddy `validate` and `adapt` in the compose caddy service — Pass
- `deploy/gen-env.sh` — not run (blocked by the permission system)

Full test suite:
- Not run by the fixer. The parent worker runs it.

Lint/type-check/build:
- `ktlintCheck` — Pass

`.taskcheck`:
- Not run (it is `sh scripts/check.sh`, and running shell scripts with `sh` is blocked here). The parent worker runs it.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed. Exception: the gen-env.sh change (CR-3) could not be run here.
