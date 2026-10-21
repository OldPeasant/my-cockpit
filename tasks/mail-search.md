---
title: Mail search
status: in_progress
owner: dell26/agent-1
deps: [mail-read-api,p0-search-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/search,server/src/test/kotlin/ch/sonensei/cockpit/mail/search]
prio: 2
lease_until: 2026-10-22T00:10:45Z
releases: 1
extended: 
---
## Goal
Find mail (SPEC.md §7.3).

## Acceptance criteria
- [x] Cached headers and bodies indexed in search_document; `GET /api/v1/mail/search?q=&server=true` additionally runs IMAP SEARCH on the server
  - verified (2026-10-22 01:48+0200 by dell26/agent-1): server/gradlew -p server test --tests 'ch.sonensei.cockpit.mail.*' (MailSearchWebTests on Postgres Testcontainers: header indexing on sync, body indexing on load, server=true merges IMAP SEARCH hits, failures reported) -> 1146 tests incl. OpenApiContractTest, 0 failures; server/gradlew -p server integrationTest --tests '*MailSearchGreenMailTests' (real IMAP UID SEARCH against GreenMail, UTF-8 word, uncached old message found only with server=true) -> 1 test, 0 failures
- [x] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.search.*'` passes (Postgres via Testcontainers where persistence is involved)
  - verified (2026-10-22 01:48+0200 by dell26/agent-1): server/gradlew -p server test --tests 'ch.sonensei.cockpit.mail.search.*' at fc43c4196 (Postgres via Testcontainers) -> BUILD SUCCESSFUL, 36 tests, 0 failures/errors/skipped
- [x] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag
  - verified (2026-10-22 01:29+0200 by dell26/agent-1): server/gradlew -p server test --tests '*OpenApiContractTest' (no UPDATE flag; test asserts api/openapi.yaml equals the generated spec) -> 1 test, 0 failures; api/openapi.yaml + web client regenerated in commit f70bc842c

## Notes
- ASSUMPTION (2026-10-22 00:14+0200 by dell26/agent-3): Mail search hooks live outside the task's touches (small calls in mail/sync MailHeaderStore, MailHeaderSyncService.searchServer, RemoteFolder.search, mail/api MailContentLoader/MailActionService, core SearchIndexer optional recency): indexing has to happen where messages and bodies are cached.
- ASSUMPTION (2026-10-22 00:14+0200 by dell26/agent-3): Mail search document: title = subject, body = sender/recipient names + addresses (also split into words) + text/plain part (else text of HTML, else snippet); recency = arrival (INTERNALDATE). A body evicted from the body cache stays in the index so read mail stays findable.
- ASSUMPTION (2026-10-22 00:14+0200 by dell26/agent-3): GET /api/v1/mail/search: scope folderId / accountId (all folders of the account) / none (all folders of all accounts), newest first, limit 1..200 (default 50), hasMore flag instead of a cursor. server=true runs IMAP UID SEARCH TEXT per word in every selectable folder of the scope, caches headers of found uncached messages (most recent limit per folder) and merges them; unreachable accounts are reported in server.failures instead of a 502.
- ASSUMPTION (2026-10-22 00:14+0200 by dell26/agent-3): A scheduled reconciler (cockpit.mail.search.reconcile.*, hourly, off in tests) indexes cached messages without document and removes documents of gone/detached messages (e.g. account deleted via FK cascade).
- ASSUMPTION (2026-10-22 00:56+0200 by dell26/agent-3): Folder list sync (mail/imap MailFolderService) calls MailSearchIndex.unindexFolder before deleting a vanished folder, outside the declared touches, so stale mail leaves search immediately rather than at the hourly reconcile
- release by dell26/agent-3: Implementation, API + openapi.yaml/web client regen, fixes for review round 1 (CR-1..3) are committed; code review round 2 (reviews/mail-search/review.md) is clean (0 findings). test-writer round 2 added tests (committed in dddf48b2: MailSearchWordsTests, MailSearchWebTests, MailActionServiceTests) but did NOT write reviews/mail-search/tests.md. ./gradlew test --tests 'ch.sonensei.cockpit.mail.*' -> 1144 tests, 0 failures. scripts/check.sh: one run failed (likely a flaky/concurrent Gradle run, no failing test XML found), rerun passed; it takes ~4 min. Left: rerun test-writer to produce tests.md with coverage numbers; if it changes only tests, rerun code-reviewer on the final code; run integrationTest --tests '*MailSearchGreenMailTests' and OpenApiContractTest; tasks.py verify 1-3; write quality.json (fixer round 1 = fixes.md); done.
