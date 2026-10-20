# Test Report

Date: 2026-10-20

Task: p0-e2e-harness - Playwright e2e harness against the compose stack (second pass, after the fixer commit 67a3e21)

Scope: base `bdd9ca3d084505a3c0d2a00e2d0155ff56d57cc7` .. HEAD `67a3e214e437650d65b362380e308eac6cecaeea` (branch `main`). Task commits (from the parent, matching `git log bdd9ca3..HEAD`, all prefixed `p0-e2e-harness:`): 8dd904f (server hooks), 146cc20 (compose, runner, fixtures, smoke spec), 89be721 (docs, run time), 9d1e346 (first test-writer pass), ac3a929 (review), 67a3e21 (fixes CR-1..CR-3: per-project `E2E_IMAGE` tag, SIGINT/SIGTERM forwarding with exit 130/143, `e2e:typecheck` in `npm run check`). The fixer commit changed no server code (`git diff 9d1e346..HEAD -- server` is empty).

Uncommitted at measurement: the two test files changed in this pass (below). `reviews/p0-e2e-harness/review.md` was also modified in the worktree during this pass; not by this test-writer.

Requirements:
- `tasks/p0-e2e-harness.md` acceptance criteria and recorded assumptions
- `reviews/p0-e2e-harness/fixes.md` (CR-1, CR-2, CR-3)
- `AGENTS.md`, `docs/ARCHITECTURE.md` ("Coverage"), parent instructions

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/testsupport/TestSupportLoginWebTests.kt` (sha256 `bb5a069c27d86007b26c66da09d659bf911650caf40d22116ffae12dbd6f657a`)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/testsupport/TestSupportWebTests.kt` (sha256 `763168a4691e2e0fc73b92d6e4141adc4fbca330f8e490978a9011916b63a0d5`)

No production file, build file or configuration was changed.

## Tests added or updated

Count: 2 test classes updated (no new test cases, no assertion changed)

At HEAD 67a3e21 (after the rebase onto bdd9ca3) the full server suite failed: `cd server && ./gradlew check` -> 759 tests, 3 failed (`FlywayConfigurationTests`, `ProfileTests$E2e` x2), all with `FATAL: sorry, too many clients already` (SQL state 53300) from the shared Postgres Testcontainer. Reproduced in 3 runs (twice through `sh scripts/check.sh`, once with `./gradlew check --continue`). Every cached Spring test context keeps a Hikari pool open on the one container. The task adds one more distinct cached context (MockMvc + profile `e2e`, used by `TestSupportWebTests$E2eProfile` and `TestSupportLoginWebTests`). Together with the contexts that main added since the first pass, the connection limit is exceeded. The same suite without the task's `core.testsupport` tests passed (`./gradlew test -q` with one `--tests` pattern per other test package: root classes, `core.auth`, `core.crypto`, `core.events`, `core.persistence`, `core.settings`, `core.spa`, `core.web`, `mail`, `support` -> pass).

Change: added `@DirtiesContext` (class mode after class) to `TestSupportWebTests.E2eProfile` and `TestSupportLoginWebTests`, with a one-line comment. This follows the existing convention in `CsrfCookieWebTests` ("The context is closed afterwards, so it does not hold database connections for the rest of the run"). After this change the full server suite passes: 759 tests, 0 failures.

