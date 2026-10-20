# Fix Report

Date: 2026-10-20

Task: p0-web-sse Web SSE client

Scope: current task, base 961dc340bb51de90ddf9dd1a2ca7650c1110281c..HEAD. Findings CR-1 and CR-2 come from reviews/p0-web-sse/review.md.

## Fixed findings

### CR-1 — Expired or revoked session: the stream retries every 30 s forever

Files changed:
- `web/src/app/core/events/change-events.service.ts`
- `web/src/app/core/events/change-events.service.spec.ts`

Problem:
EventSource cannot see HTTP status codes. After a session expired or was revoked, every reconnect got a 401, and the service kept retrying at the backoff cap because `AuthService.loggedIn` stayed true.

Fix:
`onerror` still closes the stream and schedules the backoff reconnect. It now also calls `auth.checkSession(true)`. On a 401, AuthService sets `loggedIn` to false, and the existing `loggedIn` effect runs `disconnect()`. That clears the pending reconnect timer and closes the stream, so no further attempts are made. A rejected `checkSession` (for example, network down) is swallowed, and the backoff keeps retrying. There is no extra race handling: logout always goes through `disconnect()`, which clears the timer and detaches the handlers. A reconnect therefore never survives a logout. The class doc comment now describes this behaviour.

Tests: the AuthService fake now provides `checkSession`. The default fake returns the current `loggedIn` value. New tests:
- "re-checks the session after a failed connection attempt": the service calls `checkSession(true)` and still reconnects after 1 s.
- "stops reconnecting when the session is gone": `checkSession` logs out and returns false. No new stream opens within 60 s.
- "keeps reconnecting with backoff when the session check fails": `checkSession` rejects. Reconnects still follow the 1 s, 2 s, 4 s backoff.

I removed the `checkSession` call temporarily and ran the suite: all three new tests failed. With the fix, they pass.

Validation:
- `cd web && npx ng test --watch=false --include src/app/core/events/change-events.service.spec.ts` — Pass (24 tests)

### CR-2 — The stale-source guard in `onerror` can never run

Files changed:
- `web/src/app/core/events/change-events.service.ts`
- `web/src/app/core/events/change-events.service.spec.ts`

Problem:
`if (this.source !== source) return;` could never be true. `close()` sets `onerror` to null before it drops the source, so a real EventSource never calls the handler on an old source.

Fix:
Removed the guard. The test "ignores errors of a stream it already closed" only reached the guard by calling a handler reference it had captured before `close()` detached it. A real EventSource cannot do that. I replaced the test with "detaches its handlers from a stream it closed". The new test asserts that after logout the old source is closed and `onopen` and `onerror` are null. This is the invariant that makes the guard unnecessary.

Validation:
- `cd web && npx ng test --watch=false --include src/app/core/events/change-events.service.spec.ts` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include src/app/core/events/change-events.service.spec.ts` — Pass, 24 tests

Full test suite:
- `cd web && npm run check` (lint with --max-warnings=0, api check, 19 test files with 271 tests, production build) — Pass, exit 0

Lint/type-check/build:
- Included in `npm run check` above — Pass

`.taskcheck`:
- `sh scripts/check.sh` — Pass, exit 0

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
