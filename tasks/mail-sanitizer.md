---
title: HTML mail sanitizer
status: in_progress
owner: dell26/agent-5
deps: [p0-server-deps]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/sanitize,server/src/test/kotlin/ch/sonensei/cockpit/mail/sanitize]
prio: 1
lease_until: 2026-10-20T09:53:59Z
releases: 0
extended: 
---
## Goal
Make untrusted HTML safe (SPEC.md §4.1).

## Acceptance criteria
- [x] jsoup-based sanitizer: removes scripts, event handlers, forms, iframes, `javascript:`/`data:` URLs (except inline images), external CSS; rewrites remote images to the proxy URL or blocks them per policy; preserves layout tables and inline styles minus `position`/`url()`
  - verified (2026-10-20 11:34+0200 by dell26/agent-5): HtmlMailSanitizerTests/EdgeCaseTests/InlineStyleSanitizerTests (jsoup sanitizer: scripts, on* handlers, forms, iframes, javascript:/data: URLs except raster inline images, link/@import/style removed, remote images proxied or blocked per policy, tables + inline styles minus position/url() kept) via ./gradlew test --tests 'ch.sonensei.cockpit.mail.sanitize.*' -> 390 passed, 0 failed
- [ ] Tests with a corpus of malicious samples (OWASP XSS cheat sheet vectors) and real-world newsletters (fictitious)
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sanitize.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
- ASSUMPTION (2026-10-20 10:57+0200 by R1963MACOS/agent-2): Blocked remote images keep the <img> without src plus data-cockpit-blocked-image=true; SanitizedHtml reports remoteImageCount/remoteImagesBlocked so the client can show 'load images'. Proxy URL format is injected via ImageProxyUrlBuilder (the proxy endpoint is a later task).
- ASSUMPTION (2026-10-20 10:57+0200 by R1963MACOS/agent-2): Inline SVG and data:image/svg+xml are removed entirely (can carry script); only raster data: images (png/jpeg/gif/webp/bmp, base64) are kept. Links get target=_blank rel='noopener noreferrer nofollow'.
- ASSUMPTION (2026-10-20 11:00+0200 by R1963MACOS/agent-2): Embedded <style> blocks are removed too, not only external CSS (<link>/@import): stylesheet rules can't be scoped to the mail safely server-side, so newsletters lose @media/class rules but keep inline styles.
- release by R1963MACOS/agent-2: handed off by R1963MACOS/agent-2: You've hit your session limit · resets 2:50pm (Europe/Zurich)
