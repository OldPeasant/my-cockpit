# Code Review

Date: 2026-11-14

Task: web-sse-watchdog - Web: detect a dead change-event stream (sleep, network change) and resync

Scope: task commits ee6903c3d and f0273eaf1 on base fd7cbec709075d6ac8641bcf2a600edd594709c7 (`git diff fd7cbec70..f0273eaf1`), as supplied by the parent worker. Reviewed from the current working-tree files, because shell access (git diff/log) was denied in this review session.

Requirements:
- tasks/web-sse-watchdog.md acceptance criteria and recorded assumptions
- AGENTS.md project notes (conventions, generated files, logging)
- Parent worker instructions (reconnect/resync semantics, listener cleanup, hidden-tab throttling, backoff and session probe interplay, Android compatibility)

Git range:
- Branch: main
- HEAD: f0273eaf1 per the parent worker (the session snapshot showed the same changes as 1db4068f0 and 434efb97f; the hashes could not be resolved here without shell access)
- Base/start: fd7cbec709075d6ac8641bcf2a600edd594709c7
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Files read in full:
- server/src/main/kotlin/ch/sonensei/cockpit/core/events/infra/SseEventHub.kt
- server/src/main/kotlin/ch/sonensei/cockpit/core/events/api/EventsController.kt
- web/src/app/core/events/change-events.service.ts
- web/src/app/core/events/change-events.service.spec.ts

Review limitations (not findings): shell access was denied, so I could not run the exact diff, list the server
test directory, search api/openapi.yaml, or open the Android GtdChangeStream sources. I did not independently
verify the server heartbeat test, the regenerated openapi.yaml or the Android `event: other` test. I took the
Android compatibility claim from the task's recorded assumption. The parent worker should confirm these three
with its own evidence.

What was verified:
- Server: the heartbeat is now a named `ping` event with data `{}` (SseEventHub.kt:102-111). Without a `data:` line,
  browsers do not dispatch an event, so the `{}` data is needed. It runs on the same single sender thread as
  broadcasts, and a write failure still drops the client. The controller description documents the event
  (EventsController.kt:28-29).
- Web watchdog: the watchdog starts with the first connect and stops only on disconnect (logout or destroy).
  The interval and the `visibilitychange`/`online` listeners are removed through the same stable `onWake`
  reference (change-events.service.ts:163-176). The test at spec:731-742 covers this with `vi.getTimerCount() === 0`.
- Silence goes through `fail()`, which closes the stream, starts the backoff and probes the session
  (service.ts:185, 155-160). `onopen` emits `resync$` because `wasOpen` stays true across reconnects. A wake
  reconnect cancels a pending backoff, resets `failures` and skips the probe; a later failure probes again.
  This matches the recorded assumption.
- Hidden tabs: when the tab is hidden, a late check is not treated as a wake-up (service.ts:184). `wake()` does
  nothing while the tab is hidden (service.ts:193). The silence check still applies, and EventSource network
  events keep updating `lastActivity` in a hidden tab.
- During a backoff, `check()` returns early (`source` is undefined) but still advances `lastCheck`, so a long
  backoff is not later mistaken for a wake-up. A connection that never opens is caught by the silence check,
  because `lastActivity` is set at connect (covered by spec:744-756).
- Change events, named `messages$` events and pings all count as activity. The listeners of closed sources are
  never called again, because the sources are closed.
- Tests cover each unit-test point of the criteria: a ping resets the watchdog; silence leads to a reconnect and
  `resync$`; `visibilitychange`/`online` after silence reconnects; a healthy stream is left alone; a wake-up is
  detected; logout cleanup; and a configurable token.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Wake-up threshold equals the heartbeat interval, so a healthy stream can be reconnected needlessly at the edge**
    - File: `web/src/app/core/events/change-events.service.ts:194` (also used at `:184`)
    - Category: Correctness
    - Problem: `wake()` keeps the stream only if `Date.now() - lastActivity <= heartbeatMs`, and `heartbeatMs` is
      exactly the server's ping period (25 s, SseEventHub `scheduleAtFixedRate`). Just before each ping arrives,
      the time since the last one is 25 s plus network and scheduling jitter. The ping also shares the single
      `sse-events` sender thread with change broadcasts, so a busy broadcast delays it further. A
      `visibilitychange`/`online` event, or a late check, that lands in that window treats a healthy stream as
      silent. The service then closes it, reconnects, and every store refetches through `resync$`.
    - Why it matters: the criterion says "A visible tab with a healthy stream does not reconnect needlessly". The
      window is small (milliseconds to seconds per 25 s period), but no margin is built in, and each false
      positive refetches all open views.
    - Suggested fix: compare against the heartbeat plus a margin (e.g. `heartbeatMs + checkMs`, or a separate
      grace value in `STREAM_WATCHDOG`). Add a test where `visibilitychange` fires 25-26 s after the last ping and
      the stream is kept.

## Review conclusion

No material issues found. One low-priority robustness improvement (CR-1) is optional. The parent worker should
confirm the unverified items listed under "Review limitations" (server ping test, regenerated openapi.yaml,
Android ignoring `ping`) with its own evidence.
