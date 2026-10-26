# Code Review

Date: 2026-10-26

Task: itip-reply-ordering - Ignore stale iTIP REPLYs across messages (per-attendee answered-at)

Scope: re-review of the final revision, `git diff c6660d2271d0943e8ffd0371ac562e1352af5691..78743a5cc66e3a29db1e5ebc6874bbfc41e87789 -- . ':!tasks' ':!reviews'` (all task commits). Reviewed revision: 78743a5cc66e3a29db1e5ebc6874bbfc41e87789. Committed changes only; the working tree was clean. Focus on eb664399 (new test), the only code change since the previous review; `git diff 813d0f71..HEAD` outside `tasks/`/`reviews/` touches only `ItipReplyStampPersistenceTests.kt` (+30 lines).

Requirements:
- tasks/itip-reply-ordering.md (goal, 2 acceptance criteria, recorded assumptions; the 18:11 assumption supersedes the earlier ordering assumption)
- RFC 5546 §2.1.5 (message sequencing), RFC 5545 §3.8.7.4 (SEQUENCE default 0)
- SPEC.md §6.2 (iTIP), §10/§14 (backup/restore, round-trip test)
- AGENTS.md / docs/ARCHITECTURE.md conventions (module packages, Flyway naming, logging, lint)
- reviews/itip-reply-ordering/fixes.md, reviews/itip-reply-ordering/tests.md

Git range:
- Branch: main
- HEAD: 78743a5cc66e3a29db1e5ebc6874bbfc41e87789
- Base/start: c6660d2271d0943e8ffd0371ac562e1352af5691
- Commits reviewed: 7 (6bcde169 implementation, 91eca03d persistence tests, 9c5a63b5 backup/GreenMail tests, 813d0f71 CR-1/CR-2 fixes, eb664399 per-attendee persistence test; 2456d3b1 and 78743a5c are report-only)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Validation run by the reviewer:
- `cd server && ./gradlew test --tests '*ItipReplyStampPersistence*' --tests '*ItipReplyProcessorTests*' --tests '*ItipMessagesTests*'` (Postgres via Testcontainers) -> BUILD SUCCESSFUL; ItipReplyStampPersistenceTests 26 tests, ItipReplyProcessorTests 18, ItipMessagesTests 10, 0 failures/errors/skipped.
- `cd server && ./gradlew ktlintCheck` -> BUILD SUCCESSFUL.

New test (eb664399), `ItipReplyStampPersistenceTests` "each attendee has a stamp of its own, so an older REPLY of another attendee is still taken":
- Checks a property the acceptance criterion relies on ("for the same attendee"): Ben's first REPLY (SEQUENCE 1) is applied although Anna's taken REPLY has SEQUENCE 3 and a newer DTSTAMP; Ben's own older REPLY (SEQUENCE 0, newer DTSTAMP) is then ignored, which also shows that SEQUENCE ranks above DTSTAMP. It asserts the attendee statuses and both stored stamp rows against real Postgres (the derived query with null RECURRENCE-ID values and the lower-case email key).
- The test uses the existing `reply(..., attendee =)` and `answer(..., sender =)` helpers, the new `BEN` constant uses a fictitious `example.test` address, and the expected values match `ItipReplyProcessor.takeStamp`. Sound; no finding.

Rest of the task diff (unchanged since 813d0f71, rechecked):
- AC1: `takeStamp` runs after the invited check and in the same transaction as the attendee update; a REPLY strictly older (by `ItipReply.ORDER`, missing SEQUENCE = 0, missing DTSTAMP oldest) than the stamp for the same master/RECURRENCE-ID/lower-case email is ignored; an occurrence REPLY older than the series stamp is ignored; a series REPLY skips overrides with a newer occurrence stamp (`answeredLater`). This matches the 18:11 assumption. Covered by unit, Postgres and GreenMail tests.
- AC2: stamps live in `calendar_itip_reply` (FK with `on delete cascade`, check constraint, `nulls not distinct` unique index on the lookup key), so API edits that replace attendees do not touch them. Backup exports LOCAL-event stamps to `data/calendar_itip_replies.json` after events and restores them after all event rows. Round trip covered by CalendarBackupFixture/CalendarBackupContributorTests, and edits followed by late REPLYs by ItipGreenMailTests.
- Migration follows the `calendar/V<yyyyMMddHHmm>__calendar_<desc>.sql` rule and is a new file; the new log line is at DEBUG and carries only the event id; all changes stay in the `calendar` module, with no API contract change.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
