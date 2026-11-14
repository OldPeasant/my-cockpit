# Fix Report

Date: 2026-11-14

Task: web-sse-watchdog (web SSE stream watchdog with ping heartbeat)

Scope: current task, finding CR-1 from `reviews/web-sse-watchdog/review.md`

## Fixed findings

### CR-1 — Wake-up threshold equals the heartbeat interval, so a healthy stream can be reconnected needlessly at the edge

Files changed:
- `web/src/app/core/events/change-events.service.ts`
- `web/src/app/core/events/change-events.service.spec.ts`

Problem:
`wake()` kept the stream only while `Date.now() - lastActivity <= heartbeatMs` (exactly the server's
25 s ping period), so a `visibilitychange`/`online` event or a late watchdog check landing just before
a slightly late ping closed a healthy stream, reconnected it and made every store refetch via `resync$`.

Fix:
`wake()` now treats the stream as healthy while `Date.now() - lastActivity <= heartbeatMs + checkMs`
(30 s with the defaults). The `STREAM_WATCHDOG` token shape and defaults are unchanged. The wake-up
detection in `check()` (`late > heartbeatMs`) is left as is: it only decides that the device slept and
delegates to `wake()`, which now applies the margin. Doc comments on `StreamWatchdog.heartbeatMs`, the
class and `wake()` were updated.

Tests: new watchdog test "allows a late ping a margin before treating the stream as silent on wake-up"
(ping, then 26 s, then `visibilitychange` and `online` -> no reconnect, stream still open); it failed
before the fix and passes after. Existing tests that used exactly 30 s of silence to trigger (or, while
hidden, to not trigger) a wake-up reconnect now use 35 s, beyond the new 30 s margin, keeping their
intent; their assertions are unchanged.

Validation:
- `cd web && npx ng test --watch=false --include src/app/core/events` — Pass (55 tests)
- `cd web && npx eslint src/app/core/events && npx prettier --check src/app/core/events` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include src/app/core/events` — Pass, 55 passed (new test failed before the production change)

Full test suite:
- Not run (parent requested the focused run only; `.taskcheck` is run by the parent worker)

Lint/type-check/build:
- `npx eslint src/app/core/events` — Pass
- `npx prettier --check src/app/core/events` — Pass

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
