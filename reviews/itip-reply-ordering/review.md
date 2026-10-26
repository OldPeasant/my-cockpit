# Code Review

Date: 2026-10-26

Task: itip-reply-ordering - Ignore stale iTIP REPLYs across messages (per-attendee answered-at)

Scope: re-review of the final revision, `git diff facc7cdde33eab59350c45fc7717a5e90d373e76..985262497c01bd79821d5b4ea6f302e86131aaa2` (all task commits). Revision reviewed: 985262497c01bd79821d5b4ea6f302e86131aaa2. Committed changes only; the working tree was clean. Gradle was not run (a full test run was in progress in `server/`); the review is based on reading the code, tests and the fixer's recorded validation.

Requirements:
- tasks/itip-reply-ordering.md (goal, 2 acceptance criteria, recorded assumptions; the 18:11 assumption supersedes the earlier ordering assumption)
- RFC 5546 §2.1.5 (message sequencing), RFC 5545 §3.8.7.4 (SEQUENCE default 0)
- SPEC.md §6.2 (iTIP), §10/§14 (backup/restore, round-trip test)
- AGENTS.md / docs/ARCHITECTURE.md conventions (module packages, Flyway naming, logging, lint)
- reviews/itip-reply-ordering/fixes.md (fixes for the earlier review's CR-1/CR-2)

Git range:
- Branch: main
- HEAD: 985262497c01bd79821d5b4ea6f302e86131aaa2
- Base/start: facc7cdde33eab59350c45fc7717a5e90d373e76
- Commits reviewed: 4 (c834af71 implementation, 9275bb92 persistence tests, 0382889b backup/GreenMail tests, 98526249 CR-1/CR-2 fixes)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Earlier findings (review of revision 5b926ae5):
- CR-1 (P2, series vs occurrence REPLY ordering): resolved. `ItipReplyProcessor.takeStamp` now also compares an occurrence REPLY with the attendee's series stamp and ignores it when strictly older; for a series REPLY, `answeredLater` skips overrides whose own occurrence stamp is strictly newer, while the master and other overrides are updated and the series stamp advances. Traced the interleavings (occurrence then older/newer series, series then older/equal/newer occurrence, late older REPLYs after both) and each ends with the newest answer per override. Covered by ItipReplyProcessorTests "an occurrence REPLY older than the last series REPLY is ignored..." and "a series REPLY leaves an override alone whose own answer is newer...", and end to end by ItipGreenMailTests "older series and occurrence REPLYs stay ignored after the series and its override are edited via the API".
- CR-2 (P3, missing SEQUENCE ordered below 0): resolved. `ItipReply.ORDER` is `compareBy { it.sequence ?: 0 }.thenBy(nullsFirst()) { it.stamp }`; the stored raw SEQUENCE is compared through the same comparator (`isOlder` copies the stamp's values into the reply). Covered in ItipMessagesTests, ItipReplyProcessorTests "a REPLY without SEQUENCE counts as SEQUENCE 0 across messages" and the Postgres test in ItipReplyStampPersistenceTests.

Checked and found sound (no finding):
- AC1: a REPLY strictly older than the last taken one for the same master/RECURRENCE-ID/lower-case email is ignored (`takeStamp`, called only after the invited check, inside the same transaction as the update, so an optimistic-locking retry rolls the stamp back too). Equal REPLYs are applied, matching the recorded assumption. Unit, Postgres (derived query with null RECURRENCE-ID, timed and all-day occurrences, case-insensitive address, retry behavior) and GreenMail tests cover it.
- AC2: stamps live in `calendar_itip_reply`, untouched by API edits that replace attendees; GreenMail tests PATCH the event (and series + override) and show late REPLYs stay ignored. Backup exports stamps of LOCAL events only (`data/calendar_itip_replies.json`) after all events, and restore inserts them after every event row is restored (all events, trashed ones included, are exported, so the FK holds; restore truncates all tables first). Round trip covered by CalendarBackupFixture and CalendarBackupContributorTests, including a REPLY processed after restore.
- Migration `calendar/V202610261600__calendar_itip_reply.sql`: new file following the naming rule, FK `on delete cascade`, check constraint, `nulls not distinct` unique index matching the lookup key (Postgres 16 everywhere). Entity columns match the schema (`ddl-auto=validate`).
- Concurrency: REPLYs are applied by a single-thread executor and `process` has no other production caller, so concurrent inserts of the same stamp cannot occur.
- Logging: the new message is at DEBUG and carries only the event id; no addresses or calendar content are logged.
- Module boundaries: all changes stay in the `calendar` module; no API contract change, so no OpenAPI regeneration is needed. The stamp is internal bookkeeping, not a user-facing entity, so the link/search requirement of SPEC.md §14 does not apply; backup coverage is present.

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
