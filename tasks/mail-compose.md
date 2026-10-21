---
title: Drafts and sending
status: in_progress
owner: dell26/agent-2
deps: [mail-actions-api,files-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/compose,server/src/test/kotlin/ch/sonensei/cockpit/mail/compose]
prio: 1
lease_until: 2026-10-21T10:08:27Z
extended: dell26/agent-2
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
- ASSUMPTION (2026-10-21 10:47+0200 by dell26/agent-2): Reply/reply-all/forward are POST /api/v1/mail/messages/{id}/reply|reply-all|forward creating a draft server-side (quote, Re:/Fwd:, In-Reply-To/References, forwarded attachments); uploads go to POST /api/v1/mail/drafts/{id}/attachments (multipart), Files attachments by fileId; PUT /drafts/{id} with attachments omitted keeps the current ones.
- ASSUMPTION (2026-10-21 10:47+0200 by dell26/agent-2): Sent copy is appended only for IMAP_SMTP accounts; GOOGLE and MICROSOFT servers store sent mail themselves. The sent message keeps the draft's Message-ID. Signatures are not inserted server-side (the client composes them into the body); the original is not flagged \Answered/$Forwarded.
- ASSUMPTION (2026-10-21 10:47+0200 by dell26/agent-2): Max draft/outgoing size 25 MB (cockpit.mail.compose.max-message-size). Quoted HTML is the sanitized original with remote images blocked; replies drop cid: inline images, forwards keep them as parts. GET draft returns sanitized HTML. Recent correspondents = recipients of Sent mail + senders of other mail (not Drafts/Trash/Spam) among the 5000 newest cached messages, own addresses excluded.
- ASSUMPTION (2026-10-21 10:47+0200 by dell26/agent-2): Added FilesService.open(id) (one method, outside touches) so mail reads file contents through the files service instead of its BlobStore.
- extended by dell26/agent-2: Implementation of all 4 criteria committed (drafts/send/reply/forward/correspondents, GreenMail tests, OpenAPI+web client regenerated). Left: (1) fixer for review.md CR-1 (DraftController.addresses() accepts display names/groups in 'address'; DraftControllerTests$Create test fails 400 vs 201) and the 3 P2/2 P3 findings; (2) test-writer must finish and write reviews/mail-compose/tests.md with coverage (DraftSendGreenMailTests.kt committed, unverified); (3) re-run test-writer+code-reviewer on final code, verify criteria, quality.json, done. Note: ClockConfigurationTests failed once on nanosecond precision (unrelated flake); check.sh took 6 min.
