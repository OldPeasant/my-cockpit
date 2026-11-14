# Test Report

Date: 2026-11-14

Task: web-sse-watchdog - Web: detect a dead change-event stream (sleep, network change) and resync

Scope: base `88e8f67da645d7574f4e97bf1059d0ad61dc14ee`, task code commits `1f76628ad..5c77f6835` (including the CR-1
fix `9e8677237` and the e2e rewrite `5c77f6835`), measured on HEAD `63e972f0df04fc59d6e3eb769433de56f5e476d9` (the
parent's commit of this round's spec additions; the spec on disk is identical to the committed one). Diff:
`git diff 88e8f67da HEAD -- . ':!tasks' ':!reviews'`. Re-run on the final code; replaces the earlier report written
before the CR-1 fix.

Requirements:
- `tasks/web-sse-watchdog.md` acceptance criteria and recorded assumptions
- `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md`
- Parent instructions (no Playwright run; overwrite this report)

## Test files changed

- `web/src/app/core/events/change-events.service.spec.ts`

## Tests added or updated

Count: 2 (added to the `watchdog` describe; existing web and server tests reviewed and kept)

- `treats a stream as silent on wake-up only after more than a heartbeat plus a check interval`: boundary test of the
  CR-1 fix. After a ping, exactly 30 s (25 s heartbeat + 5 s check) of silence followed by `online` leaves the stream
  alone; 30 s + 1 ms reconnects. This is a regression test: with the pre-fix threshold (heartbeat only, 25 s) the first
  assertion fails.
- `starts the backoff over after a wake-up reconnect`: after three failures (next backoff 4 s), `online` reconnects at
  once, and a failure of that new stream retries after the initial 1 s, not 4 s or 8 s.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Heartbeat sent as named SSE event `ping` with data `{}` | `SseEventHubTests`: `connected clients get heartbeat ping events`, `a new stream starts with a connected comment and heartbeats are named ping events`, dead-client heartbeat test | `server/gradlew -p server test jacocoTestReport -q` | Not run this round: the run was started but stopped (killed) before it finished, see "Full test execution". Server code unchanged since the earlier test-writer run, where SseEventHubTests passed 14/14 |
| 1. Android ignores `ping` | existing Android `GtdChangeStreamTest` (`event: other` case), per the parent's assumption | none | Not run: Android suite outside this run; parent records it |
| 2. Silence beyond `silenceMs` closes and reconnects via the error path (backoff, session probe), `resync$` emits; configurable token | `reconnects a silent stream after the backoff, re-checks the session and resyncs`, `gives up on a connection that never opens after the silence and retries`, `uses a configured STREAM_WATCHDOG`, `treats ~2.5 heartbeats (65 s) of silence as a dead stream by default` | `npx ng test --watch=false --include src/app/core/events` (in `web/`) | Pass |
| 2. Ping / any event resets the watchdog | `keeps a stream that gets pings`, `counts change events as signs of life too`, `counts named messages$ events as signs of life` | same | Pass |
| 3. `visibilitychange`/`online` after silence reconnects at once; healthy stream (incl. a late ping within the margin) left alone; pending backoff skipped | `reconnects at once when the tab becomes visible ...`, `reconnects at once when the browser goes back online ...`, `skips a pending backoff ...`, `leaves a healthy stream alone ...`, `allows a late ping a margin ...`, `treats a stream as silent on wake-up only after more than a heartbeat plus a check interval`, `starts the backoff over after a wake-up reconnect`, `ignores visibilitychange to hidden`, `does not reconnect at once while hidden ...`, `does not resync on a wake-up reconnect until the new stream opens` | same | Pass |
| 3. Wake-up (timer fired much later) reconnects; not in a hidden tab | `reconnects at once after a wake-up ...`, `does not treat a late check in a hidden tab as a wake-up` | same | Pass |
| 4. Logout stops watchdog and listeners | `stops the watchdog and its listeners on logout` | same | Pass |
| 5. Playwright: cut stream on `/new`, mail appears without reload | `web/e2e/new/live-watchdog.spec.ts` | none by the test-writer | Not run: excluded by the parent (already verified by the parent) |
| 6. `npm run check`, `./gradlew check`, `scripts/check.sh` | none | none | Not run by the test-writer (parent runs them) |

## Focused test execution

Command:

`npx ng test --watch=false --include src/app/core/events` (in `web/`), plus
`npm --prefix web run coverage -- --include src/app/core/events`

Result:

Pass: 1 file, 57 tests (both runs). `npx prettier --check` and `npx eslint` on the spec: clean.

## Full test execution

Command:

`npm --prefix web run coverage` (full web suite) and `server/gradlew -p server test jacocoTestReport -q`
(full server suite), both started 2026-11-14 21:32 in the background.

