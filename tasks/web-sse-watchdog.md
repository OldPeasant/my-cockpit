---
title: Web: detect a dead change-event stream (sleep, network change) and resync
status: in_progress
owner: dell26/agent-2
deps: []
touches: [web/src/app/core/events,server/src/main/kotlin/ch/sonensei/cockpit/core/events/infra/SseEventHub.kt,server/src/test/kotlin/ch/sonensei/cockpit/core/events]
prio: 2
lease_until: 2026-11-14T20:15:00Z
---
## Goal
Owner report (2026-11-14): in the web app a newly arrived mail sometimes does not appear in the open view (New, Mail
list) until the page is left or reloaded.

Diagnosis (interactive session, 2026-11-14): the live path itself works. A Playwright probe against the e2e stack
(GreenMail, IDLE push, no manual sync) showed a delivered mail without a reload after ~0.85 s on `/new`,
`/new?source=mail` and `/mail`. Caddy's `encode zstd gzip` also streams each SSE event at once (checked with
caddy:2 v2.11.7). The gap is in keeping the connection alive:
- `ChangeEventsService` (`web/src/app/core/events/change-events.service.ts`) only reconnects on `EventSource.onerror`.
- The server's heartbeat (`SseEventHub.sendHeartbeat`, every 25 s) is an SSE *comment*, which `EventSource` never
  passes to JavaScript, so the client cannot notice that the stream went silent.
- After a laptop sleep, a Wi-Fi/VPN change or a connection a middlebox dropped silently (half-open TCP),
  `EventSource` can stay "open" on a dead connection without firing `error`. Then no change events and no `resync$`
  arrive, and every store (mail, GTD, calendar, ...) shows stale data until a reload. Android does not have this
  problem: its stream client gives up after 2x the heartbeat interval plus a margin of silence.

## Acceptance criteria
- [ ] The server sends the heartbeat so that the browser sees it, e.g. as a named SSE event `ping` (no data, or `{}`)
      instead of, or in addition to, the comment. Existing clients ignore unknown event names (the Android
      `GtdChangeStream` maps only `change`; confirm it still ignores `ping`, or keep the comment for it). If the event
      is part of the documented stream contract, regenerate `api/openapi.yaml`.
- [ ] `ChangeEventsService` runs a watchdog: when nothing (no event, no ping) arrived for more than ~2.5 heartbeat
      intervals (configurable token, default about 65 s), it closes the `EventSource` and reconnects the way an
      error does (backoff, session check), so `resync$` emits and the stores refetch.
- [ ] When the page becomes visible again (`visibilitychange`) or the browser goes back `online`, and the stream was
      silent for longer than one heartbeat interval, or a wake-up is detected (a timer fired much later than
      scheduled), the service reconnects at once and stores resync. A visible tab with a healthy stream does not
      reconnect needlessly.
- [ ] Unit tests (fake `EVENT_SOURCE_FACTORY`, fake timers): ping resets the watchdog; silence triggers a reconnect
      and `resync$`; `visibilitychange`/`online` after silence reconnects; logout stops the watchdog and listeners;
      server test: the heartbeat is sent as the named event.
- [ ] Manually or with Playwright: with `/new` open, cut the stream (e.g. `context.setOffline(true)` for longer than
      the watchdog, then back online, or route-abort the events request without an error), deliver a mail, and it
      appears without a reload (record what you ran).
- [ ] `cd web && npm run check`, `cd server && ./gradlew check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-14 from the owner's report; the e2e probe that showed the happy path working was a temporary spec
(not committed). Keep the scope to the shared events service and the heartbeat; feature stores already refetch on
`resync$`. Android has its own read-timeout watchdog; the Android mail views not listening at all is
`and-mail-live-refresh`.
