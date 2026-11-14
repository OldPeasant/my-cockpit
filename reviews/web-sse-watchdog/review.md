# Code Review

Date: 2026-11-14

Task: web-sse-watchdog - Web: detect a dead change-event stream (sleep, network change) and resync

Scope: re-review of the final task code, `git diff 88e8f67da HEAD -- . ':!tasks' ':!reviews'` (task commits 1f76628ad..5c77f6835, including the CR-1 fix 9e8677237 and the e2e rewrite 5c77f6835). Committed changes only; an uncommitted edit of `web/src/app/core/events/change-events.service.spec.ts` (+35 lines, test-writer work in progress) was not reviewed.

Requirements:
- tasks/web-sse-watchdog.md acceptance criteria and recorded assumptions
- SPEC.md (SSE change notifications, section on the events channel)
- docs/ARCHITECTURE.md and AGENTS.md project notes (generated files, logging, conventions)
- Parent worker instructions (re-review, confirm CR-1 resolved)

Git range:
- Branch: main
- HEAD: 9fa283a8b8df95d824589ed52e501eeaf947869c
- Base/start: 88e8f67da645d7574f4e97bf1059d0ad61dc14ee
- Commits reviewed: 9 (7 with code changes; the others are board/report commits and the merge of the wip branch)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Files read in full: `SseEventHub.kt`, `EventsController.kt` (changed part), `SseEventHubTests.kt` (changed parts), `change-events.service.ts`, the watchdog section of `change-events.service.spec.ts`, `web/e2e/new/live-watchdog.spec.ts`, the `api/openapi.yaml` hunk, `web/src/app/core/events/index.ts`; plus the Android `GtdChangeStream.kt` event mapping and its test for compatibility.

## Earlier finding CR-1 (previous review): resolved

`wake()` now keeps a stream healthy while `Date.now() - lastActivity <= heartbeatMs + checkMs` (30 s with the defaults, `change-events.service.ts:199-200`). Tests cover the margin: a 26 s old ping on `visibilitychange`/`online` keeps the stream (spec "allows a late ping a margin..."), exactly 30 s keeps it and 30 s + 1 ms reconnects (spec "treats a stream as silent on wake-up only after more than a heartbeat plus a check interval"). The fix report matches the code.

## What was verified

- Criterion 1 (server ping): `sendHeartbeat` sends `event:ping` with data `{}` through the same `trySend` path as change events, so a dead client is still dropped (`SseEventHub.kt:102-111`). The `{}` data is needed because browsers dispatch no event without a `data:` line. The comment heartbeat is removed. The Android `GtdChangeStream.toSignal` returns null for any event not named `change` (line 133), and `GtdChangeStreamTest` covers an `event: other` case, so Android ignores `ping`. The only other consumer of `/api/v1/events` is the web service. The controller description and the regenerated `api/openapi.yaml` say the same thing, and the generated web client has no copy of the text. Server tests assert the exact wire form `event:ping\ndata:{}\n\n` and that no `:heartbeat` comment remains.
- Criterion 2 (silence watchdog): `STREAM_WATCHDOG` token (25 s / 65 s / 5 s defaults, configurable). `check()` calls `fail()` after the silence limit. `fail()` is the same path as `onerror`: close, backoff, session probe. `wasOpen` stays true, so the next `onopen` emits `resync$`. A connection that never opens is also caught, because `lastActivity` is set at connect.
- Criterion 3 (visibility/online/wake-up): `wake()` does nothing while the tab is hidden. It keeps a healthy stream, and otherwise cancels a pending backoff and reconnects at once. A late check is not taken as a wake-up in hidden tabs (timer throttling). `check()` returns early during a backoff but still advances `lastCheck`, so a long backoff is not later read as a sleep. Pings, change events and `messages$` events all count as activity.
- Cleanup: `stopWatchdog()` clears the interval and removes both listeners through the same `onWake` reference. `disconnect()` (logout, destroy) calls it. The logout test checks that `vi.getTimerCount()` is 0.
- Criterion 4 (unit tests): every listed case has a test: a ping resets the watchdog; silence leads to a reconnect, a session probe and `resync$`; `visibilitychange`/`online` after silence reconnect; a healthy stream is left alone; a wake-up is detected with a clock jump; logout stops everything; the token can be configured. There is also a server test for the named event.
- Criterion 5 (e2e): the spec runs against the real stack (GreenMail, real login, test sync hook) and checks that the page did not navigate. As the recorded assumption says, Chromium's offline emulation does not cut the open SSE connection. The spec therefore shows the stale list (the refetch fails while offline) and then recovery through the wake-up path (`page.clock.fastForward`). It does not run the silent-stream path end to end; that path is unit-tested. The recorded assumption flags this gap for humans, so it is not a code finding.
- Security and logging: nothing sensitive is logged. The ping carries no data. Authorization and the stream timeout are unchanged.
- Design notes, recorded as assumptions and not defects: a wake-up resets the backoff counter and skips the session probe. If the server stays down, the next `onerror` probes the session again and starts the backoff from 1 s.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | E2E comments attribute the recovery to the 65 s silence limit, but the wake-up path triggers it**
    - File: `web/e2e/new/live-watchdog.spec.ts:8` and `web/e2e/new/live-watchdog.spec.ts:61`
    - Category: Quality
    - Problem: The header says "the page clock jumps past the watchdog's silence limit, so its check fires far too late". The step comment says "the watchdog's check runs 70 s late (longer than the 65 s silence limit)". In `check()` (`change-events.service.ts:188`), a check that runs more than `heartbeatMs` (25 s) late in a visible tab goes to `wake()`. `wake()` then reconnects because the last activity is older than 30 s. The 65 s `silenceMs` branch is never reached in this scenario, so a jump of about 35 s would already reconnect.
    - Why it matters: Readers may think the e2e test covers the silence (no ping) reconnect. It covers the wake-up reconnect. That makes it harder to see which path has end-to-end coverage, and the gap is already the subject of a recorded assumption.
    - Suggested fix: Reword both comments to say the jump is a wake-up: a check far later than one heartbeat, with the stream silent for longer than a heartbeat plus a check interval. Optional: keep 70 s, but do not tie it to the silence limit.

## Review conclusion

No material issues found. The earlier CR-1 is resolved, and every acceptance criterion is met by the committed code and tests. The one new finding is a P3 comment-accuracy issue in the e2e spec and is optional. The uncommitted spec edit in the working tree was outside this review. If it is committed, it changes only test code, and the parent worker should decide whether that needs a delta review.
