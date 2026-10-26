---
title: Shared MailConnectionException handler for mail controllers
status: in_progress
owner: felix/agent-1
deps: [mail-gtd]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/api,server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailAccountController.kt,server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdController.kt]
prio: 2
lease_until: 2026-10-26T02:25:10Z
---
## Goal
Remove the duplicated `@ExceptionHandler(MailConnectionException::class)` copies in MailMessageController,
MailAccountController and MailGtdController (deferred review finding of mail-gtd): one shared handler
(e.g. a `@RestControllerAdvice` scoped to the mail controllers) with the same Problem Details response.

## Acceptance criteria
- [ ] A single MailConnectionException handler serves all mail controllers; the per-controller copies are gone
- [ ] Response (status, Problem Details type/title/detail) unchanged; existing mail controller tests pass (`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.*'`)

## Notes
- ASSUMPTION (2026-10-26 02:42+0100 by felix/agent-1): DraftController (mail/compose, outside touches) had a fourth identical MailConnectionException handler; removed it too, since the advice covers the whole ch.sonensei.cockpit.mail package and the criterion says all per-controller copies are gone
- ASSUMPTION (2026-10-26 02:42+0100 by felix/agent-1): The advice is @RestControllerAdvice(basePackages=ch.sonensei.cockpit.mail) with HIGHEST_PRECEDENCE so it wins over the global ProblemDetailsAdvice catch-all (500); response body/status/log lines copied verbatim
