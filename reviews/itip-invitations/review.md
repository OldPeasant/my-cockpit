# Code Review

Date: 2026-10-26

Task: itip-invitations - Send invitations for local events (iTIP)

Scope: Re-review of the final code after the fixer pass: `git diff 35a77101..HEAD -- server` (task commits a7a704ab, 2ed127d1, 7df31077, 091d29b6, 800c4e03, 0df4bd14; 90e1856e only adds `tasks/itip-api.md` and touches no reviewed path). Production code was reviewed in full. Test files were checked for coverage of the fixes only, because a test-writer may be editing them at the same time. Uncommitted changes are not part of this review.

Requirements:
- `tasks/itip-invitations.md` acceptance criteria and its ASSUMPTION notes, including the newer ones: CR-3 is only partly fixed (in-message ordering only), and an uninvite CANCEL with mixed recipients is rejected with 400
- SPEC.md §6.2 (Invitations), §4.2 conventions, logging rules in AGENTS.md
- docs/ARCHITECTURE.md "Conventions" (modules talk through services and events)
- RFC 5546 (iTIP), RFC 6047 (iMIP)
- Previous review (findings CR-1..CR-4) and `reviews/itip-invitations/fixes.md`

Git range:
- Branch: main
- HEAD: 0df4bd143b24c205a78725be8f124872fa610696
- Base/start: 35a771019003fe38dfb83cb9726695d905e419fe
- Commits reviewed: 6 (plus 90e1856e, which changes no reviewed paths)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Status of earlier findings

- **CR-1 (P2) An uninvite CANCEL set STATUS:CANCELLED and could also go to current attendees: Resolved.** `ItipMessages.build` (`ItipMessages.kt:99-108`) now writes `STATUS:CANCELLED` only for a full CANCEL. With `attendeesOnly` it removes STATUS and lists only the people who were uninvited. `ItipService.invitation` (`ItipService.kt:149-156`) rejects a live-event CANCEL whose recipients mix current attendees and non-attendees, using the existing 400 `InvalidRequestException`. A CANCEL for a trashed event is still a full cancellation for everyone (`attendeesOnly` is null when `trashed`). These GreenMail tests cover the change: `...does not cancel the event`, `...at once is a bad request (400)` and `...in the Trash may go to attendees and people no longer invited`. The 400 rule is recorded as an assumption.
- **CR-2 (P2) ItipReplyProcessor used the mail repositories: Resolved.** The processor now depends only on `MailReadService` and `MailContentLoader` (`mail.api`). The new `MailReadService.inInbox` (`MailReadService.kt:94-100`) is read-only, has the same semantics as the old inline check, and no longer uses `folderId!!`. The returned entity's `from` field is converter-mapped (not lazy), so reading it after the read-only transaction is safe.
- **CR-3 (P3) REPLY ordering: Partially fixed, and the rest is deferred with a recorded assumption.** Answers within one message are reduced to the newest by SEQUENCE, then DTSTAMP (`ItipReplyProcessor.kt:111-116`, `ItipReply.ORDER`). Answers in different messages are still not ordered, because that needs per-attendee storage. Kept below as a deferred P3.
- **CR-4 (P3) A REPLY was lost on an optimistic-lock conflict: Resolved.** `applyRetrying` (`ItipReplyProcessor.kt:126-136`) runs each attempt in a new `TransactionTemplate` transaction. `apply` reloads the event on every attempt, and there are at most 3 attempts. `saveAndFlush` throws inside the transaction, so the rolled-back attempt is retried on fresh state. The change events are only delivered after commit (`@TransactionalEventListener`), so a rolled-back attempt does not leak notifications. `ItipReplyProcessorTests` covers this.

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
    - Problem: Only answers inside one message are ordered. An older REPLY that arrives later in a separate message, such as one delivered late or moved back into the Inbox, still overwrites a newer answer.
    - Why it matters: In rare cases an attendee's status can go back to an older answer.
    - Suggested fix: As recorded in the task assumption, a human has to decide where a per-attendee "answered at" value is stored (an `Attendee` JSON field or an itip table). Leaving it as it is for this task is acceptable, because the decision is documented.

- [ ] **CR-5 | An uninvite CANCEL says "Cancelled" and "This event was cancelled" in its subject and text part**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipService.kt:166,181`
    - Category: Quality
    - Problem: The subject prefix and plain-text summary depend only on `method`. An uninvite CANCEL, where `attendeesOnly` is set and the event goes ahead, therefore says the event was cancelled, while the calendar part (correctly, since CR-1) says the event is not cancelled.
    - Why it matters: Recipients who read the text part, or whose client does not show the calendar part, get a wrong message: the event is not cancelled, they are just no longer invited. The calendar data stays correct.
    - Suggested fix: When an uninvite is sent, use wording such as "No longer invited: <title>" / "You are no longer invited to: <title>". Optionally, assert the subject in the existing uninvite GreenMail test.

## Review conclusion

No material issues found.

Notes:
- Criterion 1 (REQUEST/CANCEL as text/calendar mails; incoming REPLY updates attendee status) is implemented. The mail is multipart/alternative with a `text/calendar; method=...` part. The ORGANIZER is the account identity, VALARMs are removed and SEQUENCE is the event version. A REPLY is applied only for LOCAL, non-trashed events that list the sender as an attendee and only from an Inbox. `ItipService.send` has no caller yet. That is intended (recorded assumption; follow-up task `itip-api`).
- Criterion 2: `ItipGreenMailTests` is `@ExternalTest` and covers the new CR-1 paths. This reviewer could not run tests: running Gradle was not permitted in this session. The parent worker must record the executed `./gradlew test --tests '*itip*'` and `./gradlew integrationTest --tests '*ItipGreenMail*'` results with `tasks.py verify`. The fix report states these passed: 26+4 unit tests and 32 GreenMail tests.
- `MailReadService.kt` is outside the task's `touches`. The change is a minimal, additive read method that the parent approved, and it is the correct fix for CR-2.
- Logging: only message ids, counts and exception class names are logged at INFO and WARN. No addresses, bodies or calendar contents are logged.
- No secrets or personal data were found in the diff. The test addresses are fictitious.
