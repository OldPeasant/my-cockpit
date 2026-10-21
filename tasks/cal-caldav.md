---
title: CalDAV adapter
status: in_progress
owner: dell26/agent-2
deps: [cal-sync-engine]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/caldav,server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav]
prio: 2
lease_until: 2026-10-21T15:30:59Z
---
## Goal
CalDAV sources (SPEC.md §6.2).

## Acceptance criteria
- [x] Discovery (principal, calendar-home-set), sync-collection REPORT with fallback to ctag/etag; PUT/DELETE with If-Match
  - verified (2026-10-21 17:06+0200 by dell26/agent-2): cd server && ./gradlew test --tests '*caldav*' -> CalDavCalendarAdapterTests 44, CalDavEventMappingTests 42, CalDavClientTests 15, ICalendarTests 15, 0 failures (WireMock: principal/calendar-home-set discovery, sync-collection incl. 507/refused token, ctag/etag fallback, PUT/DELETE with If-Match/If-None-Match and 412); also exercised against Radicale in integrationTest (discovery, sync-collection, etag fallback, If-Match writes) -> 10/10 passed
- [x] `external`-tagged tests against a Radicale Testcontainer: create in cockpit → on server; change on server → in cockpit after sync; conflict (`./gradlew integrationTest --tests '*CalDav*'`)
  - verified (2026-10-21 17:06+0200 by dell26/agent-2): cd server && ./gradlew integrationTest --tests '*CalDav*' --rerun (Docker, Radicale Testcontainer, @ExternalTest CalDavRadicaleSyncTests) on HEAD 95486262 production code -> 10 tests, 0 failures, 2026-10-21T14:39Z: create in cockpit -> on server with server etag; server change/delete -> in cockpit after sync; edit/delete of server-changed event -> 409 conflict

## Notes
- ASSUMPTION (2026-10-21 15:24+0200 by dell26/agent-2): CalDAV source config is {"url": ..., "username": ...}, password in CalendarSource.credentials; Basic auth only, sent only to the URL's origin (redirects to other hosts refused). No SSRF block on private addresses: self-hosted CalDAV on the LAN is a normal use case.
- ASSUMPTION (2026-10-21 15:24+0200 by dell26/agent-2): Own minimal iCalendar content-line model (caldav/ICalendar.kt) instead of ical4j for CalDAV resources: writes change VEVENTs in place so unknown properties survive; VTIMEZONEs are generated from java.time rules (avoids ical4j's tz-update network fetches).
- ASSUMPTION (2026-10-21 15:24+0200 by dell26/agent-2): CalDAV cursor is JSON holding mode (sync/etag), sync token or ctag, and href->(etag, UID) per resource, because removals only name the href. Unknown TZIDs fall back to Windows names, X-LIC-LOCATION, then the VTIMEZONE's standard offset; floating times are read as UTC; RDATE is ignored and dropped on write.
- ASSUMPTION (2026-10-21 15:25+0200 by dell26/agent-2): Limitation: an override removed on the server without an EXDATE (occurrence back to normal) is not noticed by the engine until a full listing; resources with overrides but no master are skipped.
- ASSUMPTION (2026-10-21 16:10+0200 by dell26/agent-2): Calendar sync tests (FakeCalendarAdapterConfiguration) now remove the application's CalendarProviderRegistry bean definition, because the real CalDavCalendarAdapter @Component and the CALDAV fake collided; also fixed the flaky ClockConfigurationTests (microsecond clock vs nanosecond Instant.now()). Both outside the task's touches but required for .taskcheck.
- ASSUMPTION (2026-10-21 17:06+0200 by dell26/agent-2): CalDavClient follows a 303 redirect (same origin only) with the original method/body instead of switching to GET; accepted as P3 since CalDAV servers don't answer writes with 303 (review CR-1)
