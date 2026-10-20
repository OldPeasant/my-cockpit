# Test Report

Date: 2026-10-20

Task: p0-web-sse - Web SSE client

Scope: `git diff 961dc340bb51de90ddf9dd1a2ca7650c1110281c..18b21a73dca709d574050811510cd96d81ccdc52 -- web/` (second pass on the final code, after fixes CR-1, CR-2, FX-1). The task commits are b50060b (implementation and tests), caeb5d8 (more tests and reports), 8b118fc (CR-1, CR-2) and 18b21a7 (FX-1). The other commits in the range are heartbeats that do not touch `web/`. Branch `main`. The scope also includes this pass's uncommitted test additions in `web/src/app/core/events/change-events.service.spec.ts`.

Requirements:
- `tasks/p0-web-sse.md`: the acceptance criteria and the recorded assumptions. The service connects lazily through the `AuthService.loggedIn` effect. The backoff starts at 1 s, doubles up to 30 s, and resets after an open. `resync$` emits on every reopen within one login, not on the first connect. Malformed data is ignored.
- `reviews/p0-web-sse/fixes.md`: the intended behaviour after the fixes. A stream failure triggers a silent session probe (`SILENT_ERRORS` + `SKIP_LOGIN_REDIRECT`). Only a 401 from the probe leads to `auth.checkSession(true)` and then to logout. Other probe failures keep the backoff running without toasts.
- Server contract: `SseEventHub.EVENT_NAME = "change"`, and the data is the `ChangeEvent` JSON (`web/src/app/api/generated/models/change-event.ts`).
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

- `web/src/app/core/events/change-events.service.spec.ts`

## Tests added or updated

Count: 3 added in this pass. The file now has 28 tests: 25 already at HEAD and 3 new.

Added in `describe('ChangeEventsService')`:
- `sends the session probe silently and never redirects to login on its 401`: the probe's `HttpContext` has `SILENT_ERRORS` and `SKIP_LOGIN_REDIRECT` set to true. On a 401, the real interceptor stack calls neither `Router.navigate` nor `NotificationService.error`. The router URL is `/`, which is not a public path, so a redirect would show up in this test.
- `keeps reconnecting when the re-check after a 401 probe finds the session still valid`: the probe gets a 401, but `checkSession(true)` resolves to true. The service still reconnects after 1 s.
- `probes the session once per failed attempt`: two failures send exactly two probes, and `HttpTestingController.verify()` finds no stray requests.

## Requirement coverage

Focused command for all rows: `cd web && npx ng test --watch=false --include src/app/core/events/change-events.service.spec.ts` (28 passed).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: connects to `/api/v1/events` after login and closes the stream on logout | `connects to the events endpoint when logged in`, `waits for the login, and closes the stream on logout`, `opens a credentialed EventSource to the events endpoint by default`, `closes the stream when the app is destroyed`, `detaches its handlers from a stream it closed` | focused command | Pass |
| AC1: reconnects with backoff (starts at 1 s, doubles up to a 30 s cap, resets after an open) | `reconnects with exponential backoff up to the maximum`, `starts the backoff over after a successful reconnect`, `caps the default backoff at 30 s`, `backs off from 1 s up to 30 s by default`, `stops reconnecting on logout`, `connects at once on a new login during a pending reconnect, with a fresh backoff` | focused command | Pass |
| AC1 (CR-1/FX-1): an ended session stops the reconnects, without toasts or a login redirect | `probes the session after a failed connection attempt, and reconnects while it is valid`, `logs out and stops reconnecting when the probe answers 401`, `keeps the backoff going without toasts while the server is unavailable or unreachable`, `keeps reconnecting when the session re-check after a 401 fails`, `sends the session probe silently and never redirects to login on its 401`, `keeps reconnecting when the re-check after a 401 probe finds the session still valid`, `probes the session once per failed attempt` | focused command | Pass |
| AC1: `events$(type)` for each item type | `dispatches each event only to the subscribers of its type`, `keeps dispatching to existing subscribers after a reconnect`, `ignores malformed event data`, `ignores events without a string type`, `ignores SSE messages with another event name`, `completes the event and resync streams when the app is destroyed` | focused command | Pass |
| AC2: `resync` signal on a reconnect, not on the first connect | `emits resync after a reconnect, not on the first connect`, `emits resync once per reconnect`, `does not resync on the first connect of a new login`, `does not resync when the first connect of a login only succeeds after retries` | focused command | Pass |
| AC3: Vitest tests cover dispatch, backoff with fake timers and resync; `npm run check` passes | all of the above | `cd web && npm run check` | Pass |
| Real browser EventSource against the running server | none: the unit tests use a fake EventSource because jsdom has none | none | Not run: no criterion names an E2E or browser run |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/core/events/change-events.service.spec.ts`

Result:

Pass (1 test file, 28 tests passed)

## Full test execution

Command:

`cd web && npm run check` (api:check, `ng lint --max-warnings=0`, `ng test --watch=false`, `ng build`)

Result:

Pass, exit 0. Output: `src/app/api/generated is up to date.`, `All files pass linting.`, 19 test files with 275 tests passed, build complete.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` ended with `== all checks passed` (server, web, android).

