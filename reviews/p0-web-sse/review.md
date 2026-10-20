# Code Review

Date: 2026-10-20

Task: p0-web-sse Web SSE client

Scope: committed changes of the task, `git diff 961dc340..a28e79c -- web/` (web/src/app/core/events/change-events.service.ts, index.ts, change-events.service.spec.ts). An uncommitted change to the spec file I saw in the working tree (from the concurrent test-writer) is out of scope.

Requirements:
- tasks/p0-web-sse.md acceptance criteria and the three recorded assumptions (lazy connect via AuthService.loggedIn effect; 1 s doubling to 30 s backoff, reset on open; resync$ on every reopen within one login; malformed data ignored)
- SPEC.md §2 (SSE `/api/v1/events`), §4.2, frontend section ("SSE service dispatches change events to invalidate/refresh feature stores")
- docs/ARCHITECTURE.md, AGENTS.md project notes, docs/QUALITY.md
- Server contract: server/src/main/kotlin/ch/sonensei/cockpit/core/events (event name `change`, ChangeEvent JSON data, `:connected`/heartbeat comments, stream ends after 15 min, no replay, 401 when not logged in)
- Generated client model web/src/app/api/generated/models/change-event.ts

Git range:
- Branch: main
- HEAD: a28e79cc7bed9d73056cd530e625d2c511f3e818
- Base/start: 961dc340bb51de90ddf9dd1a2ca7650c1110281c
- Commits reviewed: 1 (a28e79c)

Commands run:
- `git diff --stat 961dc340..a28e79c`: 3 files, +352 lines
- `cd web && npm run test:ci`: 19 files, 257 tests passed
- `cd web && npx eslint src/app/core/events`: no output (clean)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

Acceptance criteria check:
1. Connects to `/api/v1/events` after login, reconnects with backoff, exposes `events$(type)`: met. A root effect follows `AuthService.loggedIn` and opens or closes the stream. The service handles reconnects itself: 1 s doubling up to a 30 s cap, reset after an open, and the timer is cleared on logout. `events$(type)` filters on `ChangeEvent.type`, which matches the server's String `type` and the event name `change`. Note: the connection starts only once something injects the service. No store injects it yet. This was recorded as an assumption and does not need a code change in this task.
2. Resync signal on reconnect: met. `resync$` emits on every reopen within one login, which covers missed events because the server does not replay them. It does not emit on the first connect, and it resets after logout.
3. Vitest tests: met. The tests cover dispatch by type, malformed data, exponential backoff with cap using fake timers, backoff reset, resync on reconnect only, no resync on a new login, stop on logout and close on destroy. `npm run test:ci` and the lint ran green here. I did not run the full `npm run check` (api:check + ng build). The parent should record that evidence.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Expired or revoked session: the stream retries every 30 s forever**
    - File: `web/src/app/core/events/change-events.service.ts:88`
    - Category: Correctness
    - Problem: EventSource cannot see HTTP status codes. When the session expires or is revoked, every reconnect gets a 401 and fires `onerror`, so the service keeps retrying at the 30 s cap. `AuthService.loggedIn` stays `true` because only `checkSession`, `login` and `logout` change it. Nothing in this service tells AuthService that the session is gone.
    - Why it matters: A tab left open after the session ends sends a 401 request to the server every 30 s until some other API call happens to send the user to the login page. The UI says nothing about it in the meantime. Retries stay capped, so this is not a resource problem.
    - Suggested fix: After a failed attempt that never opened (or after a few in a row), call `auth.checkSession(true)`. If the session is gone, `loggedIn` turns false and the effect stops the stream. Alternatively, record that the endless retry is accepted.

- [ ] **CR-2 | The stale-source guard in `onerror` can never run**
    - File: `web/src/app/core/events/change-events.service.ts:90`
    - Category: Quality
    - Problem: `if (this.source !== source) return;` cannot be true. `close()` sets `onerror = null` on the current source before it drops the reference, so an old source never runs this handler. No test covers it either.
    - Why it matters: It suggests a race that the code cannot have, which is misleading for later maintainers. This is minor.
    - Suggested fix: Remove the guard. If you keep it as defensive code, add a comment saying that `close()` already detaches the handlers.

## Review conclusion

No material issues found. The implementation meets the acceptance criteria and matches the server SSE contract. The two P3 findings are optional improvements.
