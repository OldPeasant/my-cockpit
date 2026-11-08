---
title: Web mail images follow the synced image settings
status: in_progress
owner: dell26/agent-2
deps: [web-back-navigation,fix-mail-images-firefox]
touches: [web/src/app/features/mail/read,web/src/app/features/settings/general,web/src/locale]
prio: 1
lease_until: 2026-11-08T11:53:11Z
---
## Goal
The server already stores `mailImagePolicy` (`block`|`allow`, default `block`) and `mailImageAllowedSenders`
(SPEC.md §4.1, §4.7), but the web read view ignores them and keeps its per-sender "always load" list in
`localStorage` (`web/src/app/features/mail/read/image-allowlist.ts`), so it is per browser and invisible to Android.
The owner reports that missing images make some mails unusable; the decided default stays "block, load on request".

## Acceptance criteria
- [ ] The read view requests `remoteImages=PROXY` without asking when `mailImagePolicy` is `allow`, or when the sender is in `mailImageAllowedSenders`; otherwise it shows the existing "Load images" banner
- [ ] "Always load images from this sender" adds the sender to `mailImageAllowedSenders` via `PATCH /api/v1/settings` (no longer `localStorage`); entries found in the old `localStorage` key are merged into the setting once and the key is removed
- [ ] Settings (General, or a Mail section in it) has a control "Remote images in mail: ask / always load" and a list of allowed senders with remove buttons
- [ ] Manually verified against `./gradlew bootRun` + `npm start` with a test mail containing a remote `https` image and a `cid:` image: blocked → "Load images" shows both; with `allow` both show immediately (screenshot or Playwright run recorded as evidence)
- [ ] New UI strings are in `web/src/locale` (English + German)
- [ ] Unit tests cover the new behavior; `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
Out of scope: Android (task and-mail-images). If "Load images" turns out not to work for real mails (proxy errors, CSP), fix it here and record what was broken.
- ASSUMPTION (2026-11-08 12:30+0100 by dell26/agent-2): mailImageAllowedSenders entries match a sender by exact address (case-insensitive) or, when the entry has no local part (example.test or @example.test), by exact domain (no subdomains), as the API describes 'addresses or domains'. 'Stop' in the read view removes every entry that allows that sender (address and domain), so images are asked for again.
- ASSUMPTION (2026-11-08 12:30+0100 by dell26/agent-2): The image settings live in Settings > General (rows 'Remote images in mail: Ask / Always load' and 'Always load images from' with Remove buttons); no add-sender field there, senders are added from the read view. The old localStorage list is merged when the read view first opens.
- ASSUMPTION (2026-11-08 12:30+0100 by dell26/agent-2): With mailImagePolicy=allow the read view requests remoteImages=PROXY in its first load; if that load fails the page shows the normal error state (no fallback to a blocked load).
