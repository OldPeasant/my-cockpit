# Code Review

Date: 2026-10-22

Task: p5-smoke - Phase 5 demo script

Scope: committed changes of task p5-smoke, 769a79ea93bf64c0eb175f7323aed4c3d9dc2200..7542b1ed887f2b49bc36a513d986c8cb8930565c (web/e2e/phase5/demo.spec.ts, web/e2e/phase5/smtp.ts, deploy/docker-compose.e2e.yml, web/e2e/support/run-e2e.mjs, web/e2e/README.md). An uncommitted change to web/e2e/phase5/demo.spec.ts was in the working tree at review time (probably the test-writer's). It was not reviewed. Line numbers refer to the committed HEAD.

Requirements:
- tasks/p5-smoke.md (acceptance criterion and the three recorded assumptions)
- SPEC.md §13.2 Phase 5, steps 1-6
- AGENTS.md, docs/ARCHITECTURE.md (e2e section, environment table), docs/QUALITY.md
- web/e2e/README.md and the sibling specs (web/e2e/phase4, web/e2e/mail-*)
- Server behaviour the spec relies on: mail/compose/DraftService.kt (save, send), mail/sync/MailHeaderSyncService.kt, mail/testsupport/MailTestSupportController.kt, core/testsupport/TestSupportConfiguration.kt (CSRF-free /api/test), web/src/app/features/mail/list/mail-list-store.ts (SSE-driven refresh), web/src/app/features/mail/compose/mail-compose-page.ts (autosave, attach)

Git range:
- Branch: main
- HEAD: 7542b1ed887f2b49bc36a513d986c8cb8930565c
- Base/start: 769a79ea93bf64c0eb175f7323aed4c3d9dc2200
- Commits reviewed: 2 (832c75142, 7542b1ed8)

Summary: 0 P0, 0 P1, 1 P2, 2 P3

What I checked:
- Step 1: the account is added through the settings form with GreenMail as the server container sees it. The spec checks the folder tree (Junk shown by role as Spam) and that the unified inbox lists every seeded message.
- Step 2: the mail is delivered by real SMTP. The list refreshes only on SSE change events (mail-list-store.ts has no polling), and a window flag shows the page was not reloaded. So the spec really exercises IDLE plus SSE.
- Step 3: each toolbar action is checked against GreenMail with IMAP SEARCH (SEEN/UNSEEN/FLAGGED, and the message is in the target folder and gone from INBOX). The final INBOX ordering relies on the UID-ascending insert order of the header sync as a tie-break, which is deterministic.
- Step 4: the spec checks the sandbox attribute, that neither the script nor the onerror handler ran, that no dialog opened, that images stay blocked until "Load images", and that they then load through the proxy (stubbed by a route).
- Step 5: saving a draft, attaching a file and sending all run synchronously on the server (DraftService.store/send). By the time `.attachments` shows the file, GreenMail's Drafts already holds that version. Sent is appended and the draft is removed before the send response, so the immediate IMAP checks after "Message sent" are race-free. The peer mailbox check covers "GreenMail received it".
- Step 6: the spec checks both directions of the task link and the "for Max Muster" text in Waiting For.
- Kept-stack reruns: every address, subject, file and folder name carries the run id. GreenMail creates new mailboxes per address, and the stack is reset before and after.
- smtp.ts: multi-line replies are handled (the regex finds the final `NNN ` line and drops what came before it). Dot-stuffing is applied, every reply has a 10 s timeout, and the socket is destroyed in `finally`. A socket closed without data surfaces as a timeout after 10 s, not immediately, which is acceptable for a test helper.
- /api/test/mail/sync without an XSRF header is fine: the /api/test chain disables CSRF (TestSupportConfiguration.kt:40).
- The compose and run-e2e changes follow the existing E2E_IMAP_PORT pattern: a free port by default, passed to Playwright, bound to 127.0.0.1 only.
- Criterion evidence: `python tasks.py verify p5-smoke` shows criterion 1 still unverified. The parent reports a phase5 run (6 passed) and an earlier full phase-demos run. The recorded evidence must name the full `--project=phase-demos` run, phases 0-5, on the final code, since the compose/run-e2e change affects every phase spec.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Step 6 forces a mail sync the server already does after Send; the comment and the recorded assumption describe the product wrongly**
    - File: `web/e2e/phase5/demo.spec.ts:299`
    - Category: Tests
    - Problem: The comment says, and the task assumption records, that "the cockpit reads the copy in Sent with its next sync of the folder (every 5 minutes)", so the spec calls `POST /api/test/mail/sync` (line 301) before it opens Sent. But `DraftService.send` (server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt:276-286) appends the copy to Sent and then runs `resync(sent)` (`sync.syncFolder`) before it returns. So the copy is in the cockpit's cache as soon as "Message sent" shows. The hook is redundant, and it covers up the product path: if the post-send Sent resync broke, step 5 (which checks Sent only on GreenMail, line 274) and step 6 would both still pass.
    - Why it matters: The demo should show what a user sees. Here it masks a regression path, and the recorded assumption tells humans the cockpit needs up to 5 minutes to show sent mail, which is false.
    - Suggested fix: Remove the `syncMail` call in step 6 (and the helper if unused), or keep it only after first asserting that the sent reply is listed in the cockpit's Sent folder. Correct the comment. Ask the parent to replace or annotate the step-6 assumption in tasks/p5-smoke.md.

## P3 Low

- [ ] **CR-2 | E2E_SMTP_PORT missing from docs/ARCHITECTURE.md; its manual compose example now binds the fixed default 18025**
    - File: `docs/ARCHITECTURE.md:106`
    - Category: Integration
    - Problem: The environment table lists `E2E_IMAP_PORT` but not the new `E2E_SMTP_PORT`. The manual stack example (docs/ARCHITECTURE.md:178) pins the IMAP port to keep it apart from other stacks, but now silently publishes GreenMail SMTP on the compose default `127.0.0.1:18025` (deploy/docker-compose.e2e.yml:60). A second manually started stack, or anything else on 18025, then makes `up` fail.
    - Why it matters: The docs on how to run the e2e stack no longer match its configuration.
    - Suggested fix: Add `E2E_SMTP_PORT` to the table row and to the example (for example `E2E_SMTP_PORT=18093`). This is a small edit to a shared doc, which AGENTS.md allows.

- [ ] **CR-3 | Step 5 "Draft saved" check is already satisfied before any edit, and the saved draft's text is not checked**
    - File: `web/e2e/phase5/demo.spec.ts:242`
    - Category: Tests
    - Problem: The compose page shows "Draft saved HH:MM" as soon as the reply draft is created (mail-compose-page.ts `apply` sets `{kind:'saved'}`), so this assertion can pass on the status from before typing. The Drafts check (line 252) only asserts the attachment file name, not the typed `Here are the numbers ...` text, so it does not show that the edits were autosaved into Drafts.
    - Why it matters: The SPEC step says "the draft autosaves to Drafts". The current checks would not notice if typed text were lost from the saved draft.
    - Suggested fix: Also assert that the fetched draft contains `Here are the numbers ${run}.`. Optionally assert the status leaves "Unsaved changes" after the attachment save, not just that it is visible.

## Review conclusion

Material issues found; fix before completing the task. No P0 or P1 issues: the spec covers SPEC.md §13.2 Phase 5 steps 1-6 against GreenMail, and the stack changes are consistent. CR-1 (P2) should be fixed or explicitly accepted by the parent with the assumption corrected, and the criterion evidence must include a full phase-demos run on the final code.
