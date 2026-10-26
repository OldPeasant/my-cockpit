# Code Review

Date: 2026-10-26

Task: itip-invitations - Send invitations for local events (iTIP)

Scope: Re-review of the final code after the fixer pass and the test-writer's follow-up tests: `git diff 35a77101..4432d5de -- server`.
- Task commits: a7a704ab, 2ed127d1, 7df31077, 091d29b6, 800c4e03, 0df4bd14 and 4432d5de.
- Commits 90e1856e and d363170a only add task files (`tasks/itip-api.md` and `tasks/itip-reply-ordering.md`).
- Production code was reviewed in full.
- Commit 4432d5de changes tests and reports only; `git diff --stat 0df4bd14..4432d5de -- server/src/main` is empty. Its test changes were reviewed in full.
- Uncommitted changes are not part of this review.

Requirements:
- `tasks/itip-invitations.md` acceptance criteria and its ASSUMPTION notes, including the newer ones: CR-3 is only partly fixed (in-message ordering only), and an uninvite CANCEL with mixed recipients is rejected with 400
- SPEC.md §6.2 (Invitations), §4.2 conventions, logging rules in AGENTS.md
- docs/ARCHITECTURE.md "Conventions" (modules talk through services and events)
- RFC 5546 (iTIP), RFC 6047 (iMIP)
- Previous review (findings CR-1..CR-4) and `reviews/itip-invitations/fixes.md`

Git range:
- Branch: main
- HEAD: 4432d5deef72344c6aed6a3d02a3d7a786f2f077
- Base/start: 35a771019003fe38dfb83cb9726695d905e419fe
- Commits reviewed: 7 (plus 90e1856e and d363170a, which change no reviewed paths)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Status of earlier findings

- **CR-1 (P2) An uninvite CANCEL set STATUS:CANCELLED and could also go to current attendees: Resolved.**
    - `ItipMessages.build` (`ItipMessages.kt:99-108`) writes `STATUS:CANCELLED` only for a full CANCEL. With `attendeesOnly`, it removes STATUS and lists only the uninvited people.
    - `ItipService.invitation` (`ItipService.kt:149-156`) rejects a live-event CANCEL whose recipients mix current attendees and non-attendees. It uses the existing 400 `InvalidRequestException`. The 400 rule is recorded as an assumption.
    - A CANCEL for a trashed event is still a full cancellation for everyone (`attendeesOnly` is null when `trashed`).
    - GreenMail tests cover these cases: `...does not cancel the event`, `...at once is a bad request (400)` and `...in the Trash may go to attendees and people no longer invited`.
- **CR-2 (P2) ItipReplyProcessor used the mail repositories: Resolved.**
    - The processor depends only on `MailReadService` and `MailContentLoader` (`mail.api`).
    - The new `MailReadService.inInbox` (`MailReadService.kt:94-100`) is read-only and has the same semantics as the old inline check, without `folderId!!`.
    - The returned entity's `from` field is converter-mapped (not lazy), so reading it after the read-only transaction is safe.
    - `MailReadServiceInInboxTests` (4432d5de) covers it.
- **CR-3 (P3) REPLY ordering: Partially fixed. The rest is deferred, recorded as an assumption, and planned in follow-up task `itip-reply-ordering`.**
    - Answers within one message are reduced to the newest, by SEQUENCE and then DTSTAMP (`ItipReplyProcessor.kt:111-116`, `ItipReply.ORDER`).
    - Answers in different messages are still not ordered. Kept below as a deferred P3.
- **CR-4 (P3) A REPLY was lost on an optimistic-lock conflict: Resolved.**
    - `applyRetrying` (`ItipReplyProcessor.kt:126-136`) runs each attempt in its own `TransactionTemplate` transaction. `apply` reloads the event each time. There are at most 3 attempts.
    - Change events are delivered only after commit (`@TransactionalEventListener`), so a rolled-back attempt does not leak notifications.
    - `ItipReplyProcessorTests` covers this. Since 4432d5de, a test also checks that failures other than optimistic-lock conflicts are not retried.

## Test changes in 4432d5de

- `ItipReplyProcessorTests`: 7 new tests:
    - replies for different events in one message are both applied (not merged by the newest-answer grouping);
    - failures other than optimistic-lock conflicts are not retried;
    - an answer that is already stored is not saved or published;
    - a message without a sender is never loaded from the server;
    - a series REPLY skips overrides in the Trash;
    - an unknown charset falls back to UTF-8;
    - `onChange` queues only newly created EMAIL changes.
- The tests assert observable behavior: returned ids, saved state, repository and publisher calls. The `onChange` test relies on the single worker thread processing in order, and it shuts the executor down in `finally`. That is sound.
- `MailReadServiceInInboxTests`: a message in the Inbox is returned. These give null: other roles and no role, a detached or folderless message (without looking up a folder), an unknown message, and a missing folder.
- The positional mock constructor matches `MailReadService`'s parameter order: locator, messages, accounts, folders, ...
- The test data is fictitious (`example.test`).
- No existing test was removed or weakened. The test helpers got only additive parameters with defaults.
- No issues were found in these test changes.

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
    - Problem: Only answers inside one message are ordered. An older REPLY that arrives later in a separate message (delivered late, or moved back into the Inbox) still overwrites a newer answer.
    - Why it matters: In rare cases, an attendee's status can go back to an older answer.
    - Suggested fix: Planned in follow-up task `tasks/itip-reply-ordering.md`, which also covers where a per-attendee answered-at value is stored. Deferring it from this task is acceptable.

- [ ] **CR-5 | The subject and text of an uninvite CANCEL say the event was cancelled**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipService.kt:166,181`
    - Category: Quality
    - Problem: The subject prefix ("Cancelled") and the plain-text summary ("This event was cancelled") depend only on `method`. An uninvite CANCEL (`attendeesOnly` set, the event goes ahead) therefore says the event was cancelled. Its calendar part, correct since the CR-1 fix, says the event is not cancelled.
    - Why it matters: Recipients who read the text part, or whose client does not show the calendar part, are told the event is cancelled when they are only no longer invited. The calendar data is correct.
    - Suggested fix: For an uninvite, use wording such as "No longer invited: <title>" / "You are no longer invited to: <title>". Optionally, assert the subject in the existing uninvite GreenMail test.

## Review conclusion

No material issues found.

Notes:
- Criterion 1 (REQUEST/CANCEL as text/calendar mails; incoming REPLY updates attendee status) is implemented:
    - The mail is multipart/alternative with a `text/calendar; method=...` part.
    - ORGANIZER is the account's identity, VALARMs are removed, and SEQUENCE is the event version.
    - A REPLY is applied only from an Inbox, only for LOCAL, non-trashed events, and only when the event lists the sender as an attendee.
    - `ItipService.send` has no caller yet. That is intended (recorded assumption; follow-up task `itip-api`).
- Criterion 2: `ItipGreenMailTests` is `@ExternalTest` and covers the new CR-1 paths.
- I could not run any tests, because Gradle was not permitted in this session. The parent worker must record the results of `./gradlew test --tests '*itip*'` (and the new `MailReadServiceInInboxTests`) and `./gradlew integrationTest --tests '*ItipGreenMail*'` with `tasks.py verify`.
- `MailReadService.kt` is outside the task's `touches`. The change adds one small read method, which the parent approved, and it is the right fix for CR-2.
- Logging: only message ids, counts and exception class names are logged at INFO and WARN. No addresses, bodies or calendar contents are logged.
- I found no secrets or personal data in the diff. The test addresses are fictitious.
