---
title: Web: search type filter, read-later capture, Android download link
status: in_progress
owner: dell26/agent-1
deps: [mail-images-web-settings]
touches: [web/src/app/features/search,web/src/app/features/capture,web/src/app/features/settings/general,web/src/locale]
prio: 3
lease_until: 2026-11-08T16:26:41Z
---
## Goal
Small web gaps found in the parity check (docs/PARITY.md): Android's global search has a type filter, Android
capture can send a URL to Read later, and web never offers the Android app download (API exists, SPEC.md §11).

## Acceptance criteria
- [ ] Ctrl+K search can filter by type (chips or `type:` prefix)
- [ ] Capture of a single URL offers "Inbox" or "Read later" (bookmark)
- [ ] Settings shows the Android app version with a download link (`/download/android`)
- [ ] New UI strings are in `web/src/locale` (English + German)
- [ ] Unit tests cover the new behavior; `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
