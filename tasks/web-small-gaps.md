---
title: Web: search type filter, read-later capture, Android download link
status: in_progress
owner: dell26/agent-1
deps: [mail-images-web-settings]
touches: [web/src/app/features/search,web/src/app/features/capture,web/src/app/features/settings/general,web/src/locale]
prio: 3
lease_until: 2026-11-08T16:47:53Z
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
- ASSUMPTION (2026-11-08 17:14+0100 by dell26/agent-1): Search type filter is a type: prefix in the Ctrl+K query (type:note,task text), not chips: the palette input lives in shell/ (outside this task's touches). Types by id, UI label or alias (mail, journal), plural s ok; an unknown type: word stays search text.
- ASSUMPTION (2026-11-08 17:14+0100 by dell26/agent-1): Capture Inbox/Read later choice shows only when the trimmed text is exactly one http(s) URL and no file is attached (a bookmark has no files); notes become the bookmark description; the choice persists for Shift+Enter captures. POST /bookmarks has no client id, so a retry after a lost answer may create a second bookmark (same as Android).
- ASSUMPTION (2026-11-08 17:14+0100 by dell26/agent-1): Android app row in Settings > General: GET /download/android/version silently; 404 -> 'No Android app is available on this server' with no link; other errors -> in-place message with Try again. Link is a plain <a download href=/download/android> (session cookie auth).
- ASSUMPTION (2026-11-08 17:14+0100 by dell26/agent-1): i18n:extract pulled unrelated untranslated units (calendar.invite.* etc.) into messages.xlf; only this task's 15 units were inserted into messages.xlf/messages.de.xlf.
