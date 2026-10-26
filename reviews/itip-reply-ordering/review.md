# Code Review

Date: 2026-10-26

Task: itip-reply-ordering - Ignore stale iTIP REPLYs across messages (per-attendee answered-at)

Scope: committed changes of the task, `git diff afa14176..5b926ae5` (commits 6e4b27bc implementation, 5b926ae5 persistence tests). Uncommitted working-tree changes by the concurrent test-writer were not reviewed.

Requirements:
- tasks/itip-reply-ordering.md (goal, 2 acceptance criteria, 3 recorded assumptions)
- SPEC.md §6.2 (iTIP), §14 (backup/restore), as referenced by the parent
- docs/ARCHITECTURE.md / AGENTS.md conventions (module boundaries, Flyway naming, backup round trip, logging)
- RFC 5546 §2.1.5 (message sequencing)

Git range:
- Branch: main
- HEAD: 5b926ae5753579ceebb437ef8db9baec911cabfe
- Base/start: afa14176152996c2186ccdac2c4f5372f2d278eb
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 1 P2, 1 P3

Checked and found sound (no finding):
- AC1: `ItipReplyProcessor.takeStamp` (ItipReplyProcessor.kt:162-180) ignores a REPLY that is strictly older than the stored stamp for the same master/RECURRENCE-ID/lower-case email, and is called only after the "invited" check, so strangers and occurrences without override store no stamp. Covered by unit tests (ItipReplyProcessorTests), Postgres tests (ItipReplyStampPersistenceTests: case-insensitive address, null/non-null RECURRENCE-ID lookup through the derived query, timed and all-day occurrences) and a GreenMail end-to-end test.
- AC2: stamps live in their own table, so API edits (which replace the attendees JSON) cannot drop them; ItipGreenMailTests covers an API PATCH followed by late REPLYs. Backup exports stamps of LOCAL events only and restores them after all events (FK order holds; trashed events are exported too). Export runs in BackupService's single repeatable-read snapshot, so stamps and events are consistent. A backup without the file restores fine (`orEmpty()`). Round trip covered in CalendarBackupContributorTests and CalendarBackupFixture.
- Concurrency: `takeStamp` runs inside the same `tx.execute` as the event update, so an optimistic-locking retry rolls the stamp back too (tested). REPLYs are processed by a single-thread executor and `process` has no other production caller, so two concurrent inserts of the same stamp (unique index violation, not retried) cannot happen in this design.
- Migration `V202610261600__calendar_itip_reply.sql` follows the naming rule, has `on delete cascade` to the event, a check constraint and a `nulls not distinct` unique index matching the lookup key. Logging stays at DEBUG with only the event id.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A late older occurrence REPLY still overwrites the status a newer series REPLY set on that override (and the reverse)**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipReplyProcessor.kt:154`, `:166`
    - Category: Correctness
    - Problem: The stamp key includes the RECURRENCE-ID, and a series REPLY (no RECURRENCE-ID) updates the master and all its overrides (`targets` at line 147). A stale occurrence REPLY (lower SEQUENCE, older DTSTAMP) arriving after a newer series REPLY is compared only with the occurrence stamp, which does not exist, so it is applied and overwrites the override's status that the newer series answer set. The reverse also holds: a late older series REPLY overwrites an override that a newer occurrence REPLY answered. `ItipReplyProcessorTests` "REPLYs are ordered per occurrence, per event and per attendee" enshrines this: series REPLY SEQUENCE 5 accepted, then an occurrence REPLY with SEQUENCE 1 and an older DTSTAMP changes the override (`shouldContainExactly listOf(override.id)`).
    - Why it matters: The task goal says an older REPLY arriving after a newer one from the same attendee must not overwrite the attendee status (RFC 5546 §2.1.5). The acceptance criterion is worded per attendee/UID/RECURRENCE-ID and the behavior is a recorded assumption, so this is not an AC failure, but the override status can still be regressed by exactly the late-delivery scenario the task is meant to fix.
    - Suggested fix: Either (a) when applying an occurrence REPLY, also ignore it if it is older than the series stamp, and when applying a series REPLY, skip overrides whose occurrence stamp is newer; or (b) keep the current behavior and make sure the assumption is surfaced for human confirmation (it already is in the task notes) and adjust the test name/comment to state the limitation explicitly.

## P3 Low

- [ ] **CR-2 | A REPLY without SEQUENCE is ordered below SEQUENCE:0, so a newer answer from a client that omits SEQUENCE is ignored permanently**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipMessages.kt:52-53` (used across messages from `ItipReplyProcessor.kt:172`)
    - Category: Correctness
    - Problem: `ItipReply.ORDER` uses `nullsFirst()` for SEQUENCE, so a missing SEQUENCE is older than SEQUENCE 0 whatever the DTSTAMP (ItipReplyStampPersistenceTests "A missing SEQUENCE counts as oldest, whatever the DTSTAMP"). In iCalendar a component without SEQUENCE is conventionally at sequence 0, and the cockpit's first invitation goes out with SEQUENCE = event version. If an attendee first answers with `SEQUENCE:0` and later answers from a client that omits SEQUENCE, the later (newer DTSTAMP) answer is now ignored across messages, and stays ignored until the organizer changes the event. Before this task the effect was limited to answers inside one message.
    - Why it matters: Legitimate status changes are silently dropped (DEBUG log only). Edge case, and it matches the recorded assumption "missing values count as oldest", so the human reviewing assumptions should see it.
    - Suggested fix: Treat a missing SEQUENCE as 0 in `ORDER` (for example `compareBy { it.sequence ?: 0 }` then DTSTAMP with nulls first), and adjust the two tests that assert the current ordering; or keep it and note the trade-off in the assumption.

## Review conclusion

No material issues found. The acceptance criteria are implemented and tested; CR-1 and CR-2 are behavior choices recorded as assumptions that a human should confirm or the fixer may tighten.
