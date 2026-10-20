---
title: Mail read API
status: in_progress
owner: dell26/agent-5
deps: [mail-header-sync,mail-sanitizer]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/api,server/src/test/kotlin/ch/sonensei/cockpit/mail/api]
prio: 1
lease_until: 2026-10-20T23:12:10Z
releases: 1
extended: 
---
## Goal
List and read messages (SPEC.md §7.4).

## Acceptance criteria
- [ ] `GET /api/v1/mail/messages` (unified or account/folder, threaded or flat, cursor), `GET …/{id}` with sanitized HTML / text, attachments metadata
- [ ] Attachment download streaming; remote image proxy with size/time limits and SSRF protection; body cache LRU with size bound
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.api.*'` passes (Postgres via Testcontainers where persistence is involved) plus `external` GreenMail tests
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-20 22:37+0200 by dell26/agent-1): GET /mail/messages: accountId alone lists that account's Inbox (role INBOX); neither param = unified Inbox of all accounts; folderId = that folder. The q= search parameter is left to the mail-search task.
- ASSUMPTION (2026-10-20 22:37+0200 by dell26/agent-1): Threaded list: one item per (account, threadKey) = newest message in the listed scope; threadSize/threadUnread are counted within that scope only. GET /messages/{id}/thread returns the whole conversation across the account's folders, deduplicated by Message-ID.
- ASSUMPTION (2026-10-20 22:37+0200 by dell26/agent-1): Reading a message never sets \Seen (EXAMINE + BODY.PEEK); clients mark read via the actions API (mail-actions-api).
- ASSUMPTION (2026-10-20 22:37+0200 by dell26/agent-1): Attachment metadata is cached in a new table mail_body_attachments (FK to mail_body, evicted with it; migration in db/migration/mail, outside the task's touches) so reopening a cached message needs no IMAP round trip.
- ASSUMPTION (2026-10-20 22:37+0200 by dell26/agent-1): Image proxy GET /api/v1/mail/image-proxy?url= is unsigned (any authenticated user may proxy any public URL); only public unicast addresses, ports 80/443, raster image types (no SVG), 5 MB / 10 s total / 3 redirects by default. Implemented with a small socket-based HTTP/1.1 client pinned to the checked IP, because the JDK HttpClient cannot be pinned (DNS rebinding).
- ASSUMPTION (2026-10-20 22:37+0200 by dell26/agent-1): Attachment downloads are always Content-Disposition: attachment with application/octet-stream, except ?inline=true for raster image types; all mail content responses carry nosniff and CSP 'default-src none; sandbox'.
- release by dell26/agent-1: handed back by dell26/agent-1: pushes had failed (To https://github.com/OldPeasant/my-cockpit.git | ! [remote rejected] HEAD -> wip/mail-read-api (missing necessary obje…); continue from wip/mail-read-api
- ASSUMPTION (2026-10-21 00:03+0200 by dell26/agent-1): Older mail on demand (SPEC.md §7.2, MailHeaderSyncService.loadOlder) is not part of this task: GET /mail/messages stays a pure header-cache read (no IMAP round trip in a list call); the API for loading older mail is the follow-up task mail-load-older (review CR-1).
- release by dell26/agent-1: Implementation complete and committed (list/read/thread/attachments/image proxy/body cache, OpenAPI regenerated, 2 review+fix rounds; sh scripts/check.sh passes at b1db3c8b). Left, finalization only: (1) run test-writer on final code -> reviews/mail-read-api/tests.md is MISSING (needs task-scoped coverage), (2) re-run code-reviewer on final code (fixes in f05c7d51 + tests in b1db3c8b made review.md stale), fixer only if new findings, (3) run ./gradlew test --tests 'ch.sonensei.cockpit.mail.api.*' incl. external GreenMail + OpenApiContractTest without flag and record tasks.py verify 1-4, (4) write quality.json, commit, tasks.py done. Follow-up mail-load-older already exists for review CR-1.
