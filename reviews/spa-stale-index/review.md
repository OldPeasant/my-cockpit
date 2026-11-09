# Code Review

Date: 2026-11-09

Task: spa-stale-index - SPA: deploys leave browsers on a stale index.html (constant 1980 Last-Modified)

Scope: git diff 28ee9f350..7e9827424 (task commit 7e9827424, SpaWebConfiguration.kt and SpaFallbackTests.kt). Static review only. Gradle was not run, at the parent's request, because a test-writer was running it in parallel. The parent reports that ktlintCheck and the spa tests pass, and that the new tests fail with the main change reverted.

Requirements:
- tasks/spa-stale-index.md acceptance criteria and recorded assumption
- AGENTS.md project notes
- Dockerfile (production run layout)
- Spring Framework 7 / Boot 4.1.1 ResourceHttpRequestHandler semantics (setUseLastModified, setEtagGenerator, checkNotModified)

Git range:
- Branch: main
- HEAD: 2a1edb2ce (task commit 7e9827424 reviewed)
- Base/start: 28ee9f350a33ab55fabf30c99c20c2ef4ddcecff
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Production image re-hashes every SPA file on every request; the digest cache and KDoc assume a packed jar the image never uses**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/spa/SpaWebConfiguration.kt:81-93`
    - Category: Correctness (performance) / Quality
    - Problem: `ContentEtags.etag` caches digests only when `!resource.isFile`. The KDoc says that cached case covers "files inside the boot jar" and that files on disk are only "a dev `cockpit.spa.location`". But the production `Dockerfile` (lines 27 and 37-46) extracts the boot jar (`-Djarmode=tools extract --layers --launcher`) and runs `JarLauncher` from the exploded directory. In production, `classpath:/static/` therefore resolves to real files under `/app/BOOT-INF/classes/static/`, and `isFile` is true. Each request for `index.html`, a client route, or a hashed `main-*.js`/`chunk-*.js` bundle reads the whole file once to compute SHA-256, then reads it again to serve it (including on 304 responses). The cache branch is only used with `java -jar app.jar`, which this deployment never runs. The recorded assumption ("Digests are cached per URL for jar entries only") has the same mistaken premise.
    - Why it matters: Behaviour is still correct, so the stale-index bug is fixed. But every page load costs the server a full read plus SHA-256 of all bundles, often several MB, and the "cheap 304" still reads the whole file. Also, the documentation of the caching behaviour (criterion 3 is about exactly this) gives the wrong picture of production.
    - Suggested fix: Choose whether to cache from the configured location, not from `Resource.isFile`. Cache digests for `classpath:` locations: classpath content never changes while the server runs, whether packed or exploded. Hash per request only for a `file:` `cockpit.spa.location`. An alternative is to key the cache on URL + `lastModified()` + `contentLength()` and to state the dev limitation. Then update the ContentEtags KDoc and the recorded assumption so they name the exploded Docker layout.

## P3 Low

- [ ] **CR-2 | The digest cache path has no test**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/core/spa/SpaFallbackTests.kt:221-310`
    - Category: Tests
    - Problem: Every test serves the fixture from a temp directory (`file:` location), so only the hash-per-request branch of `ContentEtags.etag` runs. The `computeIfAbsent` cache branch is never run. If CR-1 is fixed by caching classpath resources, the test should show two things: a cached digest is reused, and it stays content-based (the ETag differs between files and matches for `/inbox` and `/index.html`).
    - Why it matters: Nothing verifies the code path that is meant to carry the production load.
    - Suggested fix: Add a small unit test for `ContentEtags` with a `ClassPathResource` or `UrlResource` (or whatever resource type the fixed code caches): two calls return the same value, and two different contents give different values.

Verified without findings:
- Criterion 1: `setUseLastModified(false)` makes Spring pass `lastModified = -1` to `checkNotModified`, so `If-Modified-Since: 1980` is ignored. An old `If-None-Match` does not match the new SHA-256 ETag, so the server answers 200. The test covers `/index.html`, `/inbox` and a nested client route with both validators and with the date alone. The fixture uses same-length content and the 1980 mtime, so with the fix reverted the server would answer 304. `/` is covered as a forward to `/index.html`, and MockMvc does not follow forwards, which is acceptable.
- Criterion 2: The test covers a 304 for a matching `If-None-Match` on index, fallback and bundle. `Cache-Control: no-cache` is kept on hashed bundles (they stay cacheable with revalidation). A missing `/main-GONE.js` stays 404, and the fallback logic was not changed.
- The ETag value is unquoted hex, and Spring's `ServletWebRequest` adds the quotes. It is a strong ETag.
- No security-relevant change. `/api/`, `/actuator/` and `/download/` are unaffected: the download is served by a controller. Nothing new is logged.
- Tests that change `index.html` restore it in `finally`. Test classes run in separate forks, and the methods of one class run one after another, so the shared fixture is safe.

## Review conclusion

Material issues found; fix before completing the task. The functional fix is correct and the acceptance tests are meaningful. CR-1 is a non-blocking but real production inefficiency, and the KDoc and assumption describe production wrongly. Fix it or consciously accept it, and record the decision.
