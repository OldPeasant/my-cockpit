---
title: Birthdays calendar
status: in_progress
owner: dell26/agent-1
deps: [contacts-api,cal-local-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/contacts/birthdays,server/src/test/kotlin/ch/sonensei/cockpit/contacts/birthdays]
prio: 2
lease_until: 2026-10-22T04:44:16Z
extended: 
releases: 1
---
## Goal
Virtual read-only birthdays calendar (SPEC.md §8.3).

## Acceptance criteria
- [x] Birthdays appear as yearly all-day occurrences in `GET /calendar/events` from a virtual calendar; Feb 29 handled
  - verified (2026-10-22 06:19+0200 by dell26/agent-1): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.*' (Testcontainers Postgres) at 218c8e72 -> 33 passed; BirthdayCalendarWebTests/EdgeCaseWebTests assert yearly all-day occurrences in GET /calendar/events from the read-only virtual Birthdays calendar, Feb 29 -> Feb 28 in non-leap years
- [x] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.*'` passes (Postgres via Testcontainers where persistence is involved)
  - verified (2026-10-22 06:19+0200 by dell26/agent-1): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.*' jacocoTestReport at 218c8e72 -> BUILD SUCCESSFUL, 33 tests (13 unit, 8 web, 12 edge-case; Postgres via Testcontainers), 0 failures

## Notes
- ASSUMPTION (2026-10-22 04:35+0200 by dell26/agent-2): Virtual calendars: the calendar module got a small SPI (calendar.api.VirtualCalendarProvider + CalendarService.ensureVirtualCalendar); the birthdays calendar is a stored read-only LOCAL calendar (externalId 'virtual:birthdays', so name/color/visibility/backup work as for any calendar) whose occurrences are computed from contacts, not stored. Touches calendar/api and OccurrenceExpander outside the task's touches.
- ASSUMPTION (2026-10-22 04:35+0200 by dell26/agent-2): The Birthdays calendar is created lazily once a contact has a birthday (after a contact change commits, and at startup), so users without birthdays get no empty calendar; it is never removed automatically.
- ASSUMPTION (2026-10-22 04:35+0200 by dell26/agent-2): Feb 29 birthdays fall on Feb 28 in non-leap years. Occurrences start the year after birth; title is '🎂 <displayName> (<age>)', age omitted above 130 (vCard placeholder years like 1604). eventId and seriesId are the contact id, occurrence is the date; occurrences are TRANSPARENT (free) and cannot be fetched/changed via /calendar/events/{id} (404).
- ASSUMPTION (2026-10-22 04:35+0200 by dell26/agent-2): No CALENDAR/EVENT change event is published when a contact's birthday changes; clients see changes on their next range fetch (CONTACT change events are published as before).
- ASSUMPTION (2026-10-22 05:10+0200 by dell26/agent-2): Concurrent first creation of a virtual calendar is serialized by a row lock on the LOCAL calendar source (CalendarSourceRepository.findForUpdate) instead of catching the unique-key violation, so no ERROR is logged; touches calendar/model outside the task's touches.
- ASSUMPTION (2026-10-22 05:10+0200 by dell26/agent-2): Client handling of birthday occurrences (web dialog currently 404s on them) is out of scope; follow-up task web-and-birthday-occurrences created, the OccurrenceResponse description documents the virtual case.
- extended by dell26/agent-2: Implementation, tests and fixes committed; birthdays suite passes (31 tests, Testcontainers). Left: last code change (advisory lock for first virtual calendar creation, 1e21e5e5 + test e61b8f64) came after the re-review, so re-run test-writer and code-reviewer on final code; record an assumption that the advisory lock replaces the row lock noted earlier; write quality.json; tasks.py verify #1/#2; run .taskcheck; done.
- ASSUMPTION (2026-10-22 05:35+0200 by dell26/agent-2): Supersedes the findForUpdate row-lock note: concurrent first creation of a virtual calendar (and of the LOCAL source with it) is serialized by pg_advisory_xact_lock(VIRTUAL_CALENDAR_LOCK) in CalendarService.ensureVirtualCalendar; findForUpdate was removed. CalendarService.create() still creates the LOCAL source without that lock (pre-existing); a user's first calendar created at the same instant as the first Birthdays calendar could yield two LOCAL sources - accepted as negligible for a single-user app.
- release by dell26/agent-2: Implementation, fixes and tests committed (HEAD 9cdc69c1); .taskcheck passes; birthdays suite passed earlier (31 tests, Testcontainers). Only finalization left: (1) 9cdc69c1 added two race tests (rollback, no-wait) after the last test-writer run, and review.md reviewed e36eaf61, so re-run test-writer (update tests.md with coverage) and code-reviewer on the final HEAD; (2) review CR-1 (create() takes the LOCAL source without VIRTUAL_CALENDAR_LOCK) is accepted via the 05:35 assumption - optionally take the lock in create() too; CR-2 (stale row-lock assumption) is already superseded by the 05:35 assumption; (3) write reviews/contacts-birthdays/quality.json; (4) run the birthdays suite, tasks.py verify #1/#2; (5) done.
