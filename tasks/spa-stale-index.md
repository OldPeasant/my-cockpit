---
title: SPA: deploys leave browsers on a stale index.html (constant 1980 Last-Modified)
status: in_progress
owner: dell26/agent-1
deps: []
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/spa,server/src/test/kotlin/ch/sonensei/cockpit/core/spa]
prio: 1
lease_until: 2026-11-09T10:08:47Z
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
- [ ] A conditional GET of `/index.html`, `/` and a client route (e.g. `/inbox`) with the validators of an earlier
      `index.html` (`If-Modified-Since` 1980 date and/or an old `If-None-Match`) returns 200 with the current content
      when the file changed, not 304; server test reproduces the bug (fails with the fix reverted)
- [ ] Unchanged files still revalidate cheaply (304 for a matching `If-None-Match`) or are never cached (`no-store`)
      for `index.html`; hashed `*.js`/`*.css` remain cacheable; a missing `*.js` stays a 404 (no `index.html` fallback)
- [ ] KDoc of `SpaWebConfiguration` describes the actual caching behaviour
- [ ] `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.spa.*'` passes

## Notes
- Users already stuck on the stale page recover with Ctrl+Shift+R or "Clear site data"; no migration needed.
