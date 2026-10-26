---
title: Ignore stale iTIP REPLYs across messages (per-attendee answered-at)
status: in_progress
owner: felix/agent-1
deps: [itip-invitations]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar]
prio: 2
lease_until: 2026-10-26T16:27:46Z
---
## Goal
An older iTIP REPLY (lower SEQUENCE, or same SEQUENCE and older DTSTAMP) that arrives after a newer one from the same attendee must not overwrite the attendee status (RFC 5546 §2.1.5; review finding CR-3 of itip-invitations). Needs a per-attendee answered-at/sequence value: decide between an optional field on the Attendee JSON (mind EventCommands equality, backup, AttendeeDto) or an itip table with a Flyway migration.

## Acceptance criteria
- [ ] A REPLY older than the last applied one for the same attendee/UID/RECURRENCE-ID is ignored; tests cover it
- [ ] The stored value survives backup/restore and is not lost on event edits via the API

## Notes
