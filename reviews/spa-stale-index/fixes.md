# Fix Report

Date: 2026-11-09

Task: spa-stale-index - SPA: deploys leave browsers on a stale index.html (constant 1980 Last-Modified)

Scope: current task, findings CR-1 and CR-2 from reviews/spa-stale-index/review.md (approved fix supplied by the parent worker)

## Fixed findings

### CR-1 — Production image re-hashes every SPA file on every request

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/spa/SpaWebConfiguration.kt`

Problem:
`ContentEtags.etag` cached digests only when `resource.isFile` was false. The production Dockerfile runs the boot jar unpacked, so `classpath:/static/` resolves to real files, `isFile` is true, and every request read the whole file and computed SHA-256 again, including multi-MB bundles and 304 answers. The KDoc described production wrongly.

Fix:
`ContentEtags` now takes a constructor flag `cacheDigests: Boolean`. With the flag set, digests are cached per resource URL. Without it, the content is hashed on every request. The flag no longer depends on `Resource.isFile`. `SpaWebConfiguration` passes `cacheDigests = location.startsWith(ResourceUtils.CLASSPATH_URL_PREFIX)`. A `classpath:` location is cached, packed or unpacked, because classpath content never changes while the server runs. A `file:` `cockpit.spa.location` (dev and tests) is hashed per request. The KDoc of both classes now describes this, including the unpacked-jar production case under `BOOT-INF/classes/static/`. ETag values, `Cache-Control: no-cache`, the disabled `Last-Modified` and the fallback behaviour are unchanged.

Validation:
- `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.spa.*'` — Pass (ContentEtagsTests 10/10, SpaFallbackTests 19/19)

### CR-2 — The digest cache path has no test

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/core/spa/ContentEtagsTests.kt`

Problem:
Every test ran only the hash-per-request branch. The caching branch that carries production load was not tested in the integration tests, and the unit test covered it only for non-file resources.

Fix:
The tests now use the new constructor: `etags = ContentEtags(cacheDigests = false)` and `cached = ContentEtags(cacheDigests = true)`. New or adjusted tests:
- With caching, a real file (the unpacked-jar case, `isFile` true) is read once. Its digest is reused, it equals the SHA-256 of the content, and a different file gets a different digest.
- With caching, a jar entry is read once per URL. A new instance sees new content.
- Without caching, a file is read on every request.
- Without caching, a non-file resource is read on every request and its changed content is picked up.
- A read error with caching propagates and is not cached.
The existing content tests (test vectors, large content, in-place change with the same size and timestamp) run with `cacheDigests = false`, which matches the dev/test `file:` location they model. SpaFallbackTests use a `file:` location, so they still exercise hashing per request end to end. No assertion was weakened.

Validation:
- `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.spa.*'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` — no remaining changes reported
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.spa.*'` — BUILD SUCCESSFUL; ContentEtagsTests 10 tests, 0 failures; SpaFallbackTests 19 tests, 0 failures

Full test suite:
- Not run (the parent worker runs `.taskcheck` and the full suite; running Gradle in parallel with other helpers clashes on the build directory)

Lint/type-check/build:
- `./gradlew ktlintCheck` — Pass; compileKotlin and compileTestKotlin — Pass

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0

Note for the parent worker: the recorded task assumption ("Digests are cached per URL for jar entries only") still describes the old behaviour. Record a corrected assumption with `tasks.py assume`. The fixer does not change task state.

## Final status

All validated findings fixed and validation passed.
