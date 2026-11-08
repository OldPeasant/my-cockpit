---
title: Web mail images follow the synced image settings
status: in_progress
owner: dell26/agent-2
deps: [web-back-navigation,fix-mail-images-firefox]
touches: [web/src/app/features/mail/read,web/src/app/features/settings/general,web/src/locale]
prio: 1
lease_until: 2026-11-08T12:08:18Z
---
## Goal
The server already stores `mailImagePolicy` (`block`|`allow`, default `block`) and `mailImageAllowedSenders`
(SPEC.md §4.1, §4.7), but the web read view ignores them and keeps its per-sender "always load" list in
`localStorage` (`web/src/app/features/mail/read/image-allowlist.ts`), so it is per browser and invisible to Android.
The owner reports that missing images make some mails unusable; the decided default stays "block, load on request".

## Acceptance criteria
- [ ] The read view requests `remoteImages=PROXY` without asking when `mailImagePolicy` is `allow`, or when the sender is in `mailImageAllowedSenders`; otherwise it shows the existing "Load images" banner
- [ ] "Always load images from this sender" adds the sender to `mailImageAllowedSenders` via `PATCH /api/v1/settings` (no longer `localStorage`); entries found in the old `localStorage` key are merged into the setting once and the key is removed
- [x] Settings (General, or a Mail section in it) has a control "Remote images in mail: ask / always load" and a list of allowed senders with remove buttons
  - verified (2026-11-08 12:36+0100 by dell26/agent-2): Settings > General rows 'Remote images in mail' (Ask / Always load toggle, saves mailImagePolicy) and 'Always load images from' (list + Remove per sender): general-settings.spec.ts 'remote images in mail' (4 tests pass) and e2e mail-images/image-settings.spec.ts against bootRun+ng serve (toggle saved, sender listed and removed) -> passed; screenshot reviews/mail-images-web-settings/5-senders.png
- [x] Manually verified against `./gradlew bootRun` + `npm start` with a test mail containing a remote `https` image and a `cid:` image: blocked → "Load images" shows both; with `allow` both show immediately (screenshot or Playwright run recorded as evidence)
  - verified (2026-11-08 12:36+0100 by dell26/agent-2): Podman postgres:16-alpine (127.0.0.1:25432) + greenmail/standalone:2.1.5 (IMAP 23143/SMTP 23025); cd server && ./gradlew bootRun --args='--spring.profiles.active=dev,e2e --server.port=28080'; cd web && ng serve --port 24200 --proxy-config (proxy.conf.json to :28080); E2E_BASE_URL=http://localhost:24200 E2E_SERVER_GREENMAIL=localhost E2E_IMAP_PORT=23143 npx playwright test --project=inbox-exclusive --no-deps mail-images/ -> 1 passed (chromium): HTML mail with https remote image + cid: inline image; blocked -> banner, no remote request, inline shows; Load images -> proxied remote + inline loaded; Settings Always load -> reopened mail shows both at once, no banner; image proxy upstream stubbed via page.route (no internet). Screenshots reviews/mail-images-web-settings/{1-blocked,4-allowed,5-senders}.png
- [x] New UI strings are in `web/src/locale` (English + German)
  - verified (2026-11-08 12:36+0100 by dell26/agent-2): 9 units settings.mailImages* / settings.mailImageSenders* added to web/src/locale/messages.xlf (from npm run i18n:extract) and messages.de.xlf with German targets; npm run check incl. ng build + src/locale/messages-de.spec.ts -> passed
  - verified (2026-11-08 12:37+0100 by dell26/agent-2): 9 units settings.mailImages* / settings.mailImageSenders* added to web/src/locale/messages.xlf (from npm run i18n:extract) and messages.de.xlf with German targets; npm run check (incl. src/locale/messages-de.spec.ts) -> passed; npm run build:de -> no missing translation for this task's ids (its 16 'No translation found' errors are all calendar.invite.* already on main from another task)
- [ ] Unit tests cover the new behavior; `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
Out of scope: Android (task and-mail-images). If "Load images" turns out not to work for real mails (proxy errors, CSP), fix it here and record what was broken.
- ASSUMPTION (2026-11-08 12:30+0100 by dell26/agent-2): mailImageAllowedSenders entries match a sender by exact address (case-insensitive) or, when the entry has no local part (example.test or @example.test), by exact domain (no subdomains), as the API describes 'addresses or domains'. 'Stop' in the read view removes every entry that allows that sender (address and domain), so images are asked for again.
- ASSUMPTION (2026-11-08 12:30+0100 by dell26/agent-2): The image settings live in Settings > General (rows 'Remote images in mail: Ask / Always load' and 'Always load images from' with Remove buttons); no add-sender field there, senders are added from the read view. The old localStorage list is merged when the read view first opens.
- ASSUMPTION (2026-11-08 12:30+0100 by dell26/agent-2): With mailImagePolicy=allow the read view requests remoteImages=PROXY in its first load; if that load fails the page shows the normal error state (no fallback to a blocked load).
