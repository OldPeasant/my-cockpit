---
title: Drafts and sending
status: in_progress
owner: dell26/agent-2
deps: [mail-actions-api,files-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/compose,server/src/test/kotlin/ch/sonensei/cockpit/mail/compose]
prio: 1
lease_until: 2026-10-21T09:02:49Z
---
## Goal
Compose, reply, forward (SPEC.md §7.3).

## Acceptance criteria
- [ ] Drafts API (create/update/delete) saving to the Drafts folder; send via SMTP (XOAUTH2 where needed) with HTML + plain alternative, identity, attachments from upload or Files; append to Sent unless provider does (Gmail)
- [ ] Reply/reply-all/forward helpers (quoting, References headers, attachments on forward); recent correspondents endpoint for autocomplete
- [ ] `external` GreenMail tests: sent message received with attachment, appended to Sent, draft replaced on update
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-21 10:47+0200 by dell26/agent-2): Drafts live only on the IMAP Drafts folder (no local draft table): each save APPENDs a new version and expunges the old one; all versions share one Message-ID so the header sync re-attaches the cached row and the draft id stays stable. ETag of a draft = IMAP UID of its current version.
