---
title: Load older mail on demand (API for MailHeaderSyncService.loadOlder)
status: todo
owner:
deps: [mail-read-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/api,server/src/test/kotlin/ch/sonensei/cockpit/mail/api,api/openapi.yaml,web/src/app/api/generated]
prio: 2
---
## Goal
SPEC.md §7.2: headers are cached for the sync window, "older loaded on demand when scrolling". `MailHeaderSyncService.loadOlder(folderId, limit)` exists (mail-header-sync) but no API calls it; `GET /api/v1/mail/messages` (mail-read-api) reads only the cache and ends with `nextCursor: null` at the end of the window.

## Acceptance criteria
- [ ] `POST /api/v1/mail/folders/{id}/load-older` (or an equivalent, documented in an assumption) calls `loadOlder` and returns `{loaded, hasMore}`; Problem Details for unknown folder (404) and unreachable server (502/503)
- [ ] Folder-scoped list response tells clients that older mail can be loaded (e.g. `hasOlder` when the cache is exhausted and the server has more)
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.api.*'` passes plus an `external` GreenMail test: load older, then the list continues past the window
- [ ] `api/openapi.yaml` regenerated and `OpenApiContractTest` passes without the flag; web client regenerated

## Notes
