# Code Review

Date: 2026-11-09

Task: spa-stale-index - SPA: deploys leave browsers on a stale index.html (constant 1980 Last-Modified)

Scope: git diff 28ee9f350a33ab55fabf30c99c20c2ef4ddcecff..fd99730285abab394a69eb6fc7a24be2cef6d16e. The production change is in SpaWebConfiguration.kt. The tests are ContentEtagsTests.kt and SpaFallbackTests.kt. This is the re-review of the final revision after the fixes for CR-1 and CR-2 from the first review. HEAD (01a447513) differs from fd9973028 only in tasks/spa-stale-index.md. This was a static review: the parent asked that Gradle not be run because a test-writer runs it in parallel. fixes.md reports that `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.spa.*'` passed (ContentEtagsTests 10/10, SpaFallbackTests 19/19).

Requirements:
- tasks/spa-stale-index.md: acceptance criteria and the two recorded assumptions, including the corrected one about caching digests for classpath: locations
- AGENTS.md project notes
- Dockerfile (unpacked boot jar in production)
- reviews/spa-stale-index/fixes.md
- Spring ResourceHttpRequestHandler semantics (setUseLastModified, setEtagGenerator, checkNotModified)

Git range:
- Branch: main
- HEAD: 01a447513 (reviewed code revision fd9973028; the commits after it change only tasks/)
- Base/start: 28ee9f350a33ab55fabf30c99c20c2ef4ddcecff
- Commits reviewed: 5 (3 code/test commits plus 2 board commits)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-3 | No test covers how the location prefix selects digest caching**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/spa/SpaWebConfiguration.kt:46`
    - Category: Tests
    - Problem: `cacheDigests = location.startsWith(ResourceUtils.CLASSPATH_URL_PREFIX)` is the only thing that turns caching on for production. ContentEtagsTests test both modes of `ContentEtags` directly. SpaFallbackTests always use a `file:` location. So no test fails if this condition is inverted or dropped. If it is inverted, production goes back to the CR-1 per-request hashing, and dev/test serves stale ETags for edited files.
    - Why it matters: This is a low-risk one-liner, but it decides the production performance behaviour that CR-1 was about. Today only review protects it.
    - Suggested fix: This is optional. Move the decision into a small function (for example `ContentEtags.forLocation(location)` or an internal `cachesDigests(location)`), and unit-test it for `classpath:/static/` (true) and a `file:` URI (false).

## Previous findings

- CR-1 (P2, production re-hashes on every request): Resolved. Caching no longer depends on `Resource.isFile`. A constructor flag, set from the configured location, decides it (SpaWebConfiguration.kt:46). `classpath:/static/` is the default and is not overridden anywhere in the repository (`git grep` finds `cockpit.spa.location` only in the test). Production therefore caches digests for the unpacked jar the Dockerfile runs. The cache key is the resource URL, and the map is bounded by the number of build files, because client routes resolve to `index.html`. The KDoc of `SpaWebConfiguration` (lines 23-34) and of `ContentEtags` (lines 83-92) now describes the unpacked-jar case correctly. The corrected assumption (2026-11-09 11:00) matches the code.
- CR-2 (P3, cache path untested): Resolved. ContentEtagsTests now cover four cases. A real file with caching (`isFile` true, the unpacked-jar case) is read once and stays content-based. A jar-URL resource is cached per URL, and a new instance sees new content. Without caching, a resource is read on every request (file and non-file). A read error propagates and is not cached. A failed `computeIfAbsent` stores no mapping, which matches the test.

Verified without findings:
- Criterion 1: `setUseLastModified(false)` stops `If-Modified-Since: 1980` from producing a 304, and an old `If-None-Match` does not match the new SHA-256 ETag. The SpaFallbackTests test runs against a fixture with the same length and the 1980 mtime. It sends both validators, and the date alone, to `/index.html`, `/inbox` and a nested client route, and expects 200 with the new content. It would get 304 with the fix reverted: the `oldEtag` fallback still leaves the date check to reproduce the bug. `/` is covered as a forward to `/index.html`.
- Criterion 2: a 304 for a matching `If-None-Match` on the index, the fallback and the bundle. No `Last-Modified` header. `Cache-Control: no-cache` is kept on hashed bundles. The fallback and the bundle get different ETags, and `/inbox` gets the same ETag as `/index.html`. `/main-GONE.js` stays 404. The fallback resolver did not change.
- Criterion 3: the KDoc matches the code (`no-cache`, content ETag, `Last-Modified` disabled, caching for classpath: locations and hashing per request for other locations).
- `computeIfAbsent` holds the map bin lock while one file is hashed. This happens once per file per server lifetime, so it is negligible.
- Security: no new input handling, no logging, and no change to which paths are served. `/api/`, `/actuator/` and `/download/` still never fall back.
- Tests use fictitious data, restore the shared fixture in `finally`, and no assertion was weakened.

## Review conclusion

No material issues found. CR-1 and CR-2 are resolved. The only new finding, CR-3, is an optional low-priority test gap and does not block completing the task.
