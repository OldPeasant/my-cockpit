---
title: Mail search
status: in_progress
owner: dell26/agent-3
deps: [mail-read-api,p0-search-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/search,server/src/test/kotlin/ch/sonensei/cockpit/mail/search]
prio: 2
lease_until: 2026-10-21T22:55:04Z
---
## Goal
Find mail (SPEC.md §7.3).

## Acceptance criteria
- [ ] Cached headers and bodies indexed in search_document; `GET /api/v1/mail/search?q=&server=true` additionally runs IMAP SEARCH on the server
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.search.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-22 00:14+0200 by dell26/agent-3): Mail search hooks live outside the task's touches (small calls in mail/sync MailHeaderStore, MailHeaderSyncService.searchServer, RemoteFolder.search, mail/api MailContentLoader/MailActionService, core SearchIndexer optional recency): indexing has to happen where messages and bodies are cached.
- ASSUMPTION (2026-10-22 00:14+0200 by dell26/agent-3): Mail search document: title = subject, body = sender/recipient names + addresses (also split into words) + text/plain part (else text of HTML, else snippet); recency = arrival (INTERNALDATE). A body evicted from the body cache stays in the index so read mail stays findable.
- ASSUMPTION (2026-10-22 00:14+0200 by dell26/agent-3): GET /api/v1/mail/search: scope folderId / accountId (all folders of the account) / none (all folders of all accounts), newest first, limit 1..200 (default 50), hasMore flag instead of a cursor. server=true runs IMAP UID SEARCH TEXT per word in every selectable folder of the scope, caches headers of found uncached messages (most recent limit per folder) and merges them; unreachable accounts are reported in server.failures instead of a 502.
- ASSUMPTION (2026-10-22 00:14+0200 by dell26/agent-3): A scheduled reconciler (cockpit.mail.search.reconcile.*, hourly, off in tests) indexes cached messages without document and removes documents of gone/detached messages (e.g. account deleted via FK cascade).
