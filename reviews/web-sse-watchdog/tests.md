# Test Report

Date: 2026-11-14

Task: web-sse-watchdog - Web: detect a dead change-event stream (sleep, network change) and resync

Scope: `fd7cbec709075d6ac8641bcf2a600edd594709c7..434efb97fcb2b2a10403111dc663eecd0601452d` (branch `main`, task
commits `1db4068f0` "ping heartbeat event, web stream watchdog" and `434efb97f` "regenerate openapi"; the parent named
them `ee6903c3d`/`f0273eaf1`, the same changes after a rebase). Working tree clean before the test edits.

Requirements:
- `tasks/web-sse-watchdog.md` acceptance criteria and assumptions
- `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md`
- Parent instructions (gaps to look at: a connecting stream that never opens, wake-up while hidden, named
  `messages$` events as activity)

## Test files changed

- `web/src/app/core/events/change-events.service.spec.ts`

## Tests added or updated

Count: 6 (added to the `watchdog` describe; the parent's 11 watchdog/default tests and the server tests were reviewed and kept as is)

- `gives up on a connection that never opens after the silence and retries`
- `counts named messages$ events as signs of life`
- `does not treat a late check in a hidden tab as a wake-up`
- `does not reconnect at once while hidden, even after a long silence offline`
- `does not resync on a wake-up reconnect until the new stream opens` (also: a wake-up sends no session probe)
- `uses a configured STREAM_WATCHDOG` (the token is configurable, criterion 2)

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Heartbeat sent as named SSE event `ping` with data `{}` (no comment) | `SseEventHubTests`: `connected clients get heartbeat ping events`, `a new stream starts with a connected comment and heartbeats are named ping events`, dead-client heartbeat test | `cd server && ./gradlew test jacocoTestReport` | Pass (SseEventHubTests 14/14) |
| 1. Android ignores `ping` | existing Android `GtdChangeStreamTest` (`event: other` case) per the parent's assumption | not run here (outside the reviewed parts) | Not run: Android suite not in this run; parent records it |
| 2. Silence > `silenceMs` closes and reconnects via the error path (backoff, session probe), `resync$` emits | `reconnects a silent stream after the backoff, re-checks the session and resyncs`, `gives up on a connection that never opens after the silence and retries`, `uses a configured STREAM_WATCHDOG`, default-token test | `cd web && npx ng test --watch=false --include src/app/core/events` | Pass |
| 2. Ping / any event resets the watchdog | `keeps a stream that gets pings`, `counts change events as signs of life too`, `counts named messages$ events as signs of life` | same | Pass |
| 3. `visibilitychange`/`online` after silence reconnects at once; healthy stream left alone; pending backoff skipped | `reconnects at once when the tab becomes visible ...`, `reconnects at once when the browser goes back online ...`, `skips a pending backoff ...`, `leaves a healthy stream alone ...`, `ignores visibilitychange to hidden`, `does not reconnect at once while hidden ...`, `does not resync on a wake-up reconnect until the new stream opens` | same | Pass |
| 3. Wake-up (timer fired much later) reconnects; not in a hidden tab | `reconnects at once after a wake-up ...`, `does not treat a late check in a hidden tab as a wake-up` | same | Pass |
| 4. Logout stops watchdog and listeners | `stops the watchdog and its listeners on logout` | same | Pass |
| 5. Playwright/manual: cut stream on `/new`, mail appears without reload | none | - | Not run: needs the e2e stack; parent's responsibility |
| 6. `npm run check`, `./gradlew check`, `scripts/check.sh` | - | - | Not run by the test-writer (parent runs `.taskcheck`); web full unit suite and server test suite ran under coverage, see below |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/core/events`

Result:

Pass (1 file, 54 tests). `npx prettier --check` and `npx eslint` on the spec: clean.

## Full test execution

Command:

`cd web && npm run coverage` (full web unit suite with coverage)

Result:

Pass: 196 files, 4920 tests.

Command:

`cd server && ./gradlew test jacocoTestReport -q` (full server test suite with coverage, includes `SseEventHubTests`)

Result:

Pass: exit 0, 5274 tests in the JUnit XML results, 0 failures/errors; `SseEventHubTests` 14 tests, 0 failed.

## .taskcheck

Result:

Not run (the parent runs it before `done`).

## Bugs found

None.

## Coverage

Status: verified (all three scoped production files measured, fresh runs, exit 0)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no changed-line tooling configured). For the web file all changed lines fall
inside the covered set: the only uncovered statement (line 134) is a pre-existing guard.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-sse-watchdog`, base
  `fd7cbec709075d6ac8641bcf2a600edd594709c7` (from the parent), HEAD `434efb97fcb2b2a10403111dc663eecd0601452d`;
  scope = `git diff --name-only fd7cbec70..HEAD -- server/src/main web/src android/app/src/main` minus specs.
- Source/test snapshot identifier (including uncommitted inputs): HEAD plus the uncommitted spec edit,
  sha256 `change-events.service.spec.ts` = `7013c8b71d8987866b9356e24267f92c0bc1f258b8c07f5c35f66e2220d51a89`,
  `change-events.service.ts` = `354ee1094a37e90f1a19142d0c28df9cc626f580442507516e6067d3c97b6c11` (unchanged from HEAD).
- Exact included files: `web/src/app/core/events/change-events.service.ts`,
  `server/src/main/kotlin/ch/sonensei/cockpit/core/events/infra/SseEventHub.kt`,
  `server/src/main/kotlin/ch/sonensei/cockpit/core/events/api/EventsController.kt`. Excluded:
  `web/src/app/core/events/index.ts` (type/re-export list only, v8 reports no executable code for it);
  `api/openapi.yaml` (generated); `tasks/*` (task metadata).
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8`,
  `npm run coverage` in `web/`, all specs; JaCoCo (Gradle `jacoco`), `./gradlew test jacocoTestReport -q` in
  `server/`, all tests.
- Run start/completion time and exit status: web 2026-11-14 20:59:12, ~157 s, exit 0; server started 2026-11-14 20:58 (in parallel), finished after the web run, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256
  `dc614b529f10c98e621f4533cc15803d23f4b943d6a002ac0ad6e8c4fbcbcb97`, `web/coverage/web/coverage-summary.json`;
  `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256
  `c803c32b655aaa0920fbc58dcba3b1c0e256799a9efaabbec78a2ec3db5359a8`.
- Evidence outputs are fresh and inputs stayed unchanged: yes, both runs after the last spec edit; Vitest rewrites
  `coverage/`, the JaCoCo XML did not exist before the run (only an older `html/`).
- Final run after latest test additions and production fixes: yes (no production fixes made).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/core/events/change-events.service.ts` | 136/137 (99.3%) | 40/41 (97.6%) | 36/36 (100%) | 113/113 (100%) |
| `server/.../core/events/infra/SseEventHub.kt` (JaCoCo instructions) | 293/314 (93.3%) | 4/4 (100%) | 17/17 (100%) | 56/56 (100%) |
| `server/.../core/events/api/EventsController.kt` (JaCoCo instructions) | 10/10 (100%) | N/A (0 branches) | 2/2 (100%) | 4/4 (100%) |
| Aggregate (summed counters, 3 files; `index.ts` excluded as above) | v8 statements 136/137 + JaCoCo instructions 303/324 (not summed: different units) | 44/45 (97.8%) | 55/55 (100%) | 173/173 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/core/events/change-events.service.ts:134` (`return` of `if (this.source \|\| this.reconnectTimer !== undefined)`) | Pre-existing re-entry guard of `connect()`; every caller (login effect, backoff timer, `wake()` after clearing both) only calls it when neither is set | Justified gap: defensive, not reachable through the public API |
| `SseEventHub.kt:65,124,130` (7 missed instructions each, lines counted covered) | Pre-existing `runCatching { emitter.complete() }` failure paths (complete throwing on an already finished emitter) | Justified gap: pre-existing defensive code, not changed by the task; the changed `sendHeartbeat` (lines 102-111) is fully covered |

## Unable to test

- Criterion 5 (Playwright/manual run against the e2e stack): not run by the test-writer; needs the e2e stack, the parent
  must run and record it.
- Android ignoring `ping`: not re-run here; relies on the existing `GtdChangeStreamTest` case named in the parent's assumption.

## Summary

Tests added and all validation passed.
