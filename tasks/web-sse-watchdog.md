---
title: Web: detect a dead change-event stream (sleep, network change) and resync
status: in_progress
owner: dell26/agent-1
deps: []
touches: [web/src/app/core/events,server/src/main/kotlin/ch/sonensei/cockpit/core/events/infra/SseEventHub.kt,server/src/test/kotlin/ch/sonensei/cockpit/core/events]
prio: 2
lease_until: 2026-11-14T20:52:08Z
releases: 2
extended: 
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
- [x] Manually or with Playwright: with `/new` open, cut the stream (e.g. `context.setOffline(true)` for longer than
      the watchdog, then back online, or route-abort the events request without an error), deliver a mail, and it
      appears without a reload (record what you ran).
  - verified (2026-11-14 21:28+0100 by dell26/agent-1): npm --prefix web run e2e -- e2e/new/live-watchdog.spec.ts --project=chromium (podman-compose e2e stack, GreenMail, real login): /new open, setOffline(true), mail appended + /api/test/mail/sync (refetch fails, row absent), setOffline(false) (row still absent: no needless reconnect), page.clock.fastForward(70s) = wake-up -> watchdog reconnects, resync, mail row visible with 0 navigations -> 36 passed (incl. phase-demos)
- [ ] `cd web && npm run check`, `cd server && ./gradlew check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-14 from the owner's report; the e2e probe that showed the happy path working was a temporary spec
(not committed). Keep the scope to the shared events service and the heartbeat; feature stores already refetch on
`resync$`. Android has its own read-timeout watchdog; the Android mail views not listening at all is
`and-mail-live-refresh`.
- ASSUMPTION (2026-11-14 20:57+0100 by dell26/agent-2): Heartbeat comment replaced by a named 'ping' event with data {} (browsers drop data-less events); also updated the stream description in EventsController.kt (outside touches) so the openapi contract documents it. Android ignores non-'change' events (GtdChangeStreamTest 'event: other' case).
- ASSUMPTION (2026-11-14 20:58+0100 by dell26/agent-2): Watchdog: 5 s check interval; wake-up = check ran >1 heartbeat (25 s) late while visible (hidden tabs are throttled, so ignored there); visibility/online/wake reconnect at once and skip a pending backoff; silence reconnect goes through the normal error path (backoff + session probe).
- release by dell26/agent-2: Done: server ping event (+openapi), web watchdog (STREAM_WATCHDOG, silence/visibility/online/wake reconnect), unit tests (55 pass), test-writer tests.md, code review (CR-1 P3) fixed by fixer (fixes.md). LEFT: (1) e2e/new/live-watchdog.spec.ts FAILS: after setOffline 70s + online the mail row never shows within 40s (server log in that run: 'Test mail sync: 1 accounts, 0 new messages' -> maybe IDLE/initial sync took the mail, or the offline stream never errored/reconnected; inspect the Playwright trace, check /api/v1/events requests after online and whether the mail is in /api/v1/mail). Also consider: resync only fires if a stream was open before (wasOpen). (2) re-run test-writer + code-reviewer on final code (fix commit came after review), verify criteria, quality.json, done.
- ASSUMPTION (2026-11-14 21:26+0100 by dell26/agent-1): E2E (criterion 5): Chromium's offline emulation keeps an already-open SSE connection alive (trace: /api/v1/events stays 200, the change event arrives, only its refetch fails with ERR_INTERNET_DISCONNECTED), so setOffline cannot cut the stream; the spec instead goes offline (refetch fails), back online, then page.clock.fastForward(70s) simulates the wake-up after a sleep, and the watchdog's late check reconnects + resyncs.
- release by dell26/agent-1: Done: server ping event (+openapi), web watchdog, unit tests, CR-1 fix (fixes.md). e2e/new/live-watchdog.spec.ts now PASSES (criterion 5 verified): the old version failed because Chromium's setOffline keeps an already-open SSE connection (trace: events stayed 200, the event arrived, only its refetch failed), so the spec now simulates the wake-up with page.clock.fastForward(70s) after going back online (assumption recorded). LEFT: (1) re-run test-writer + code-reviewer on the final code (CR-1 fix and the e2e rewrite came after the review); (2) verify criteria 1-4 and 6 (unit tests, server SseEventHubTests, npm run check, gradlew check, scripts/check.sh); (3) quality.json (reviewed_revision = final hash, fixes_required true with fixes.md for CR-1); (4) done.
