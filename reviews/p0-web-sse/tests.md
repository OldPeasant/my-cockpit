# Test Report

Date: 2026-10-20

Task: p0-web-sse - Web SSE client

Scope: `git diff 961dc340bb51de90ddf9dd1a2ca7650c1110281c..a28e79cc7bed9d73056cd530e625d2c511f3e818 -- web/` (one task commit a28e79c "p0-web-sse: ChangeEventsService (SSE, backoff reconnect, resync) + tests"), branch `main`, plus the uncommitted test additions of this report in `web/src/app/core/events/change-events.service.spec.ts`.

Requirements:
- `tasks/p0-web-sse.md` acceptance criteria and recorded assumptions (lazy connect via `AuthService.loggedIn` effect; backoff 1 s doubling to 30 s, reset on open; `resync$` on every reopen within one login, not on the first connect; malformed data ignored)
- Server contract: `server/src/main/kotlin/ch/sonensei/cockpit/core/events` (`SseEventHub.EVENT_NAME = "change"`, data = `ChangeEvent` JSON; heartbeats/`connected` are SSE comments) and generated `web/src/app/api/generated/models/change-event.ts`
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

- `web/src/app/core/events/change-events.service.spec.ts`

## Tests added or updated

Count: 11 added (10 existing tests unchanged; 21 total in the file)

Added in `describe('ChangeEventsService')`:
- `completes the event and resync streams when the app is destroyed`
- `ignores events without a string type`
- `ignores SSE messages with another event name`
- `keeps dispatching to existing subscribers after a reconnect`
- `emits resync once per reconnect`
- `does not resync when the first connect of a login only succeeds after retries`
- `connects at once on a new login during a pending reconnect, with a fresh backoff`
- `ignores errors of a stream it already closed`

Added in new `describe('ChangeEventsService defaults')` (production token defaults, no overrides):
- `backs off from 1 s up to 30 s by default`
- `opens a credentialed EventSource to the events endpoint by default` (global `EventSource` stubbed with `vi.stubGlobal`)
- `caps the default backoff at 30 s` (fake timers: 1, 2, 4, 8, 16, 30, 30 s)

## Requirement coverage

Focused command for all rows: `cd web && npx ng test --watch=false --include='src/app/core/events/**/*.spec.ts'` (21 passed).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: connects after login to `/api/v1/events` (closes on logout) | `connects to the events endpoint when logged in`, `waits for the login, and closes the stream on logout`, `opens a credentialed EventSource to the events endpoint by default`, `closes the stream when the app is destroyed` | focused command | Pass |
| AC1: reconnects with backoff (1 s doubling, 30 s cap, reset after open) | `reconnects with exponential backoff up to the maximum`, `starts the backoff over after a successful reconnect`, `caps the default backoff at 30 s`, `backs off from 1 s up to 30 s by default`, `stops reconnecting on logout`, `connects at once on a new login during a pending reconnect, with a fresh backoff`, `ignores errors of a stream it already closed` | focused command | Pass |
| AC1: `events$(type)` per item type | `dispatches each event only to the subscribers of its type`, `keeps dispatching to existing subscribers after a reconnect`, `ignores malformed event data`, `ignores events without a string type`, `ignores SSE messages with another event name`, `completes the event and resync streams when the app is destroyed` | focused command | Pass |
| AC2: `resync` signal on reconnect (not first connect) | `emits resync after a reconnect, not on the first connect`, `emits resync once per reconnect`, `does not resync on the first connect of a new login`, `does not resync when the first connect of a login only succeeds after retries` | focused command | Pass |
| AC3: Vitest tests cover dispatch, backoff with fake timers, resync; `npm run check` passes | all of the above | `cd web && npm run check` | Pass |
| Real browser EventSource against the running server | none (unit tests use a fake EventSource; jsdom has none) | none | Not run: no criterion names an E2E/browser run; out of this task's unit-test scope |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/core/events/**/*.spec.ts'`

Result:

Pass (1 test file, 21 tests passed)

## Full test execution

Command:

`cd web && npm run check` (api:check, `ng lint --max-warnings=0`, `ng test --watch=false`, `ng build`)

Result:

Pass: `src/app/api/generated is up to date.`, `All files pass linting.`, 19 test files / 268 tests passed, build complete.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` ended with `== all checks passed` (server, web, android).

