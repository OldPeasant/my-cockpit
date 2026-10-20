---
title: IMAP/SMTP connectivity and folder sync
status: in_progress
owner: dell26/agent-2
deps: [mail-model,oauth-connections]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/imap,server/src/test/kotlin/ch/sonensei/cockpit/mail/imap]
prio: 1
lease_until: 2026-10-20T15:35:59Z
---
## Goal
Connect accounts (SPEC.md §7.1).

## Acceptance criteria
- [ ] Account CRUD API (`/api/v1/mail/accounts`, secrets write-only) with 'test connection'; Angus Mail sessions with TLS/STARTTLS, XOAUTH2 for Google/MS
- [ ] Connection pool per account with limits; folder list with SPECIAL-USE role detection and manual override
- [ ] `external` tests against GreenMail (`./gradlew integrationTest --tests '*Imap*'`); unit tests for role detection
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
