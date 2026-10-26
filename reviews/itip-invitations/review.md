# Code Review

Date: 2026-10-26

Task: itip-invitations - Send invitations for local events (iTIP)

Scope: Re-review of the final task code at HEAD: `git diff b2d98ecdba880e9cdc6224fab11ca8cff725d3ec..HEAD -- server/`, limited to `calendar/itip` (main and test), `mail/api/MailReadService.inInbox` and `MailReadServiceInInboxTests`.
- Task commits touching `server/`: d5020088, ad6f82b8, 01c1b362, 5cac30ad, 102b9a20, bc0860c6, 4745a792.
- `git diff --stat 4745a792..HEAD -- server/` is empty, so the code is the same as in the previous review (rebased; same task patch). I read the production files in full again.
- The working tree is clean. Uncommitted changes are not part of this review.

Requirements:
- `tasks/itip-invitations.md` acceptance criteria and its ASSUMPTION notes (no REST endpoint in this task; REPLY only from an Inbox, from the attendee's own address, for LOCAL events; occurrence REPLY without an override is ignored; CR-3 only partly fixed; mixed-recipient uninvite CANCEL rejected with 400)
- SPEC.md §6.2, "Invitations" bullet (sending invites for LOCAL events via email, iTIP REQUEST/REPLY)
- AGENTS.md project notes (modules talk through services and events, logging rules, fictitious test data)
- RFC 5546 (iTIP) and RFC 6047 (iMIP)
- Previous review (CR-1 to CR-5), `reviews/itip-invitations/fixes.md` and the dispositions in `reviews/itip-invitations/quality.json`

Git range:
- Branch: main
- HEAD: eaf543d45406d59f0a1fa40be0b67b4d45c5b132
- Base/start: b2d98ecdba880e9cdc6224fab11ca8cff725d3ec
- Commits reviewed: 7 (those touching `server/`)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Status of earlier findings and their dispositions

- **CR-1 (P2), disposition "fixed": confirmed.**
    - `ItipMessages.build` (`ItipMessages.kt:99-108`) writes `STATUS:CANCELLED` only for a full CANCEL. With `attendeesOnly` it removes STATUS and lists only the uninvited addresses.
    - `ItipService.invitation` (`ItipService.kt:149-156`) rejects a CANCEL for a live event whose recipients mix attendees and non-attendees, with the existing 400 `InvalidRequestException`. This matches the recorded assumption.
    - For a trashed event, `attendeesOnly` is null (`ItipService.kt:164`), so it is a full cancellation for everyone.
    - GreenMail tests cover the three paths (`ItipGreenMailTests.kt:258`, `:270`, `:281`).
- **CR-2 (P2), disposition "fixed": confirmed.**
    - `ItipReplyProcessor` imports only `mail.api.MailContentLoader`, `mail.api.MailReadService` and `mail.imap.MailConnectionException` from the mail module. No mail repository is imported anywhere in `calendar/itip`.
    - `MailReadService.inInbox` is read-only and null-safe on `folderId`. `MailReadServiceInInboxTests` covers Inbox, other roles, no role, detached or folderless rows, unknown message and missing folder.
- **CR-3 (P3), disposition "deferred": acceptable.**
    - Answers within one message are reduced to the newest by SEQUENCE, then DTSTAMP (`ItipReplyProcessor.kt:110-117`, `ItipReply.ORDER` in `ItipMessages.kt:52-53`).
    - Ordering across messages is not done. This is recorded as an assumption, and follow-up task `tasks/itip-reply-ordering.md` exists. Still listed below as a P3 for traceability.
- **CR-4 (P3), disposition "fixed": confirmed.**
    - `applyRetrying` (`ItipReplyProcessor.kt:126-136`) runs each attempt in its own transaction. `apply` reloads the event, and there are at most 3 attempts. Change events are only delivered after commit.
    - `ItipReplyProcessorTests` covers one conflict followed by success, repeated conflicts, and that other failures are not retried.
- **CR-5 (P3), disposition "accepted": acceptable.**
    - The code is unchanged: the subject prefix (`ItipService.kt:166`) and the text summary (`ItipService.kt:181`) still say "Cancelled" for an uninvite CANCEL.
    - Accepting it for now is reasonable. The text/calendar part, which clients act on, is correct. `ItipService.send` has no caller yet; the follow-up task `itip-api` exposes it and can change the wording. Still listed below.

## New findings

I found no new validated issues. Checked in particular:
- Recipient validation: invalid addresses, REQUEST only to attendees, duplicate addresses removed case-insensitively, an empty list is a 409.
- Header injection through the title: covered by `ItipGreenMailTests.kt:599`.
- REPLY matching: the invitation check uses the answered target (master for a series answer, the override for an occurrence answer). Overrides in the Trash are skipped. A UID naming an override's own id is ignored.
- Background processing: a bounded single-thread queue, a full queue is logged and not fatal, the executor is shut down on destroy, and every failure ends in a log line.
- Logging: only message ids, counts and exception class names at INFO and WARN. No addresses, bodies or calendar data are logged.
- No secrets or personal data in the diff; test addresses use `example.test`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-3 | Cross-message REPLY ordering is still not checked (deferred)**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipReplyProcessor.kt:110-117`
    - Category: Correctness
    - Problem: Only answers within one message are ordered. An older REPLY that arrives later in a separate message (delivered late, or moved back into the Inbox) still overwrites a newer answer.
    - Why it matters: In rare cases an attendee's status can go back to an older answer.
    - Suggested fix: Planned in follow-up task `tasks/itip-reply-ordering.md`, which needs a place to store a per-attendee answered-at value. Deferring it from this task is acceptable.

- [ ] **CR-5 | The subject and text part of an uninvite CANCEL say the event was cancelled (accepted)**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipService.kt:166,181`
    - Category: Quality
    - Problem: The subject prefix "Cancelled" and the text "This event was cancelled" depend only on the method. An uninvite CANCEL, where the event goes ahead, therefore says the event was cancelled. The calendar part is correct.
    - Why it matters: Recipients who read only the text part are told the event is cancelled when they are only no longer invited.
    - Suggested fix: For an uninvite, use wording such as "No longer invited: {event title}" and "You are no longer invited to: {event title}", and assert the subject in the existing uninvite GreenMail test. Accepted for now; it can be done in `itip-api`.

## Review conclusion

No material issues found.

Notes:
- Criterion 1 (REQUEST/CANCEL as text/calendar mails; incoming REPLY updates attendee status): implemented. The mail is multipart/alternative with a `text/calendar; method=...` part. ORGANIZER is the account's default identity, VALARMs are removed, and SEQUENCE is the event version. A REPLY is applied only from an Inbox, for a LOCAL, non-trashed event that lists the sender as an attendee.
- Criterion 2 (`external` GreenMail tests): `ItipGreenMailTests` is annotated `@ExternalTest` and covers sending and receiving through GreenMail SMTP and IMAP.
- Evidence: I did not run Gradle (not permitted for sub-agents here). The parent reported these results, which also match the recorded verify lines: `./gradlew test --tests '*itip*' --tests '*MailReadServiceInInboxTests' ktlintCheck` passed (ItipMessagesTests 26/26, ItipReplyProcessorTests 11/11, MailReadServiceInInboxTests 4/4, ktlint clean), and `./gradlew integrationTest --tests '*ItipGreenMail*'` passed 32/32.
- `MailReadService.kt` is outside the task's `touches`. The change is one small read method that the parent approved, and it is the right fix for CR-2.
