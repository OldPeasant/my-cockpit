# Code Review

Date: 2026-10-20

Task: p0-deploy - Docker image, compose stack, Caddy

Scope: committed changes of task p0-deploy, `git diff 39a2396..HEAD` (Dockerfile, .dockerignore, deploy/*, server core/spa main + test, docs/ARCHITECTURE.md edits)

Requirements:
- tasks/p0-deploy.md acceptance criteria 1-5 and recorded assumptions (SPA behind Boot default security until p0-auth-login; Caddy exposes only /actuator/health*)
- SPEC.md §4.1 (security headers via Caddy + Spring, sandboxed HTML email with CSP, master key), §11 (deployment and operations)
- docs/ARCHITECTURE.md (runtime versions, stack table, run instructions), AGENTS.md project notes
- Parent agent instructions (scope, base commit, task commits)

Git range:
- Branch: main
- HEAD: b37b9e04a4f4fa4844004b68093e590bf194d8a5
- Base/start: 39a2396a330e2519e7ca417b825d3bea53505aea
- Commits reviewed: 4 (171cd93, ed889c6, df189bb, b37b9e0)

Summary: 0 P0, 0 P1, 2 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | SPA fallback fills an unbounded resource cache, one entry per distinct client-route URL**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/spa/SpaWebConfiguration.kt:29`
    - Category: Security
    - Problem: `.resourceChain(true)` puts Spring's `CachingResourceResolver` in front of `SpaFallbackResourceResolver`, and its cache is a plain `ConcurrentMapCache` with no size limit. The cache key is the request path (plus an encoding suffix), and the cached value is any non-null resource. The fallback returns `index.html` for every path without an extension outside `/api` and `/actuator`, so every distinct URL such as `/x1`, `/x2`, ... or `/projects/<random>` adds a permanent entry.
    - Why it matters: once p0-auth-login makes the SPA public (as planned in the recorded assumption), any anonymous client can grow server heap without limit by requesting random paths, and it ends in an OutOfMemoryError. Normal use also adds one entry per visited entity URL for the life of the process. Responses are already sent with `Cache-Control: no-cache`, so the cache brings almost no benefit.
    - Suggested fix: use `.resourceChain(false)`, which drops the caching resolver and keeps the custom resolver. Another option is to keep caching and resolve the fallback outside the cached chain. Add a test asserting that client routes still work without the cache. If caching stays, add one asserting that distinct client routes don't grow a cache.

- [ ] **CR-2 | Caddy's deferred CSP overwrites every CSP the server sets, so per-response policies from Spring can't take effect**
    - File: `deploy/Caddyfile:10` (`defer`) and `deploy/Caddyfile:21` (`Content-Security-Policy`)
    - Category: Integration
    - Problem: the `header` block runs with `defer` and sets `Content-Security-Policy` unconditionally, so it replaces any CSP from the proxied response. The comment at line 8 says so ("these win over headers from the server"). SPEC.md §4.1 puts the security headers in "Caddy + Spring" and asks for HTML email to be rendered in a sandboxed iframe with its own CSP. The server can only send that per-response policy (for example `default-src 'none'; img-src <proxy>; sandbox`) as a response header, and this configuration will silently replace it with the app-wide policy. The app-wide policy allows `'self'` scripts and `frame-src 'self' blob:`.
    - Why it matters: a later mail task will add a strict CSP in Spring, its tests will pass against the server, and production behind Caddy will quietly run the weaker policy for untrusted email HTML. Nothing in this task fails today, but it builds in a header precedence that conflicts with the spec's split of responsibilities.
    - Suggested fix: set CSP only when the upstream didn't send one: Caddy's `?Content-Security-Policy "..."` sets a default without replacing an existing header. Keep `defer`/forced set for HSTS, X-Content-Type-Options and the removed headers. Update the comment at line 8 and the deploy/README.md note to say which headers the server may override.

## P3 Low

- [ ] **CR-3 | gen-env.sh puts the domain argument into the sed replacement without checking it**
    - File: `deploy/gen-env.sh:26`
    - Category: Quality
    - Problem: `$domain` goes straight into `s|^COCKPIT_DOMAIN=.*|COCKPIT_DOMAIN=$domain|`. A value containing `|` breaks the sed expression, `&` inserts the matched text, and with GNU sed a `\n` sequence adds an extra line to `.env`. The script then still reports success.
    - Why it matters: an operator who makes a typo or pastes a URL (`https://cockpit.example.org/`) gets a broken `deploy/.env`, and Caddy or the server fails later with a confusing error instead of the script failing right away.
    - Suggested fix: check the argument against a host-name pattern before using it (for example `case "$domain" in *[!A-Za-z0-9.-]*) echo "invalid domain" >&2; exit 2;; esac`) and print the usage line otherwise.

## Notes (not findings)

- Verified as correct: the Dockerfile uses Node 24 (matches `.nvmrc` = 24) and JDK 21 (matches the Gradle toolchain `JavaLanguageVersion.of(21)`). It copies `web/dist/web/browser` (the `@angular/build:application` default output, production by default) into `src/main/resources/static/` before `bootJar`, and runs as uid/gid 10001 with a `/data` it owns. `bootJar` doesn't need `../api/openapi.yaml` (only `tasks.test` declares it as an input), so the narrow `.dockerignore` context is enough. Actuator has no exposure settings, so only `health` is exposed, without details. Spring Security's `StrictHttpFirewall` rejects `;`, encoded `.`/`/` and non-normalized paths, so the Caddy `/actuator/*` filter can't be bypassed with path tricks. The `.env.example` placeholder `change-me` for `COCKPIT_MASTER_KEY` fails `MasterKey.decode` at startup, so a copied template can't run with a weak key. `deploy/.gitignore` keeps `.env` out of Git, and `gen-env.sh` writes it with umask 077.
- I could not check that the CSP `script-src` hash matches the inline beasties script in the current Angular build: hash computation wasn't available in this read-only session. A curl health check doesn't evaluate CSP. If the hash is wrong, the non-critical stylesheet stays `media="print"`. Checking it once in a browser (no CSP violation in the console) would confirm criterion 3's CSP in practice.
- `python tasks.py verify p0-deploy` shows no evidence recorded for any criterion yet. The parent worker still has to record evidence for criteria 1 and 4 (the podman build and the podman-compose health check through Caddy, which it says it ran).

## Review conclusion

Material issues found; fix before completing the task. Neither finding blocks an acceptance criterion. CR-1 (unbounded cache reachable by anonymous clients once the SPA is public) should be fixed before this lands. CR-2 should be fixed now or handed on to the mail/security tasks with an explicit assumption.
