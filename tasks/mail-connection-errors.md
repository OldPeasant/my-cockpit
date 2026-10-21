---
title: Shared MailConnectionException handler for mail controllers
status: todo
owner:
deps: [mail-gtd]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/api,server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailAccountController.kt,server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdController.kt]
prio: 2
---
## Goal
Remove the duplicated `@ExceptionHandler(MailConnectionException::class)` copies in MailMessageController,
MailAccountController and MailGtdController (deferred review finding of mail-gtd): one shared handler
(e.g. a `@RestControllerAdvice` scoped to the mail controllers) with the same Problem Details response.

## Acceptance criteria
- [ ] A single MailConnectionException handler serves all mail controllers; the per-controller copies are gone
- [ ] Response (status, Problem Details type/title/detail) unchanged; existing mail controller tests pass (`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.*'`)

## Notes
