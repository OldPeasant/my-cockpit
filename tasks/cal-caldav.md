---
title: CalDAV adapter
status: in_progress
owner: dell26/agent-2
deps: [cal-sync-engine]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/caldav,server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav]
prio: 2
lease_until: 2026-10-21T14:55:45Z
---
## Goal
CalDAV sources (SPEC.md §6.2).

## Acceptance criteria
- [ ] Discovery (principal, calendar-home-set), sync-collection REPORT with fallback to ctag/etag; PUT/DELETE with If-Match
- [ ] `external`-tagged tests against a Radicale Testcontainer: create in cockpit → on server; change on server → in cockpit after sync; conflict (`./gradlew integrationTest --tests '*CalDav*'`)

## Notes
- ASSUMPTION (2026-10-21 15:24+0200 by dell26/agent-2): CalDAV source config is {"url": ..., "username": ...}, password in CalendarSource.credentials; Basic auth only, sent only to the URL's origin (redirects to other hosts refused). No SSRF block on private addresses: self-hosted CalDAV on the LAN is a normal use case.
- ASSUMPTION (2026-10-21 15:24+0200 by dell26/agent-2): Own minimal iCalendar content-line model (caldav/ICalendar.kt) instead of ical4j for CalDAV resources: writes change VEVENTs in place so unknown properties survive; VTIMEZONEs are generated from java.time rules (avoids ical4j's tz-update network fetches).
- ASSUMPTION (2026-10-21 15:24+0200 by dell26/agent-2): CalDAV cursor is JSON holding mode (sync/etag), sync token or ctag, and href->(etag, UID) per resource, because removals only name the href. Unknown TZIDs fall back to Windows names, X-LIC-LOCATION, then the VTIMEZONE's standard offset; floating times are read as UTC; RDATE is ignored and dropped on write.
- ASSUMPTION (2026-10-21 15:25+0200 by dell26/agent-2): Limitation: an override removed on the server without an EXDATE (occurrence back to normal) is not noticed by the engine until a full listing; resources with overrides but no master are skipped.
- ASSUMPTION (2026-10-21 16:10+0200 by dell26/agent-2): Calendar sync tests (FakeCalendarAdapterConfiguration) now remove the application's CalendarProviderRegistry bean definition, because the real CalDavCalendarAdapter @Component and the CALDAV fake collided; also fixed the flaky ClockConfigurationTests (microsecond clock vs nanosecond Instant.now()). Both outside the task's touches but required for .taskcheck.
