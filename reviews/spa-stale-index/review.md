# Code Review

Date: 2026-11-09

Task: spa-stale-index - SPA: deploys leave browsers on a stale index.html (constant 1980 Last-Modified)

Scope: git diff 28ee9f350a33ab55fabf30c99c20c2ef4ddcecff..d0dc41f700ff3036655218f474bcd70214b91c4b (the whole task). The production change is in SpaWebConfiguration.kt. The tests are ContentEtagsTests.kt, SpaFallbackTests.kt and SpaWebConfigurationTests.kt. This is the third review. The previous review covered revision fd9973028. Between fd9973028 and d0dc41f70 the only code change is the new test file SpaWebConfigurationTests.kt. The other changes are in reviews/ and tasks/. SpaWebConfiguration.kt, ContentEtagsTests.kt and SpaFallbackTests.kt are unchanged since the last review. This was a static review: the parent asked that Gradle not be run because it runs Gradle in parallel. Earlier runs are recorded in fixes.md and tests.md.

Requirements:
- tasks/spa-stale-index.md: acceptance criteria and the two recorded assumptions, including the corrected one about caching digests for classpath: locations
- AGENTS.md project notes
- Dockerfile (unpacked boot jar in production)
- reviews/spa-stale-index/fixes.md and reviews/spa-stale-index/tests.md
- Spring ResourceHandlerRegistry and ResourceHttpRequestHandler semantics (getHandlerMapping, setUseLastModified, setEtagGenerator, checkNotModified)

Git range:
- Branch: main
- HEAD: d0dc41f700ff3036655218f474bcd70214b91c4b
- Base/start: 28ee9f350a33ab55fabf30c99c20c2ef4ddcecff
- Commits reviewed: 10 (4 code/test/report commits plus 6 board commits). The working tree is clean.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None open.

## Previous findings

- CR-1 (P2, production re-hashes on every request): Resolved in 8c7564f2e. Caching no longer depends on `Resource.isFile`. A constructor flag set from the configured location decides it (SpaWebConfiguration.kt:46). `classpath:/static/` is the default, so production caches digests for the unpacked jar the Dockerfile runs. The cache key is the resource URL. The map is bounded by the number of build files, because client routes resolve to `index.html`. The KDoc (lines 23-34 and 83-92) and the corrected assumption match the code.
- CR-2 (P3, cache path untested): Resolved in 8c7564f2e. ContentEtagsTests cover four cases. With caching, a real file is read once and the ETag stays content-based. With caching, a jar-URL resource is cached per URL. Without caching, the content is read on every request. A read error propagates and is not cached.
- CR-3 (P3, no test covers how the location prefix selects digest caching): Resolved in d0dc41f70. SpaWebConfigurationTests builds the real handler through `SpaWebConfiguration(location).addResourceHandlers` and a `ResourceHandlerRegistry` subclass that exposes the protected `getHandlerMapping()`. It takes the `/**` `ResourceHttpRequestHandler` from the `SimpleUrlHandlerMapping` and calls its actual `etagGenerator`. For `classpath:/static/`, two calls read the resource once (`reads shouldBe 1`). For a `file:` temp-dir location, a content change gives the new digest and two reads. If the `startsWith(CLASSPATH_URL_PREFIX)` condition were inverted or removed, at least one of these tests would fail. A third test checks that both locations have `isUseLastModified == false` and `Cache-Control: no-cache`. This guards the criterion 1 wiring at unit level as well as through SpaFallbackTests. The test uses only fictitious URLs and content, has no shared state, and needs no running server. The `CountingResource` URLs do not have to match the location, because the generator only uses `resource.url` as the cache key.

Verified without findings (whole task diff at d0dc41f70):
- Criterion 1: `setUseLastModified(false)` stops `If-Modified-Since: 1980` from producing a 304. An old `If-None-Match` does not match the new SHA-256 ETag. SpaFallbackTests reproduce the bug with a fixture that has the same length and the 1980 mtime. They send both validators, and the date alone, to `/index.html`, `/inbox` and a nested client route, and expect 200 with the new content.
- Criterion 2: a matching `If-None-Match` gets a 304 on the index, the fallback and the bundle. No `Last-Modified` header is sent. Hashed bundles keep `Cache-Control: no-cache`. `/inbox` gets the same ETag as `/index.html`. `/main-GONE.js` stays 404. The fallback resolver did not change.
- Criterion 3: the KDoc matches the code.
- Criterion 4: not re-run here, as the parent asked. The parent runs `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.spa.*'` and must record the result for d0dc41f70.
- Security: there is no new input handling and no logging. The set of served paths did not change. `/api/`, `/actuator/` and `/download/` still never fall back.
- The reports tests.md and fixes.md at d0dc41f70 contain no unfinished placeholders.

## Review conclusion

No material issues found. CR-1, CR-2 and CR-3 are resolved. The whole task diff 28ee9f350a33..d0dc41f70 has no open findings.
