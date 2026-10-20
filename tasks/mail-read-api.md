---
title: Mail read API
status: in_progress
owner: dell26/agent-1
deps: [mail-header-sync,mail-sanitizer]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/api,server/src/test/kotlin/ch/sonensei/cockpit/mail/api]
prio: 1
lease_until: 2026-10-20T20:56:09Z
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
