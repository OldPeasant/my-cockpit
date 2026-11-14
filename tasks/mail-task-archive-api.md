---
title: Mail → Next action & archive: server call
status: in_progress
owner: dell26/agent-1
deps: []
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd,server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd,api/openapi.yaml,web/src/app/api/generated,SPEC.md]
prio: 2
lease_until: 2026-11-14T08:17:05Z
releases: 1
extended: 
---
## Goal
Turning a mail into a task is the GTD clarify step, so the task goes straight to Next Actions and the mail
leaves the mail inbox. Today `POST /api/v1/mail/messages/{id}/to-task` (`mail/gtd/MailGtdController.kt`) only
creates a linked Inbox task; archiving is a separate mail action. One server call serves all three client entry
points (read view, New view row, mail list multi-select) and handles partial failure once.

## Acceptance criteria
- [ ] One call takes mail ids (1..n), a target list (default `NEXT`) and `archive` (default true); per mail it creates a task titled with the subject, linked to the mail exactly as `to-task` does, then archives the mail via the existing ARCHIVE action
- [ ] The response lists, per mail, the created task (id, title, list) and whether archiving succeeded, with the reason if not; a failed archive keeps the task (no rollback), an unknown/inaccessible mail id gives a Problem Details error without creating anything for it
- [x] After archiving, the task's link still resolves to the (moved) mail, also when the IMAP server lacks MOVE (GreenMail test)
  - verified (2026-11-14 08:30+0100 by dell26/agent-1): cd server && ./gradlew integrationTest --tests '*MailTaskArchiveGreenMailTests' (GreenMail; no-MOVE case via IMAPStore subclass hiding MOVE, COPY+EXPUNGE path) -> 4 tests, 0 failures; link resolves to the Archive copy before and after re-sync
- [ ] The existing `to-task` (Inbox, no archive) is unchanged
- [x] `api/openapi.yaml` and `web/src/app/api/generated/` regenerated; SPEC.md §7.3 (GTD integration) and §7.4 describe the action
  - verified (2026-11-14 08:30+0100 by dell26/agent-1): cd server && ./gradlew updateOpenApi; cd web && npm run api:gen -> openapi.yaml gains only POST /api/v1/mail/messages/to-tasks + 4 schemas, generated fn mail-to-tasks.ts and models; SPEC.md 7.3 (Next action & archive) and 7.4 (to-task, to-tasks) updated
- [ ] Tests cover single, multiple, partial archive failure (no Archive folder) and the link; `cd server && ./gradlew check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-14 with the owner: from a mail, make a Next action and archive the mail in one step (mails already clarified should not pass through the cockpit Inbox again); create a project directly from the task detail.
- ASSUMPTION (2026-11-14 08:27+0100 by dell26/agent-1): Endpoint is POST /api/v1/mail/messages/to-tasks {ids (1..100), list (default NEXT; only NEXT, INBOX, SOMEDAY allowed, WAITING/SCHEDULED -> 400 since they need fields a mail does not give), archive (default true)}; 200 with one result per distinct id; any unknown id -> 404 and nothing created for any id (all ids resolved before creating); ids resolving to the same message share one task
- release by dell26/agent-1: Done: MailGtdService.toTasks + MailTaskArchiveController (POST /api/v1/mail/messages/to-tasks), MailTaskArchiveWebTests (6 pass: ./gradlew test --tests '*MailTaskArchiveWebTests'), MailTaskArchiveGreenMailTests (3/4 pass via ./gradlew integrationTest --tests '*MailTaskArchiveGreenMailTests'). Left: (1) the 'without MOVE' GreenMail test fails with StackOverflowError because mockkConstructor(IMAPStore) recurses in Angus; replace it (e.g. check whether GreenMail advertises MOVE at all, else a test IMAPStore subclass overriding hasCapability or a spy around MailSessions) ; (2) regenerate api/openapi.yaml + web generated client (api/README.md, npm run api:gen); (3) SPEC.md 7.3/7.4 text; (4) test-writer + code-reviewer + quality.json, verify, done. Existing MailGtdWebTests still pass (to-task unchanged).