I did not add tests for `web/e2e/support/run-e2e.mjs`. The project has no test runner for Node scripts outside `web/src`, and `scripts/api-gen-check.mjs` has no tests either. Vitest via `ng test` only covers `web/src`. Adding one would mean a new test setup, which is out of scope for this helper. The runner was instead exercised end to end (see below).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 `npm run e2e` brings up the compose stack (profile e2e, fresh Postgres), waits for health, runs chromium, tears down | smoke.spec.ts (2 tests) through the runner | `cd web && npm run e2e` (Podman 5 + `podman-compose` via the `docker compose` shim) | Pass: image `localhost/cockpit-e2e-72159f73:latest` (per-project tag, CR-1), `[e2e] stack healthy after 15 s`, 2 passed (1.6s), `[e2e] tearing down the stack`; `podman ps -a --filter name=cockpit-e2e-72159f73` is empty afterwards |
| AC1 `npm run e2e -- SPEC` runs one spec | smoke.spec.ts, `-g "loggedIn fixture"` | `cd web && npm run e2e -- e2e/support/smoke.spec.ts -g "loggedIn fixture" --workers=2` | Pass: healthy after 15 s, 1 test run, 1 passed, torn down |
| AC2 `loggedIn` fixture: setup (TOTP via otplib) once per worker, session stored | both smoke tests, including with several workers (exercises the mkdir lock and the shared `account.json`, which the first pass had not run) | `cd web && npm run e2e -- --workers=3 --repeat-each=3` | Pass: "Running 6 tests using 3 workers", 6 passed (2.2s), torn down |
| AC3 `POST /api/test/clock {now}` sets the server clock (e2e only) | `ShiftableClockTests` (5), `TestSupportWebTests$E2eProfile` (5), `TestSupportLoginWebTests` (9) | `cd server && ./gradlew test --tests '*testsupport*'`; full `./gradlew check jacocoTestReport --rerun-tasks` | Pass (22 testsupport tests, 0 failures) |
| AC3 `POST /api/test/reset` empties the DB (e2e only) | `E2eProfile.reset empties the database ...`, `TestSupportLoginWebTests` reset tests | same | Pass |
| AC3 both absent in other profiles | `TestSupportWebTests$DefaultProfile` (2), `$DevProfile` (1), `the public test chain does not open the rest of the API` | same | Pass |
| AC4 smoke spec (login, see shell) passes; run time in ARCHITECTURE.md; e2e not in `.taskcheck` | smoke.spec.ts | `npm run e2e` (above); `scripts/check.sh` has no e2e step | Pass |
| CR-1 per-project image tag | n/a (runtime) | `npm run e2e` | Pass: build log `COMMIT localhost/cockpit-e2e-72159f73:latest` |
| CR-2 SIGINT/SIGTERM forwarded, exit 130/143, teardown | n/a | `timeout -s TERM 40 node e2e/support/run-e2e.mjs --repeat-each=300 --workers=1` | Not run: the sandbox denied this command in this pass. The fixer's run (fixes.md: exit 143, teardown, no containers or Playwright processes left) is the only evidence |
| CR-3 e2e sources type-checked in `npm run check` | n/a | `cd web && npm run e2e:typecheck`; `sh scripts/check.sh` (web step runs `npm run -s check`, which includes `e2e:typecheck`) | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*testsupport*'`

Result:

Pass (before the `@DirtiesContext` change: BUILD SUCCESSFUL, 22 tests). After the change, the testsupport classes were run again as part of the full suite: ShiftableClockTests 5/5, TestSupportLoginWebTests 9/9, TestSupportWebTests$DefaultProfile 2/2, $DevProfile 1/1, $E2eProfile 5/5.

## Full test execution

Command:

`cd server && ./gradlew check jacocoTestReport --rerun-tasks -q`

Result:

Pass: 65 result files, 759 tests, 0 failures, 0 errors, 0 skipped (with the `@DirtiesContext` change). Without it, the same `./gradlew check --continue` failed (759 tests, 3 failed, "too many clients already").

Also run: `cd web && npm run e2e` (2 passed), `npm run e2e -- e2e/support/smoke.spec.ts -g "loggedIn fixture" --workers=2` (1 passed), `npm run e2e -- --workers=3 --repeat-each=3` (6 passed), `npm run e2e:typecheck` (pass).

## .taskcheck

Result:

Pass with the test change: `sh scripts/check.sh` -> exit 0, "== all checks passed" (tfp gate tests, server ktlint + tests, web lint + e2e typecheck + 18 Vitest files + build, android). Before the change it failed twice at the server step (3 failed tests, see above; the first two runs also showed a Gradle `EOFException`/`NoSuchFileException` on the binary results file, which came from the crashed test results).

## Bugs found

No production defects found.

One test-suite defect was found and fixed in the task's own test files (not production code):

### Full server suite exhausts Postgres connections after the rebase

Finding ID:

`TW-1`

Test:

`ch.sonensei.cockpit.support.FlywayConfigurationTests`, `ch.sonensei.cockpit.support.ProfileTests$E2e` (both tests). These are pre-existing tests that failed as a side effect.

Production source:

None. The cause is in the test setup: one more cached Spring context (MockMvc + `e2e`) from `server/src/test/kotlin/ch/sonensei/cockpit/core/testsupport/{TestSupportWebTests,TestSupportLoginWebTests}.kt` on the shared Testcontainer (`PostgresTestBase`).

Requirement:

`.taskcheck` (`sh scripts/check.sh`) must pass for `tasks.py done`.

Expected behavior:

`cd server && ./gradlew check` passes.

Actual behavior:

3 tests failed with `FATAL: sorry, too many clients already` (SQL state 53300).

Severity:

High. It blocks `.taskcheck` and therefore `done`.

Fix required:

Fixed in this pass in the test files only (`@DirtiesContext` on the two e2e+MockMvc classes). The parent must commit the two changed test files. The headroom stays small for the whole suite: every new distinct cached context holds about 10 connections. A suite-wide fix would be outside this task's `touches`, for example a smaller Hikari pool for tests or a higher `max_connections` on the shared container in `PostgresTestBase`. The parent may want a follow-up task for it.

## Coverage

