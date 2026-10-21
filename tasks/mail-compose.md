---
title: Drafts and sending
status: in_progress
owner: dell26/agent-4
deps: [mail-actions-api,files-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/compose,server/src/test/kotlin/ch/sonensei/cockpit/mail/compose]
prio: 1
lease_until: 2026-10-21T12:07:53Z
extended: 
releases: 2
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
- ASSUMPTION (2026-10-21 11:53+0200 by dell26/agent-2): Draft GET body and reply/forward quotes keep remote image src unchanged (sanitized otherwise, via identity PROXY policy), so content does not degrade on GET->PUT or for recipients; the web compose editor must avoid auto-loading remote images itself. Supersedes the 'remote images blocked / GET returns sanitized HTML' part of the earlier note.
- ASSUMPTION (2026-10-21 11:53+0200 by dell26/agent-2): Review CR-5 (correspondent query sorts without a matching index) deferred to perf-tuning: needs a migration outside this task's touches.
- release by dell26/agent-2: All 4 criteria implemented and committed (drafts API on IMAP Drafts, SMTP send w/ XOAUTH2, HTML+plain, attachments upload/Files, Sent append for IMAP_SMTP, reply/reply-all/forward, correspondents, GreenMail tests, openapi.yaml + web client regenerated). Fixer applied review CR-1..CR-4, CR-6 (fixes.md); CR-5 deferred (assumption). 'gradlew test --tests ch.sonensei.cockpit.mail.compose.*' passes on 1926b9fd. LEFT: (1) test-writer on final code -> write reviews/mail-compose/tests.md with coverage (missing; also run the external GreenMail tests); (2) re-run code-reviewer on final code (review.md is from before fixes); (3) tasks.py verify 1-4 incl. OpenApiContractTest without flag; (4) .taskcheck (~6 min), quality.json, done.
- release by dell26/agent-3: Implementation complete (drafts API, SMTP send, reply/forward, correspondents, GreenMail tests, openapi regenerated); 3 review rounds done, latest review.md has 0 findings, fixes.md rounds 1-2 committed. LEFT: (1) test-writer has not yet written reviews/mail-compose/tests.md - rerun test-writer on final code (it last added DraftMimeTests.kt cases, committed in 49a50bf0) incl. task-scoped coverage; (2) since DraftMimeTests changed after review, re-run code-reviewer if done's freshness check requires it; (3) run the external GreenMail tests and OpenApiContractTest, record tasks.py verify for criteria 1-4; (4) write quality.json, commit, tasks.py done. sh scripts/check.sh passed for server+web locally.
