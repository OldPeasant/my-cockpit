---
title: Mail → Next action & archive: server call
status: todo
owner:
deps: []
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd,server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd,api/openapi.yaml,web/src/app/api/generated,SPEC.md]
prio: 2
---
## Goal
Turning a mail into a task is the GTD clarify step, so the task goes straight to Next Actions and the mail
leaves the mail inbox. Today `POST /api/v1/mail/messages/{id}/to-task` (`mail/gtd/MailGtdController.kt`) only
creates a linked Inbox task; archiving is a separate mail action. One server call serves all three client entry
points (read view, New view row, mail list multi-select) and handles partial failure once.

## Acceptance criteria
- [ ] One call takes mail ids (1..n), a target list (default `NEXT`) and `archive` (default true); per mail it creates a task titled with the subject, linked to the mail exactly as `to-task` does, then archives the mail via the existing ARCHIVE action
- [ ] The response lists, per mail, the created task (id, title, list) and whether archiving succeeded, with the reason if not; a failed archive keeps the task (no rollback), an unknown/inaccessible mail id gives a Problem Details error without creating anything for it
- [ ] After archiving, the task's link still resolves to the (moved) mail, also when the IMAP server lacks MOVE (GreenMail test)
- [ ] The existing `to-task` (Inbox, no archive) is unchanged
- [ ] `api/openapi.yaml` and `web/src/app/api/generated/` regenerated; SPEC.md §7.3 (GTD integration) and §7.4 describe the action
- [ ] Tests cover single, multiple, partial archive failure (no Archive folder) and the link; `cd server && ./gradlew check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-14 with the owner: from a mail, make a Next action and archive the mail in one step (mails already clarified should not pass through the cockpit Inbox again); create a project directly from the task detail.