## Bugs found

None. All tests pass against the final code at 18b21a7. Before writing the navigation assertion, I confirmed that `loginRedirectInterceptor` would navigate on a 401 when `SKIP_LOGIN_REDIRECT` is not set and the URL is not public.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: the same as whole-file coverage, because both production files are new in this task and every executable line is an added line. Lines: 61/61 in `change-events.service.ts`.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: task `p0-web-sse`. Base `961dc340bb51de90ddf9dd1a2ca7650c1110281c`, given by the parent. HEAD `18b21a73dca709d574050811510cd96d81ccdc52`. Task-owned commits: b50060b, caeb5d8, 8b118fc and 18b21a7. The file scope comes from `git diff --stat 961dc340..HEAD -- web/` and matches the task's `touches: [web/src/app/core/events]`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 values were identical before and after the coverage run:
  - `change-events.service.ts`: e8c1e77ae3b3b7a1636b4a401be26febdcfc1d154178b3fc626343aabb2b534a (committed at HEAD)
  - `index.ts`: b2da4a386d37418a7d0b3a54f92d963098fbd5882eb2ece1f5ec7c41c4fb76d5
  - `change-events.service.spec.ts`: da01f0b91f68f8ff6b210e12fbacb4bec56ef2d8d6ae5631635ddac72f277e51 (uncommitted, includes the 3 new tests)
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/core/events/change-events.service.ts`. Excluded `web/src/app/core/events/index.ts`: it only re-exports and has no executable statements, so v8 emits no record for it. Excluded `change-events.service.spec.ts` as a test file. No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 with `@vitest/coverage-v8` (v8 provider). Command: `npm run coverage` (`ng test --watch=false --coverage ...`), run in `web/`. Test selection: the full web suite (19 files, 275 tests).
- Run start/completion time and exit status: started 2026-10-20 14:26:19 +0200, reports written at 14:26:23 +0200. All 275 tests passed. The exit status was not printed separately, but the run reported no failures and the reports were regenerated.
- Generated report paths and report hashes/run identifier (Git-ignored):
  - `web/coverage/web/coverage-summary.json`: sha256 0d5aa54413f8fc79fe86022ecec50067dffe9d06f2ed28683efb463e2ace8678
  - `web/coverage/web/coverage-final.json`: sha256 2e721de6356ac1148124045022729e3506073b67d149a1a763be365fd353c9dc
  - `web/coverage/web/lcov.info`
- Evidence outputs are fresh and inputs stayed unchanged: the report mtimes (14:26:23) match this run, and the input hashes were the same after the run.
- Final run after latest test additions and production fixes: yes. The run covers the code after CR-1, CR-2 and FX-1 (HEAD 18b21a7) plus this pass's tests. A later production change would make these numbers stale.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/core/events/change-events.service.ts` | 71/72 (98.61%) | 22/23 (95.65%) | 21/21 (100%) | 61/61 (100%) |
| `web/src/app/core/events/index.ts` | N/A (no executable code) | N/A | N/A | N/A |

The aggregate over the measured scope (one file) equals the row above. `index.ts` is excluded as noted, and no files are missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/core/events/change-events.service.ts:82`: the early `return` in `connect()` when a stream or a reconnect timer already exists (branch 0 of the `if`, the statement at col 58) | Prevents a second stream from opening | Justified gap: the public API cannot reach it. `connect()` runs only from the `loggedIn` effect and from the reconnect timer. The effect calls it only after a change to `true`, when the logout's `disconnect()` has already cleared the source and the timer. The timer clears `reconnectTimer` before it calls `connect()`, and it runs only after `close()`. The tests `connects at once on a new login during a pending reconnect, with a fresh backoff` and `stops reconnecting on logout` confirm that no duplicate streams open. |

## Unable to test

- Behaviour with a real browser `EventSource` against the running server: cookie auth with `withCredentials`, the server's 15-minute stream end, a real 401 on reconnect, and proxies. This was not run because the unit tests use a fake EventSource and no acceptance criterion requires a browser run. A later E2E task could cover it.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. This pass added 3 tests. The focused tests (28), `npm run check` (275 tests) and `.taskcheck` passed on the final code at 18b21a7 plus the new tests. The test changes and this report are not committed; the parent worker commits them.
