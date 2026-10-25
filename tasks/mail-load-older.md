---
title: Load older mail on demand (API for MailHeaderSyncService.loadOlder)
status: in_progress
owner: r1963macos/agent-1
deps: [mail-read-api,mail-actions-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/api,server/src/test/kotlin/ch/sonensei/cockpit/mail/api,api/openapi.yaml,web/src/app/api/generated]
prio: 2
lease_until: 2026-10-25T22:39:55Z
releases: 1
extended: 
---
## Goal
SPEC.md §7.2: headers are cached for the sync window, "older loaded on demand when scrolling". `MailHeaderSyncService.loadOlder(folderId, limit)` exists (mail-header-sync) but no API calls it; `GET /api/v1/mail/messages` (mail-read-api) reads only the cache and ends with `nextCursor: null` at the end of the window.

## Acceptance criteria
- [ ] `POST /api/v1/mail/folders/{id}/load-older` (or an equivalent, documented in an assumption) calls `loadOlder` and returns `{loaded, hasMore}`; Problem Details for unknown folder (404) and unreachable server (502/503)
- [ ] Folder-scoped list response tells clients that older mail can be loaded (e.g. `hasOlder` when the cache is exhausted and the server has more)
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.api.*'` passes plus an `external` GreenMail test: load older, then the list continues past the window
- [ ] `api/openapi.yaml` regenerated and `OpenApiContractTest` passes without the flag; web client regenerated

## Notes
- ASSUMPTION (2026-10-25 23:19+0100 by r1963macos/agent-1): Endpoint is POST /api/v1/mail/folders/{id}/load-older?limit= (1..200, default 100 = MailHeaderSyncService.DEFAULT_OLDER_LIMIT), no body; RemoteFolderNotFoundException maps to 404 like other reads of a folder gone from the server
- ASSUMPTION (2026-10-25 23:19+0100 by r1963macos/agent-1): hasOlder (always present, true only on the last page of a folderId list) is computed without contacting IMAP: true for a selectable folder until a load-older returned hasMore=false for its current UIDVALIDITY. Completeness is kept in memory (MailOlderService), so after a restart, or for a folder fully inside the sync window, clients see hasOlder=true once and the next load returns loaded=0/hasMore=false. Persisting it would need a mail_folder column (migration outside this task's touches). Unified and account-Inbox lists always report hasOlder=false.
- release by r1963macos/agent-1: Implemented and committed (587736e5): MailOlderService (mail/api), POST /api/v1/mail/folders/{id}/load-older?limit= on MailMessageController -> {loaded, hasMore} (404 unknown/gone folder, 502/503 via existing MailConnectionException handler), hasOlder on MailMessageListResponse; unit tests MailLoadOlderControllerTests + MailOlderServiceTests pass (9 tests), compileTestKotlin + ktlintCheck clean; external MailLoadOlderGreenMailTests written but NOT run. BLOCKER: Docker daemon not running on r1963macos (Testcontainers Postgres), so left: UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest', cd web && npm run api:gen, run mail.api tests + integrationTest for MailLoadOlderGreenMailTests, test-writer/code-reviewer/quality.json, verify criteria, done. See assumptions.