Status: verified (server, JaCoCo). Not applicable to the web/deploy harness files (see below).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file coverage. All three scoped server files were added by the task (`git diff --name-status bdd9ca3..HEAD -- server/src/main web/src android/app/src/main` lists only `A` entries in `core/testsupport`), so every executable line is a changed line.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-e2e-harness`; base `bdd9ca3d084505a3c0d2a00e2d0155ff56d57cc7` (from the parent); HEAD `67a3e214e437650d65b362380e308eac6cecaeea`; ownership from the parent's commit list, which matches `git log bdd9ca3..HEAD` and the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD above plus the two modified test files (sha256 above). Production and harness inputs, hashed before the runs into `.tmp/tw2/snapshot.sha256` and checked after them with `sha256sum -c`: all unchanged except the two test files I edited on purpose (`ShiftableClock.kt`, `TestSupportConfiguration.kt`, `TestSupportController.kt`, `ShiftableClockTests.kt`, `web/e2e/support/*`, `web/playwright.config.ts`, `deploy/docker-compose.e2e.yml`, `web/package.json` OK).
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/core/testsupport/{ShiftableClock,TestSupportConfiguration,TestSupportController}.kt`. Excluded from unit coverage: `web/e2e/support/{run-e2e.mjs,api.ts,fixtures.ts}`, `web/playwright.config.ts`, `deploy/docker-compose.e2e.yml`. They are e2e harness code and configuration outside `web/src`, which the configured Vitest coverage does not instrument, so they were validated by running e2e instead. Not in scope: docs, `web/angular.json`, `web/package.json`, `web/.gitignore`, `web/e2e/README.md`, `web/e2e/tsconfig.json`. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (configuration unchanged); `./gradlew check jacocoTestReport --rerun-tasks -q` in `server/`; full server suite.
- Run start/completion time and exit status: about 2026-10-20T14:35+02:00 to 14:36:28+02:00, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, mtime 2026-10-20T14:36:28.54+02:00, sha256 `516ebf08cab405fa56265d342e43d1717fa47660ee13cf7d0da1f469912ccac4`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `--rerun-tasks` forced a fresh run, the report mtime is inside the run, and the inputs were unchanged after the run (see the snapshot).
- Final run after latest test additions and production fixes: yes. It ran on the final test files, and production code has been unchanged since 67a3e21.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/testsupport/ShiftableClock.kt` | 80/80 (100%) | 2/2 (100%) | 7/7 (100%) | 9/9 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/testsupport/TestSupportConfiguration.kt` | 70/70 (100%) | N/A (0/0) | 7/7 (100%) | 12/12 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/testsupport/TestSupportController.kt` | 108/109 (99.1%) | 2/4 (50%) | 7/7 (100%) | 24/24 (100%) |
| **Aggregate (summed counters, 3 files)** | 258/259 (99.6%) | 4/6 (66.7%) | 21/21 (100%) | 45/45 (100%) |

The aggregate covers all three scoped server files, and no scoped server file is unmeasured. The web/deploy harness files are excluded (no configured coverage provider for them) and were validated by the e2e runs above.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TestSupportController.kt:55` (2 of 4 branches, the false path of `if (tables.isNotEmpty())`) | Reset when the schema has no tables other than Flyway's history | Justified gap: not reachable with the Flyway schema, which always has application tables. Reset on a DB without data is tested (`reset is repeatable on an already empty database`) |
| `web/e2e/support/run-e2e.mjs` (not instrumented) | Health timeout path, `up` failure path, `E2E_BASE_URL` path, `E2E_KEEP`, signal handling | Default path, single spec and multi-worker were run. Signal handling, `E2E_BASE_URL` and `E2E_KEEP` were not run in this pass (see "Unable to test") |

## Unable to test

- CR-2 SIGTERM/SIGINT forwarding and exit 130/143: the sandbox denied my `timeout -s TERM ...` run. The only evidence is the fixer's manual run in `fixes.md`. A rerun by the parent is worth it if the evidence for this pass must be independent.
- The `E2E_BASE_URL` and `E2E_KEEP=1` paths of `run-e2e.mjs`, and the "non-fresh stack without a stored account is reset first" branch of `sharedAccount`: not run. The sandbox denied the command that keeps a stack running. No follow-up is needed for the acceptance criteria.
- No unit tests for `run-e2e.mjs`: the repo has no test runner for Node scripts outside `web/src`, and introducing one is out of scope.
- `docker compose` with real Docker Engine: not available. Podman with `podman-compose` was used through the `docker` shim.

## Summary

Tests added and all validation passed. The second pass found that the full server suite failed at the final revision ("too many clients already", TW-1). The fix is `@DirtiesContext` on the task's two e2e test classes, in test files only. With it, `./gradlew check` (759 tests), `sh scripts/check.sh` and all `npm run e2e` variants pass. No production defects were found. The parent must commit the two changed test files, and the changed tests should be included in the final code review.
