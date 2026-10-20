# Code Review

Date: 2026-10-20

Task: p0-web-sse Web SSE client (second review, final revision)

Scope: committed changes of the task, `git diff 961dc340..18b21a7 -- web/` (web/src/app/core/events/change-events.service.ts, index.ts, change-events.service.spec.ts), at HEAD 18b21a73dca709d574050811510cd96d81ccdc52. This is a re-review after the fixes for CR-1, CR-2 and FX-1 (reviews/p0-web-sse/fixes.md). The working tree has an uncommitted change to the spec file (+40 lines from the concurrent test-writer). That change is out of scope.

Requirements:
- tasks/p0-web-sse.md acceptance criteria and its three recorded assumptions: lazy connect through the AuthService.loggedIn effect; backoff starts at 1 s, doubles up to 30 s and resets on open; resync$ emits on every reopen within one login; malformed data is ignored
- SPEC.md §2 (SSE `/api/v1/events`), §4.2, and the frontend section on the SSE service
- docs/ARCHITECTURE.md, the AGENTS.md project notes, docs/QUALITY.md
- Server contract in server/.../core/events: event name `change`, ChangeEvent JSON, no replay, 401 when not logged in
- web/src/app/core/auth/auth.service.ts and web/src/app/core/http/interceptors.ts, for the behaviour of the session probe

Git range:
- Branch: main
- HEAD: 18b21a73dca709d574050811510cd96d81ccdc52
- Base/start: 961dc340bb51de90ddf9dd1a2ca7650c1110281c
- Commits reviewed: 4 (b50060b, caeb5d8, 8b118fc, 18b21a7). The heartbeat commits in the range change only task metadata.

Commands run:
- `cd web && npx ng test --watch=false --include src/app/core/events/change-events.service.spec.ts`: 28 passed. This run used the working tree, which includes the test-writer's uncommitted additions. fixes.md reports 25 tests at HEAD.
- `cd web && npx eslint src/app/core/events`: clean
- I did not re-run the full `npm run check`. fixes.md reports exit 0 after FX-1. The parent should record its own run as evidence.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Resolution of the earlier findings:
- CR-1 (endless retries after the session ends): resolved. Each `onerror` sends a probe to `GET /api/v1/auth/me` with `SKIP_LOGIN_REDIRECT` and `SILENT_ERRORS` set (change-events.service.ts:117-127). Only an `ApiError` with status 401 triggers `auth.checkSession(true)`. That call sets `loggedIn` to false, and the effect then calls `disconnect()`, which clears the pending timer. AuthService's own 401 is not redirected, and problemInterceptor shows no toast for it. Other probe failures and a rejected re-check are swallowed, so the backoff keeps going. If a stale probe still returns a 401 after a re-login, the re-check asks the server again, so it cannot log out a valid session. Tests cover the 200, 401, 502/503/network and failed re-check cases against the real HTTP stack.
- FX-1 (a toast for every reconnect failure): resolved by the silent probe above. A test asserts that `NotificationService.error` is never called.
- CR-2 (dead stale-source guard): resolved. The guard was removed, and a test now asserts that `close()` sets the handlers of the old source to null.

Acceptance criteria check:
1. Connects to `/api/v1/events` after login, reconnects with backoff, exposes `events$(type)`: met. The service opens a credentialed EventSource while `loggedIn` is true. Backoff starts at 1 s and doubles up to the 30 s cap, the timer is cleared on logout, and `events$` filters by `ChangeEvent.type`. The service connects lazily, so the stream opens only when something injects it. This is recorded as an assumption.
2. Resync on reconnect: met. `resync$` emits on reopen and not on the first connect of a login, including a first connect that only succeeds after retries.
3. Vitest tests: met. Tests cover dispatch by type, backoff with fake timers (exponential, capped, reset), and resync on reconnect only. The focused suite and the lint ran green here. The full `npm run check` is reported green in fixes.md, and the parent must record its own run.

Observations (not findings): every stream end, including the server's normal close after 15 minutes, now sends one extra `GET /auth/me`. The cost is negligible. After a 401 probe the app becomes logged-out without navigating to /login. That matches how AuthService already behaves and is outside this task's touches.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. CR-1 and CR-2 are resolved, and the FX-1 follow-up is correct and tested. The final code meets the acceptance criteria and follows the project conventions.
