---
title: Mail actions
status: in_progress
owner: dell26/agent-5
deps: [mail-read-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/api,server/src/test/kotlin/ch/sonensei/cockpit/mail/api]
prio: 1
lease_until: 2026-10-21T01:21:33Z
---
## Goal
Triage actions (SPEC.md §7.3).

## Acceptance criteria
- [ ] `POST /api/v1/mail/messages/actions {ids, action, targetFolderId?}`: READ, UNREAD, FLAG, UNFLAG, MOVE, ARCHIVE, DELETE (to Trash; permanent in Trash), SPAM; applied on IMAP and cache consistently; partial failures reported per id
- [ ] `external` GreenMail tests reflect every action
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-21 03:02+0200 by dell26/agent-5): Actions response is 200 with one result per distinct id in request order ({id, ok, errorCode, detail}), also when all fail; no 207 Multi-Status
