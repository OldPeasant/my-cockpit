---
title: Drafts and sending
status: in_progress
owner: dell26/agent-2
deps: [mail-actions-api,files-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/compose,server/src/test/kotlin/ch/sonensei/cockpit/mail/compose]
prio: 1
lease_until: 2026-10-21T08:57:45Z
---
## Goal
Compose, reply, forward (SPEC.md §7.3).

## Acceptance criteria
- [ ] Drafts API (create/update/delete) saving to the Drafts folder; send via SMTP (XOAUTH2 where needed) with HTML + plain alternative, identity, attachments from upload or Files; append to Sent unless provider does (Gmail)
- [ ] Reply/reply-all/forward helpers (quoting, References headers, attachments on forward); recent correspondents endpoint for autocomplete
- [ ] `external` GreenMail tests: sent message received with attachment, appended to Sent, draft replaced on update
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
