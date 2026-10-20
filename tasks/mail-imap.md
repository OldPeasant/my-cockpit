---
title: IMAP/SMTP connectivity and folder sync
status: in_progress
owner: dell26/agent-2
deps: [mail-model,oauth-connections]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/imap,server/src/test/kotlin/ch/sonensei/cockpit/mail/imap]
prio: 1
lease_until: 2026-10-20T15:46:03Z
---
## Goal
Connect accounts (SPEC.md §7.1).

## Acceptance criteria
- [ ] Account CRUD API (`/api/v1/mail/accounts`, secrets write-only) with 'test connection'; Angus Mail sessions with TLS/STARTTLS, XOAUTH2 for Google/MS
- [ ] Connection pool per account with limits; folder list with SPECIAL-USE role detection and manual override
- [ ] `external` tests against GreenMail (`./gradlew integrationTest --tests '*Imap*'`); unit tests for role detection
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-20 17:17+0200 by dell26/agent-2): Account API lives in mail/imap (task touches) as /api/v1/mail/accounts plus POST /{id}/test, GET /{id}/folders, POST /{id}/folders/sync, PATCH /{id}/folders/{folderId}; 'test connection' tests the stored account only (no test of unsaved settings), failures are 200 with per-protocol errorCode
- ASSUMPTION (2026-10-20 17:17+0200 by dell26/agent-2): Sending identities (mail_identity) are not managed by this API; left to compose/settings tasks. Account creation does not contact the server; folder sync is an explicit call (header-sync/idle tasks will call MailFolderService.sync)
- ASSUMPTION (2026-10-20 17:17+0200 by dell26/agent-2): Provider defaults: Gmail imap.gmail.com:993 TLS / smtp.gmail.com:465 TLS; Microsoft outlook.office365.com:993 TLS / smtp.office365.com:587 STARTTLS; OAuth username defaults to the connection email
- ASSUMPTION (2026-10-20 17:18+0200 by dell26/agent-2): Role detection: SPECIAL-USE attributes from plain LIST (Angus has no LIST RETURN (SPECIAL-USE)), All counts as Archive when there is no Archive attribute, then English/German well-known names; PATCH folder {automatic:true} only clears the override, the role is recomputed on the next sync
- ASSUMPTION (2026-10-20 17:18+0200 by dell26/agent-2): Pool defaults (cockpit.mail.pool.*): 3 connections per account, 30s borrow timeout, 5m idle timeout; cockpit.mail.ssl-trust (empty by default) exists only for test servers with self-signed certs; host names are always checked
