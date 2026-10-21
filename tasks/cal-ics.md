---
title: ICS feed adapter
status: in_progress
owner: dell26/agent-3
deps: [cal-sync-engine]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics,server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics]
prio: 2
lease_until: 2026-10-21T15:51:08Z
extended: dell26/agent-3
---
## Goal
Read-only ICS subscriptions (SPEC.md §6.1).

## Acceptance criteria
- [ ] Fetch with ETag/Last-Modified, parse, upsert/delete; calendars read-only; SSRF protection (no private/loopback addresses unless allowed in config)
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.ics.*'` passes (Postgres via Testcontainers where persistence is involved) with WireMock feeds

## Notes
- ASSUMPTION (2026-10-21 16:02+0200 by dell26/agent-3): ICS source config is {"url": "<http(s)|webcal URL>"}; webcal(s):// is fetched as https://. One read-only calendar per source (external id 'feed') named like the source; poll interval 15 min (cockpit.calendar.ics.poll-interval), SPEC gives none.
- ASSUMPTION (2026-10-21 16:02+0200 by dell26/agent-3): SSRF: reuses mail.api.PublicAddresses (public unicast only) for the URL host and every redirect; config escape hatches cockpit.calendar.ics.allow-private-networks (all hosts) and allowed-private-hosts (named hosts). JDK HttpClient cannot be pinned to the checked address, so DNS rebinding is only narrowed by the JVM name cache, not closed. URLs with userinfo are refused; any port allowed. PublicAddresses should move to core when a third user appears.
- ASSUMPTION (2026-10-21 16:02+0200 by dell26/agent-3): Own tolerant ICS content-line parser instead of ical4j's builder (ical4j resolves TZIDs via its registry, may go to the network, and rejects malformed feeds). Feed VTIMEZONE rules ignored: TZID resolved as IANA, Area/City suffix, Windows name, X-LIC-LOCATION, else X-WR-TIMEZONE/UTC. RDATE unsupported. Per-event etag = SHA-256 of mapped content (DTSTAMP excluded) so unchanged events are not rewritten; events without UID get a UID hashed from start+title.
- ASSUMPTION (2026-10-21 16:49+0200 by dell26/agent-3): CR-5: if any VEVENT of a feed cannot be read, the ICS adapter returns complete=false (readable events upserted, nothing deleted); the skip count is only logged, not shown in lastError, because that needs a sync-engine change outside this task
- extended by dell26/agent-3: Implementation, fixes and 3 review rounds done (review.md round 3: 0 findings; fixes.md). ICS tests pass (./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.ics.*'). Left: reviews/cal-ics/tests.md was never committed -> run test-writer once more on final code (d0a9995f added SSRF/parser tests after round-3 review), then code-reviewer re-run (test code changed), write quality.json, tasks.py verify 1+2, done. Note: scripts/check.sh failed once on core ClockConfigurationTests (nanos vs micros truncation flake, outside this task).