## Bugs found

None. No production defect was found; all new tests pass against the implementation at a28e79c.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file coverage here, because both production files are new in the task (every executable line is an added line): 55/55 lines of `change-events.service.ts`.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-web-sse`; base `961dc340bb51de90ddf9dd1a2ca7650c1110281c` (from the parent); HEAD `a28e79cc7bed9d73056cd530e625d2c511f3e818`; task-owned commit a28e79c (only commit in `961dc340..HEAD`), file scope from `git diff --name-only 961dc340..HEAD -- web/`, consistent with the task's `touches: [web/src/app/core/events]`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 `change-events.service.ts` bb9a0d41e2c27e87d30b633c1ac0d568fbf690990c17e90f8fa6ce3cc51e9419, `index.ts` b2da4a386d37418a7d0b3a54f92d963098fbd5882eb2ece1f5ec7c41c4fb76d5, `change-events.service.spec.ts` (uncommitted, with the new tests) fbb1981d613dd9998f18bb744ad02e9949332f1fbb031ba835b7936be53fc4f8; identical before and after the coverage, check and taskcheck runs.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/core/events/change-events.service.ts`. Excluded `web/src/app/core/events/index.ts`: re-exports only, no executable statements; v8 emits no record for it. `change-events.service.spec.ts` excluded as a test file. No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 with `@vitest/coverage-v8` (v8 provider) via `npm run coverage` (`ng test --watch=false --coverage ...`) in `web/`; full web suite (19 files, 268 tests).
- Run start/completion time and exit status: started 2026-10-20 14:17:52 +0200, reports written 14:18:01 +0200; all 268 tests passed and the reports were regenerated (exit status not echoed separately; the run reported no failures).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 41c348daf82a6ac3e5d6acdaf7e80678db0b99947208a06c26df4db9760acbfa, `web/coverage/web/coverage-final.json` sha256 7d28919db8094d55df09af178222e7dcae3d7640ae1f51c3506ae32cb9c31c76, `web/coverage/web/lcov.info` (Git-ignored).
- Evidence outputs are fresh and inputs stayed unchanged: report mtimes 14:18:01 match this run; input hashes rechecked unchanged afterwards.
- Final run after latest test additions and production fixes: yes for the test additions; no production fixes were made. Any later production change by a fixer makes these numbers stale.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/core/events/change-events.service.ts` | 65/66 (98.48%) | 20/21 (95.24%) | 17/17 (100%) | 55/55 (100%) |
| `web/src/app/core/events/index.ts` | N/A (no executable code) | N/A | N/A | N/A |

Aggregate over the measured scope (one file): same as the row above; `index.ts` excluded as noted, no files missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/core/events/change-events.service.ts:78` (early `return` of `connect()` when a stream or a reconnect timer already exists; branch 0 of the `if`, statement at col 58) | Guard against opening a second stream | Justified gap: not reachable through the public API. `connect()` runs only from the `loggedIn` effect (runs on a change to `true`, when no source/timer exists because logout's `disconnect()` cleared them) and from the reconnect timer (which clears `reconnectTimer` and runs only after `close()`). Tests `connects at once on a new login during a pending reconnect...` and `stops reconnecting on logout` verify no duplicate streams appear. |

## Unable to test

- Behavior against a real browser `EventSource` and the running server (cookie auth with `withCredentials`, actual 15-min stream end, proxy behavior): not run; unit tests use a fake EventSource. No acceptance criterion of this task requires it; a later E2E task could cover it.
- Note: this report and the test changes are left uncommitted for the parent worker (see Summary).

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. 11 tests added, focused tests, `npm run check` and `.taskcheck` passed.
