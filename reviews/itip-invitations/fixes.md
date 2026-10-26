# Fix Report

Date: 2026-10-26

Task: itip-invitations - Send invitations for local events (iTIP)

Scope: Findings CR-1 to CR-4 from `reviews/itip-invitations/review.md`. Base b3acece8. Changes are in the working tree only. The parent worker commits them.

## Fixed findings

### CR-1 - An uninvite CANCEL set STATUS:CANCELLED and could also go to attendees who are still invited

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipMessages.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/itip/ItipMessagesTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/itip/ItipGreenMailTests.kt`

Problem:
`ItipMessages.build` wrote `STATUS:CANCELLED` for every CANCEL, including one with `attendeesOnly` (an uninvite). RFC 5546 §3.2.5 says STATUS must not be included in that case. `ItipService` also sent the uninvite CANCEL to every requested recipient. With mixed recipients, current attendees therefore received a cancellation of an event that is still going ahead.

Fix:
- `build` now writes `STATUS:CANCELLED` only for a full CANCEL. With `attendeesOnly` it removes STATUS.
- `ItipService` rejects a CANCEL for a live (not trashed) event whose recipients mix current attendees with people who are no longer invited. It throws the existing 400 `InvalidRequestException("recipients", ...)`. I chose rejection over silently sending to only the non-attendees. It is simpler, it matches the existing REQUEST validation, and a caller never gets a partial send without noticing. A CANCEL for an event in the Trash may still go to both groups as a full cancellation.
- Tests: the two tests that asserted `STATUS:CANCELLED` on an uninvite now assert that there is no STATUS. New GreenMail tests: mixed recipients for a live event give 400 and nothing is sent; mixed recipients for a trashed event get a full CANCEL.

Validation:
- `./gradlew test --tests '*itip*' ktlintCheck` - Pass
- `./gradlew integrationTest --tests '*ItipGreenMail*'` - Pass (32/32)

### CR-2 - ItipReplyProcessor read the mail module's repositories directly

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadService.kt` (outside the task's touches; the parent approved a minimal change)
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipReplyProcessor.kt`

Problem:
The calendar module injected `MailMessageRepository` and `MailFolderRepository`, which breaks the "modules talk through services and events" convention.

Fix:
Added the read-only `MailReadService.inInbox(id): MailMessage?`. It returns the message row when it is located in a folder with role INBOX, and null otherwise. It has the same semantics as the old inline check, but without the `folderId!!`. `ItipReplyProcessor` now injects `MailReadService` instead of the repositories, and its unused read-only `TransactionTemplate` was removed. No class outside `mail/` imports a mail repository any more.

Validation:
- `./gradlew test --tests '*itip*'` - Pass (new unit test: a message not in an Inbox is never loaded)
- `./gradlew integrationTest --tests '*ItipGreenMail*'` - Pass (the existing Inbox, Archive and gone-message cases go through the new method)
- `./gradlew test --tests 'ch.sonensei.cockpit.mail.*'` - Pass

### CR-4 - A REPLY that collided with a concurrent edit was lost

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipReplyProcessor.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/itip/ItipReplyProcessorTests.kt` (new)

Problem:
An `OptimisticLockingFailureException` from `saveAndFlush` was caught as a generic `RuntimeException` and logged at WARN, and the answer was dropped.

Fix:
New `applyRetrying`: each attempt runs `apply` in its own `TransactionTemplate` transaction, and `apply` reloads the event. There are at most 3 attempts in all (`MAX_ATTEMPTS`). After the last one the exception propagates to the existing WARN handling.

Validation:
- `ItipReplyProcessorTests`: one conflict, then success (event reloaded twice, saved twice, indexed once), and repeated conflicts, which give up after 3 saves - Pass

## Partially fixed

### CR-3 - REPLY ordering (SEQUENCE/DTSTAMP) is not checked

Status:
Partially fixed. The rest requires a decision.

What was changed (`ItipMessages.kt`, `ItipReplyProcessor.kt`, tests in `ItipMessagesTests.kt` and `ItipReplyProcessorTests.kt`):
- `ItipReply` now keeps the REPLY's `sequence` (SEQUENCE) and `stamp` (DTSTAMP, UTC). Each is null when missing or unreadable. `ItipReply.ORDER` sorts by SEQUENCE, then DTSTAMP, with unknown values first.
- Rule implemented: when one message holds several answers from the same attendee for the same UID and RECURRENCE-ID, only the newest by that order is applied.

Reason the rest is not fixed:
Ignoring an answer that is older than the last one applied *across messages* needs per-attendee state, such as an "answered at" value. There is no place to keep it inside this task's scope.
- A new table needs a Flyway migration.
- A field on the shared `Attendee` JSON (`calendar/model/CalendarEvent.kt`) would need no migration. But it changes `Attendee` equality, which `EventCommands` uses to decide how series edits propagate (`event.attendees == before.attendees`). It would also affect backup/restore (`CalendarBackupContributor`), and the API (`AttendeeDto.toModel`) would drop the value on every edit.
- The suggested fallback, "ignore a REPLY whose SEQUENCE is lower than the event's version-derived SEQUENCE", would drop valid answers. Storing any answer bumps the event version. So after attendee A's answer is applied, attendee B's answer to the same invitation (lower SEQUENCE) would be rejected.
- A rule of "DTSTAMP older than the event's updatedAt" would also lose valid answers that are delivered late.

Decision needed: store a per-attendee answered-at value, either as an optional `Attendee` JSON field (shared model change, owned by the calendar tasks) or as an itip table plus migration, or accept the current behavior.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests '*itip*' ktlintCheck` - Pass (ItipMessagesTests 26/26, ItipReplyProcessorTests 4/4, ktlint clean)
- `cd server && ./gradlew integrationTest --tests '*ItipGreenMail*'` - Pass (ItipGreenMailTests 32/32, GreenMail and the test database)
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.*'` - Pass

Full test suite:
- `cd server && ./gradlew check` - Not finished. It was still running after the 10-minute tool limit, so there is no result. The parent worker must run it, or `scripts/check.sh`.

Lint/type-check/build:
- `./gradlew ktlintFormat` then `ktlintCheck` - Pass; compilation of main and test - Pass

`.taskcheck`:
- Not run. It is left to the parent worker before `done`.

Remaining unresolved findings:
1 (CR-3 is partial: cross-message ordering needs a storage decision)

## Final status

Some findings require a product or architecture decision.
