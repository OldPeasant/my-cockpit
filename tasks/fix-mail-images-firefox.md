---
title: Mail images never load in Firefox (frame CSP 'self' in srcdoc)
status: in_progress
owner: felix/agent-1
deps: []
touches: [web/src/app/features/mail/read/mail-frame.ts,web/src/app/features/mail/read/mail-frame.spec.ts,web/e2e/mail-read,web/playwright.config.ts]
prio: 1
lease_until: 2026-11-08T08:29:55Z
---
## Goal
In Firefox no mail image ever shows, neither inline `cid:` images nor remote images after "Load images": the image
slots stay empty placeholders. Chromium works. Reported by the owner 2026-11-08 (Firefox, no add-ons).

Cause, reproduced on the e2e stack with Playwright Firefox 157: the mail body is rendered in an `<iframe srcdoc>`
whose meta CSP is `img-src 'self' data:` (`MAIL_FRAME_CSP`, `web/src/app/features/mail/read/mail-frame.ts`). Firefox
does not match `'self'` in an `about:srcdoc` document against the app origin, and logs
`Content-Security-Policy: The page's settings blocked the loading of a resource (img-src) at …/api/v1/mail/image-proxy?url=…
because it violates the following directive: "img-src 'self' data:"` (same for `…/attachments/2?inline=true`).
The server side is fine: the proxied image opens in a new tab.

Verified fix (tried, then reverted): name the app origin explicitly next to `'self'`, i.e. build the meta CSP with
`'self' ${location.origin}` for `img-src` and `font-src`. With it, the same Firefox probe loads both the proxied and
the `cid:` image.

## Acceptance criteria
- [ ] The mail frame CSP allows images and fonts from the app's own origin explicitly (in addition to `'self'`), still `default-src 'none'`, no scripts, no other hosts; `mail-frame.spec.ts` asserts the origin is in `img-src`/`font-src` and that no foreign host is
- [x] Playwright has a Firefox project that runs `e2e/mail-read/*` (install: `npx playwright install firefox`; document it in docs/ARCHITECTURE.md "Install"/E2E)
  - verified (2026-11-08 09:12+0100 by felix/agent-1): web/playwright.config.ts project 'firefox' (Desktop Firefox, testMatch mail-read/*.spec.ts, deps phase-demos); docs/ARCHITECTURE.md Install/E2E name 'npx playwright install --with-deps chromium firefox'; cd web && npm run e2e -- e2e/mail-read -> [firefox] mail-read/read.spec.ts passed
- [ ] `e2e/mail-read/read.spec.ts` also asserts that the inline `cid:` image loads, and it passes in Chromium and Firefox: `cd web && npm run e2e -- e2e/mail-read` -> pass for both projects
- [ ] `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08. Related: the existing e2e stubs the image proxy, so it never caught this; the "ResizeObserver loop
completed with undelivered notifications" errors the mail frame logs in the console may be silenced here if trivial
(`fit()` in mail-frame.ts), otherwise leave them.
- ASSUMPTION (2026-11-08 08:50+0100 by felix/agent-1): Firefox e2e project runs only e2e/mail-read (as the task names) and depends on phase-demos like chromium; the ResizeObserver console warning is left as is (not trivial without changing fit() timing)