Result:

Not run to completion: both processes were killed before they finished (the coordinator asked to finish at once);
no result is claimed. The full web and server suites passed in the earlier test-writer run on the pre-CR-1 code; the
parent's `.taskcheck` run is the evidence for the final code.

## .taskcheck

Result:

Not run (the parent runs it before `done`).

## Bugs found

None.

## Coverage

Status: partial: the web file is verified from a fresh focused run; the server files were not measured this round
(the run was killed).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no changed-line tooling configured). For the web file, every changed line is
inside the covered set: the only uncovered statement (line 138) is the pre-existing re-entry guard of `connect()`.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-sse-watchdog`, base
  `88e8f67da645d7574f4e97bf1059d0ad61dc14ee` (from the parent), HEAD `63e972f0df04fc59d6e3eb769433de56f5e476d9`;
  scope = `git diff --name-only 88e8f67da HEAD -- server/src/main web/src android/app/src/main` without specs.
- Source/test snapshot identifier (including uncommitted inputs): HEAD with a clean working tree for these files;
  sha256 `change-events.service.ts` = `a3b54e51eecdf7c94418ab3b4575260a4b7683c673d66660c616b018c8603a5d`,
  `change-events.service.spec.ts` = `7fa59cd59775c15ae90d98caddfd193d98403f002646e86587dd85af1d3bf3a4`
  (same hashes before and after the run).
- Exact included files: `web/src/app/core/events/change-events.service.ts` (measured);
  `server/src/main/kotlin/ch/sonensei/cockpit/core/events/infra/SseEventHub.kt` and
  `server/src/main/kotlin/ch/sonensei/cockpit/core/events/api/EventsController.kt` (in scope, not measured this
  round: the JaCoCo run was killed). Excluded: `web/src/app/core/events/index.ts` (re-export list only, no executable
  code), `api/openapi.yaml` (generated), `web/e2e/new/live-watchdog.spec.ts` (test).
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8`,
  `npm --prefix web run coverage -- --include src/app/core/events` from the repository root, test selection
  `src/app/core/events` (57 tests). Whole-file numbers of `change-events.service.ts` come from its own spec only.
- Run start/completion time and exit status: 2026-11-14 21:56:14, about 1 s of tests, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256
  `545bc9a227ac7edc9550d943f85b7dca2d8c4adc27dc23217446d43f2838eed7`, `web/coverage/web/coverage-summary.json`
  (repository totals in it are from the focused run and are not used).
- Evidence outputs are fresh and inputs stayed unchanged: yes for the web file (Vitest rewrote `coverage/` in this run;
  source hashes unchanged).
- Final run after latest test additions and production fixes: yes for the web file (after the CR-1 fix and both new
  tests); no for the server files (run killed; server code has not changed since the earlier measured run).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/core/events/change-events.service.ts` | 137/138 (99.3%) | 40/41 (97.6%) | 36/36 (100%) | 114/114 (100%) |
| `server/.../core/events/infra/SseEventHub.kt` | not measured this round | not measured | not measured | not measured |
| `server/.../core/events/api/EventsController.kt` | not measured this round | not measured | not measured | not measured |

No aggregate is given: only one of the three scoped files was measured in this round. For reference only (earlier
revision, server code identical): the earlier report measured `SseEventHub.kt` at 293/314 instructions, 4/4 branches,
17/17 methods, 56/56 lines, and `EventsController.kt` at 10/10 instructions, 2/2 methods, 4/4 lines.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/core/events/change-events.service.ts:138` (`return` of `if (this.source \|\| this.reconnectTimer !== undefined)` in `connect()`) | Pre-existing re-entry guard; every caller (login effect, backoff timer, `wake()` after clearing both) calls it only when neither is set | Justified gap: defensive, not reachable through the public API |
| `SseEventHub.kt`, `EventsController.kt` | Named `ping` heartbeat (criterion 1) | Not measured this round; covered by `SseEventHubTests`, which passed in the earlier run. Follow-up: the parent's `./gradlew check` is the evidence for the final code |

## Unable to test

- Criterion 5 (Playwright against the e2e stack): not run here, by instruction; the parent verified it.
- Android ignoring `ping`: not re-run; relies on the existing `GtdChangeStreamTest` case named in the parent's assumption.
- Full web and server suites and server coverage on the final code: the runs were killed before finishing; the parent's
  `npm run check`, `./gradlew check` and `.taskcheck` must provide this evidence.

## Summary

Tests added; the focused web suite passes (57 tests) and no production defects were found. Server coverage and the full
suites were not completed in this round and must be covered by the parent's final validation runs.
