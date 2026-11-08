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
- [x] Ctrl+K search can filter by type (chips or `type:` prefix)
  - verified (2026-11-08 17:31+0100 by dell26/agent-1): npm --prefix web run test:ci --include search/** (search-query.spec 'type:' prefix parsing incl. German labels, search-palette.provider.spec sends types=TASK&types=NOTE for 'type:note,Tasks passport') -> 69 passed; full npm --prefix web run check -> 4520 passed
- [x] Capture of a single URL offers "Inbox" or "Read later" (bookmark)
  - verified (2026-11-08 17:31+0100 by dell26/agent-1): capture-dialog.spec 'read later' describe: single URL shows Inbox/Read later radiogroup, Read later POSTs /api/v1/bookmarks {url, readLater:true, description}; not offered for non-URL or with attachments; capture.service.spec readLater -> all pass in npm --prefix web run check (4520 passed)
- [x] Settings shows the Android app version with a download link (`/download/android`)
  - verified (2026-11-08 17:31+0100 by dell26/agent-1): android-app-download.spec + general-settings.spec: GET /download/android/version shows 'Version 1.4.0', size/date and <a download href=/download/android>; 404 -> no link; errors -> Try again -> pass in npm --prefix web run check. Dev proxy now forwards /download (not exercised against a live server)
- [x] New UI strings are in `web/src/locale` (English + German)
  - verified (2026-11-08 17:31+0100 by dell26/agent-1): 15 new units added to web/src/locale/messages.xlf + messages.de.xlf (German targets); messages-de.spec.ts (incl. test-writer check of the task's units and placeholders) passes in npm --prefix web run check; ng build ok
- [x] Unit tests cover the new behavior; `cd web && npm run check` and `sh scripts/check.sh` pass
  - verified (2026-11-08 17:31+0100 by dell26/agent-1): npm --prefix web run check -> exit 0 (lint clean, 193 files/4520 tests, build ok); sh scripts/check.sh -> 'all checks passed' exit 0 on f3e36cde7; task-scoped coverage 85/85 changed lines (reviews/web-small-gaps/tests.md)

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 17:14+0100 by dell26/agent-1): Search type filter is a type: prefix in the Ctrl+K query (type:note,task text), not chips: the palette input lives in shell/ (outside this task's touches). Types by id, UI label or alias (mail, journal), plural s ok; an unknown type: word stays search text.
- ASSUMPTION (2026-11-08 17:14+0100 by dell26/agent-1): Capture Inbox/Read later choice shows only when the trimmed text is exactly one http(s) URL and no file is attached (a bookmark has no files); notes become the bookmark description; the choice persists for Shift+Enter captures. POST /bookmarks has no client id, so a retry after a lost answer may create a second bookmark (same as Android).
- ASSUMPTION (2026-11-08 17:14+0100 by dell26/agent-1): Android app row in Settings > General: GET /download/android/version silently; 404 -> 'No Android app is available on this server' with no link; other errors -> in-place message with Try again. Link is a plain <a download href=/download/android> (session cookie auth).
- ASSUMPTION (2026-11-08 17:14+0100 by dell26/agent-1): i18n:extract pulled unrelated untranslated units (calendar.invite.* etc.) into messages.xlf; only this task's 15 units were inserted into messages.xlf/messages.de.xlf.
