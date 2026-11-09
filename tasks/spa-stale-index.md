---
title: SPA: deploys leave browsers on a stale index.html (constant 1980 Last-Modified)
status: in_progress
owner: dell26/agent-1
deps: []
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/spa,server/src/test/kotlin/ch/sonensei/cockpit/core/spa]
prio: 1
lease_until: 2026-11-09T10:19:46Z
---
## Goal
After a deploy, browsers that opened My Cockpit before keep their cached `index.html`, which points at hashed
bundles the new build no longer has: `/main-*.js` and `/chunk-*.js` return 404 `application/problem+json` and
the page stays blank until a hard reload. Seen in production on 2026-11-09 (my-cockpit.sonensei.ch, after an
OAuth reconnect reloaded the app).

Cause: `SpaWebConfiguration` serves the build from the boot jar with `Cache-Control: no-cache`, relying on
revalidation. The only validator is `Last-Modified`, and every jar entry has the same fixed timestamp
(`Fri, 01 Feb 1980 00:00:00 GMT`, reproducible jar), so a conditional request always gets `304 Not Modified`,
even when `index.html` changed. The KDoc's promise "a deploy never leaves a stale index.html" does not hold.

Fix direction (agent decides, record with `tasks.py assume`): validate by content, not by date. For example, turn off
`Last-Modified` for the SPA resources and send a content-based ETag (Spring `ResourceHandlerRegistration`
`setUseLastModified(false)` + `setEtagGenerator`), and/or send `index.html` (also via the client-route fallback)
with `Cache-Control: no-store`. Hashed bundles may keep revalidating or get a long max-age.

## Acceptance criteria
- [x] A conditional GET of `/index.html`, `/` and a client route (e.g. `/inbox`) with the validators of an earlier
      `index.html` (`If-Modified-Since` 1980 date and/or an old `If-None-Match`) returns 200 with the current content
      when the file changed, not 304; server test reproduces the bug (fails with the fix reverted)
  - verified (2026-11-09 11:03+0100 by dell26/agent-1): SpaFallbackTests 'changed index html is sent again despite the validators of the old one' (index.html rewritten with same length + 1980 mtime; GET /index.html, /inbox, /projects/{id} with old If-None-Match + If-Modified-Since 1980, and IMS only -> 200 with new content) and 'root forwards a conditional request' (/ with IMS 1980 -> 200 forward /index.html); ./gradlew test --tests 'ch.sonensei.cockpit.core.spa.*' passes with fix, and with SpaWebConfiguration.kt reverted (git stash) -> 2 FAILED (the changed-index and revalidate tests, 304 instead of 200)
- [ ] Unchanged files still revalidate cheaply (304 for a matching `If-None-Match`) or are never cached (`no-store`)
      for `index.html`; hashed `*.js`/`*.css` remain cacheable; a missing `*.js` stays a 404 (no `index.html` fallback)
- [x] KDoc of `SpaWebConfiguration` describes the actual caching behaviour
  - verified (2026-11-09 11:00+0100 by dell26/agent-1): Read SpaWebConfiguration KDoc (lines 16-35): describes no-cache for all SPA files, content SHA-256 ETag instead of Last-Modified (1980 jar timestamp), 200 on change / 304 on matching If-None-Match, digest caching for classpath: vs per-request hashing for file: locations -> matches the code (setUseLastModified(false), setEtagGenerator(ContentEtags(...)::etag))
- [x] `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.spa.*'` passes
  - verified (2026-11-09 11:03+0100 by dell26/agent-1): cd server && ./gradlew ktlintCheck test --rerun --tests 'ch.sonensei.cockpit.core.spa.*' at d0dc41f70 -> BUILD SUCCESSFUL, 36 tests 0 failures (ContentEtagsTests 10, SpaFallbackResourceResolverTests 4, SpaFallbackTests 19, SpaWebConfigurationTests 3)

## Notes
- Users already stuck on the stale page recover with Ctrl+Shift+R or "Clear site data"; no migration needed.
- ASSUMPTION (2026-11-09 10:52+0100 by dell26/agent-1): SPA caching: kept Cache-Control no-cache for all SPA files (index.html, fallback, hashed bundles) and replaced Last-Modified with a strong SHA-256 content ETag (setUseLastModified(false) + setEtagGenerator); no no-store/max-age split, so unchanged files cost a 304 and hashed bundles stay cacheable with revalidation. Digests are cached per URL for jar entries only; files on disk are hashed per request.
- ASSUMPTION (2026-11-09 11:00+0100 by dell26/agent-1): Correction to the caching assumption: ETag digests are cached per URL when cockpit.spa.location is a classpath: location (production, also with the unpacked jar of the Docker image), and recomputed per request for a file: location (dev/tests), since the classpath never changes while the server runs.
