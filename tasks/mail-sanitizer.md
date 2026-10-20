---
title: HTML mail sanitizer
status: in_progress
owner: R1963MACOS/agent-2
deps: [p0-server-deps]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/sanitize,server/src/test/kotlin/ch/sonensei/cockpit/mail/sanitize]
prio: 1
lease_until: 2026-10-20T09:12:22Z
---
## Goal
Make untrusted HTML safe (SPEC.md §4.1).

## Acceptance criteria
- [ ] jsoup-based sanitizer: removes scripts, event handlers, forms, iframes, `javascript:`/`data:` URLs (except inline images), external CSS; rewrites remote images to the proxy URL or blocks them per policy; preserves layout tables and inline styles minus `position`/`url()`
- [ ] Tests with a corpus of malicious samples (OWASP XSS cheat sheet vectors) and real-world newsletters (fictitious)
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sanitize.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
