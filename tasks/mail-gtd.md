---
title: Mail to GTD integration
status: in_progress
owner: dell26/agent-1
deps: [mail-actions-api,gtd-capture-api,notes-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd,server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd]
prio: 1
lease_until: 2026-10-21T05:37:41Z
extended: 
releases: 1
---
## Goal
Turn mail into tasks, waiting-fors, notes (SPEC.md §7.3).

## Acceptance criteria
- [ ] `POST /api/v1/mail/messages/{id}/to-task|to-waiting|to-note` create linked items (waiting-for uses the recipient of a sent mail)
- [ ] Links survive moves (Message-ID re-resolution, test)
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-21 05:26+0200 by dell26/agent-3): to-waiting on a received (not sent) mail waits for its sender instead of being rejected; 'sent' = in the Sent-role folder or From matches the account address or an identity. waitingForText holds names/addresses (no contact lookup: no contacts service yet)
- ASSUMPTION (2026-10-21 05:26+0200 by dell26/agent-3): to-note body = From/To/Cc/Date header lines + the mail's plain text (HTML part converted to text via jsoup when there is no plain part), truncated to the note limit; the mail is linked with a MANUAL link (not an inline [[email:id]]), same as tasks
- ASSUMPTION (2026-10-21 05:27+0200 by dell26/agent-3): Endpoints take no request body and are not idempotent (a repeated POST creates another item); the link points at the message's current copy (locator) when called with a stale id
- ASSUMPTION (2026-10-21 06:04+0200 by dell26/agent-3): Review finding CR-3 (MailConnectionException handler duplicated across mail controllers) deferred: consolidating it touches MailMessageController/MailAccountController outside this task's touches; left for a cleanup task
- extended by dell26/agent-3: Implementation, OpenAPI regen, review+fixes (CR-1/CR-2; CR-3 deferred via assume) and tests are committed; scripts/check.sh passes. Left: re-run code-reviewer (and test-writer if needed) on final code since 05bf8a9f added tests after the re-review; run ./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.*' (Testcontainers) and OpenApiContractTest; tasks.py verify criteria 1-4; write+commit reviews/mail-gtd/quality.json; tasks.py done.
- release by dell26/agent-3: Done: endpoints to-task/to-waiting/to-note with MANUAL links, Message-ID re-resolution on moves, OpenAPI+web client regenerated, review rounds 1-3 + fixes (reviews/mail-gtd/{review,tests,fixes}.md). Last commit 33b0bc4d fixes final-review CR-1 (P3: display names in note header not neutralized) by running neutralizeInlineLinks on the whole note body, plus new test 'inline link tokens in display names and the subject do not link the note to other items'. THAT TEST FAILS: 500, flexmark AssertionError 'length:174 != computeLength(): 176' (SegmentBuilderBase.length, -ea in tests) when NoteService parses the neutralized header (e.g. '[\\[task:uuid]] <mallory@example.test>' / triple-bracket cc name). Left: 1) fix that (likely neutralize names differently, e.g. escape both brackets or neutralize only in full(address), avoid producing input that trips flexmark), keep test green; 2) re-run test-writer + code-reviewer on final code (fixer report for CR-1 needed); 3) ./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.*' and OpenApiContractTest; 4) tasks.py verify 1-4; 5) write+commit reviews/mail-gtd/quality.json (CR-2 deferred via assume); 6) scripts/check.sh (server suite took ~10 min on loaded machine) and done.
