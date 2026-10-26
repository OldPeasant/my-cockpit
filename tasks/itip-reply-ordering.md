---
title: Ignore stale iTIP REPLYs across messages (per-attendee answered-at)
status: in_progress
owner: felix/agent-1
deps: [itip-invitations]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar]
prio: 2
lease_until: 2026-10-26T17:24:00Z
extended: felix/agent-1
---
## Goal
An older iTIP REPLY (lower SEQUENCE, or same SEQUENCE and older DTSTAMP) that arrives after a newer one from the same attendee must not overwrite the attendee status (RFC 5546 §2.1.5; review finding CR-3 of itip-invitations). Needs a per-attendee answered-at/sequence value: decide between an optional field on the Attendee JSON (mind EventCommands equality, backup, AttendeeDto) or an itip table with a Flyway migration.

## Acceptance criteria
- [ ] A REPLY older than the last applied one for the same attendee/UID/RECURRENCE-ID is ignored; tests cover it
- [ ] The stored value survives backup/restore and is not lost on event edits via the API

## Notes
- ASSUMPTION (2026-10-26 17:16+0100 by felix/agent-1): Per-attendee REPLY stamp kept in a new table calendar_itip_reply (event_id=series master, recurrence_id_at/date, lower-case email, SEQUENCE, DTSTAMP) instead of a field on the Attendee JSON: event edits via the API replace attendees and EventContent.propagate compares attendee lists, so a JSON field would be lost or break propagation; the table is untouched by edits and cascades with the event
- ASSUMPTION (2026-10-26 17:16+0100 by felix/agent-1): A REPLY is ignored only when strictly older (lower SEQUENCE, or same SEQUENCE and older DTSTAMP; missing values count as oldest); an equal one is applied. The stamp advances on every accepted REPLY from an invited attendee, even when the status does not change. Ordering is per RECURRENCE-ID: a series REPLY and an occurrence REPLY do not order each other
- ASSUMPTION (2026-10-26 17:16+0100 by felix/agent-1): Backup: stamps of LOCAL events go to data/calendar_itip_replies.json (SPEC.md backup layout lists only the older calendar files); stamps of external events are not exported
- extended by felix/agent-1: Implementation done and committed (calendar_itip_reply table, ItipReplyProcessor.takeStamp, backup export/restore of LOCAL stamps, unit+Postgres+GreenMail tests; e3f63d49 adds test-writer's backup round-trip and GreenMail stale-reply tests). review.md done: 0 P0/P1, P2 CR-1 (series vs occurrence REPLY ordering, covered by recorded assumption -> decide fix or accept), 1 P3. Left: test-writer report tests.md (with coverage) on final code, decide CR-1/P3 (fixer or 'not required'), re-review if code changes, tasks.py verify 1+2, quality.json, done. NOTE: sh scripts/check.sh hit a 10-min timeout inside 'server: ktlint + tests' -- find out whether the new tests hang (GreenMail/Testcontainers in check?) before running done.
