# Code Review

Date: 2026-10-26

Task: itip-invitations - Send invitations for local events (iTIP)

Scope: `git diff dc2d0063..HEAD -- server/` (task commits 7cf8814f, a08ff11e, ca4ebe4e; 0ad26b12 only adds `tasks/itip-api.md` and is outside the reviewed paths). Uncommitted test changes made at the same time by the test-writer are not part of this review.

Requirements:
- `tasks/itip-invitations.md` acceptance criteria and its ASSUMPTION notes (no REST endpoint in this task; anti-spoofing by From address; occurrence REPLY without an override is ignored; SEQUENCE = event version)
- SPEC.md §6.2 (Invitations), §4.2 conventions, logging rules in AGENTS.md
- docs/ARCHITECTURE.md "Conventions" (modules talk through services and events, never through another module's repositories)
- RFC 5546 (iTIP), RFC 6047 (iMIP)

Git range:
- Branch: main
- HEAD: ca4ebe4e703ae8f5cc555c0f9dc84219263e9798
- Base/start: dc2d00636310204390d9c1006d93519fb02c91a1
- Commits reviewed: 3 (plus 0ad26b12, which changes no reviewed paths)

Summary: 0 P0, 0 P1, 2 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A CANCEL that uninvites attendees sets STATUS:CANCELLED and is also sent to attendees who are still invited**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipMessages.kt:80-86`, `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipService.kt:146,154`
    - Category: Correctness
    - Problem: `build` always writes `STATUS:CANCELLED` for a CANCEL, also when `attendeesOnly` is set to uninvite particular people. RFC 5546 §3.2.5 (CANCEL table, STATUS) says STATUS is set to CANCELLED to cancel the whole event and "MUST NOT be included" when uninviting specific attendees. Also, `ItipService.invitation` computes `attendeesOnly` from only the non-attendees among the requested recipients, but sends the mail to every requested recipient. A CANCEL with `recipients = [current attendee, removed person]` therefore sends current attendees a CANCEL marked `STATUS:CANCELLED` for the event's UID. Their calendar clients will remove an event that is still going ahead. The unit test `a CANCEL to people no longer invited lists only them as attendees` and the GreenMail test `a CANCEL to someone no longer invited lists only them` assert the non-compliant `STATUS:CANCELLED`.
    - Why it matters: Attendees can lose a meeting that is not cancelled. Clients that follow the RFC may also treat an uninvite as a cancellation of the whole event.
    - Suggested fix: Leave out STATUS (or keep the event's own status) when `attendeesOnly` is used. In `ItipService`, reject mixing current attendees and non-attendees in one CANCEL for a live event with a 400, or send the uninvite only to the non-attendees. Change the two tests to match and add a test for mixed recipients.

- [ ] **CR-2 | ItipReplyProcessor reads the mail module's repositories directly**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipReplyProcessor.kt:16,18,49-50,101-104`
    - Category: Architecture
    - Problem: The calendar module injects `MailMessageRepository` and `MailFolderRepository` to load the message and check the folder role. docs/ARCHITECTURE.md "Conventions" says modules talk "through services and events, never through another module's repositories". In `server/src/main/kotlin`, this is the only class outside `mail/` that imports a `mail.model` repository. The mail module already has service entry points, such as `MailReadService.get(id)` and `MailContentLoader`.
    - Why it matters: The calendar module now depends on mail's persistence model, such as `isLocated` and `folderId!!`. A refactoring inside the mail module would then break calendar code.
    - Suggested fix: Add or use a mail-module service method, for example "is this message located in an Inbox, and who sent it", and call it from `ItipReplyProcessor` instead of the repositories.

## P3 Low

- [ ] **CR-3 | REPLY ordering (SEQUENCE/DTSTAMP) is not checked, so an older answer can overwrite a newer one**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipMessages.kt:108-128`, `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipReplyProcessor.kt:138-148`
    - Category: Correctness
    - Problem: `ItipReply` keeps no SEQUENCE or DTSTAMP, and `update` applies every matching REPLY that is processed. Any message that reaches the Inbox counts as a new arrival, including an old reply that a user moves back into the Inbox or a reply delivered late. Such a message replaces the attendee's newer answer. RFC 5546 §2.1.5 tells the Organizer CUA to use SEQUENCE and DTSTAMP to decide which REPLY is current.
    - Why it matters: Attendee status can silently go back to an older answer.
    - Suggested fix: Keep the REPLY's DTSTAMP and ignore replies older than the last one applied for that attendee, for example by storing a per-attendee "answered at" value. Then record the chosen rule with `tasks.py assume`.

- [ ] **CR-4 | A REPLY that collides with a concurrent edit is lost and never retried**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipReplyProcessor.kt:86-89,145`
    - Category: Quality
    - Problem: `saveAndFlush` uses optimistic locking. If the user edits the same event while a reply is being applied, the `OptimisticLockingFailureException` is caught as a generic `RuntimeException` and logged at WARN, and the reply is dropped. Nothing processes the message again.
    - Why it matters: In rare cases, an attendee's answer never shows up, and the only sign is a WARN log line.
    - Suggested fix: Retry `apply` once or twice on an optimistic-lock failure. Each retry reloads the event in a new transaction.

## Review conclusion

Material issues found; fix before completing the task.

Notes:
- Criterion 1 (REQUEST/CANCEL as text/calendar mails; incoming REPLY updates attendee status) is implemented. The mail is multipart/alternative with a `text/calendar; method=...` part, From is the ORGANIZER, VALARMs are removed, overrides get the correct RECURRENCE-ID, and REPLYs are applied after header sync. The only exception is the CANCEL problem in CR-1. `ItipService.send` has no caller yet. That is the recorded assumption, and follow-up task `itip-api` covers it.
- Criterion 2: `ItipGreenMailTests` is annotated `@ExternalTest` and covers sending, the error responses (404, 409, 400) and REPLY processing, including a spoofed sender, a non-Inbox folder, a synced calendar and series or occurrence replies. This review did not run any tests and did not check recorded `tasks.py verify` evidence. The task file still shows both criteria unticked, so the parent worker has to record that evidence.
- Spoofing: a REPLY is only accepted if its From header matches the attendee, and only for LOCAL events that list that attendee. From headers can be forged, so this is a weak check. That limitation is recorded as an assumption in the task and is not reported as a finding.
- Logging: only message and event ids, counts and exception class names are logged. No mail bodies, calendar contents or addresses are logged at INFO or above.
