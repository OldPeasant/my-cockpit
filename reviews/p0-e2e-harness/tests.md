# Test Report

Date: 2026-10-20

Task: p0-e2e-harness - Playwright e2e harness against the compose stack

Scope: base `0d7d42c31558bd84b91a454775bfebb48d2d054f` .. HEAD `5c3138c61a70d418464762265421129a04e428f5` (branch `main`), task commits `6dd2df73c0daa3a981f6b1f4ec6304c8c10acdf6` (server hooks), `64f7e01e81fef939628049f47e8e05aaf439f25b` (compose, Playwright runner, fixtures, smoke spec), `5c3138c61a70d418464762265421129a04e428f5` (docs, run time). The parent named c217073/489a822/c43349e; in this clone the same three task commits have the hashes above (rebased); `git log 0d7d42c..HEAD` contains only these three. Uncommitted at measurement: only the new test file below.

Requirements:
- `tasks/p0-e2e-harness.md` acceptance criteria and recorded assumptions (clock = real time + offset; `/api/test/**` public and CSRF-free in profile `e2e` only; reset also resets the clock offset and the login throttle)
- `AGENTS.md`, `docs/ARCHITECTURE.md` ("Coverage", e2e run-time table), parent instructions

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/testsupport/TestSupportLoginWebTests.kt` (new, untracked; sha256 `a04290d9f34cd0c6edbfca6cb825e54867be4438e28edcaf8be4318fcad1f64f`)

The existing `ShiftableClockTests.kt` and `TestSupportWebTests.kt` were reviewed and left unchanged. No web/e2e support files were changed.

## Tests added or updated

Count: 9 (new class `TestSupportLoginWebTests`, profile `e2e`, real account created through `SetupService`, MockMvc against the real security chains and Postgres Testcontainer)

- `reset ends existing sessions and removes recovery codes, so setup is required again`: with a login session and recovery codes present, reset empties `app_user`, `recovery_code`, `auth_session`; the old session cookie gets 401 and the cookie is cleared; setup status is `setupRequired: true`; a new setup with other credentials returns 201 right away.
- `reset is repeatable on an already empty database`
- `only POST resets, a GET leaves the database alone`: GET `/api/test/reset` and `/api/test/clock` return 405, and the account is still there.
- `the public test chain does not open the rest of the API`: in profile `e2e`, `/api/v1/auth/me` and `/api/v1/settings` still return 401, login without CSRF returns 403, and the paths `/api/testing/reset` and `/api/test-reset` are not matched by the test chain (403 or 401).
- `clock answers the time it was set to, also for an input with a zone offset`: `+02:00` input is read as the matching UTC instant, and both the response and the server clock show it.
- `after the clock is set, TOTP codes must be computed for the server time`: after the clock is moved 3 days ahead, a code for real time gets 401 and a code for the server time gets 200.
- `moving the clock past the idle timeout ends a session`: the clock moves +13 h, so a session without "remember device" gets 401.
- `the login lockout runs out on the shifted clock`: 5 failures, then 429 with Retry-After; after the clock moves +1 min, login returns 200.
- `reset lifts an active login lockout`: an active 429 lockout, then reset and a new setup, then login returns 200.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 `npm run e2e` brings up the compose stack (profile e2e, fresh Postgres), waits for health, runs chromium, tears down | smoke.spec.ts (2 tests) run through the runner | `cd web && npm run e2e` (Podman via `podman-compose` docker shim) | Pass: image built, `[e2e] stack healthy after 68 s`, 2 passed (2.7s), stack torn down (`podman ps --filter name=cockpit-e2e` empty afterwards) |
| AC1 `npm run e2e -- SPEC` runs one spec | smoke.spec.ts, `-g "loggedIn fixture"` | `cd web && npm run e2e -- e2e/support/smoke.spec.ts -g "loggedIn fixture"` | Pass: healthy after 19 s, 1 test run, 1 passed, torn down |
| AC2 `loggedIn` fixture does setup (TOTP via otplib) once per worker and stores the session | `the loggedIn fixture opens the shell without signing in` (and `account` fixture via the first test) | `npm run e2e` (both runs above) | Pass (1 worker; multi-worker sharing through the lock was not exercised) |
| AC3 `POST /api/test/clock {now}` sets the server clock (e2e only) | `TestSupportWebTests$E2eProfile` (clock tests, setup abandoned-after), `ShiftableClockTests` (5), new: `clock answers the time ... zone offset`, `TOTP ... server time`, `moving the clock past the idle timeout ...`, `the login lockout runs out on the shifted clock` | `cd server && ./gradlew ktlintCheck test --tests '*testsupport*'` | Pass (22 tests, 0 failures) |
| AC3 `POST /api/test/reset` empties the DB (e2e only) | `E2eProfile.reset empties the database ...`, new: `reset ends existing sessions and removes recovery codes ...`, `reset is repeatable ...`, `only POST resets ...`, `reset lifts an active login lockout` | same | Pass |
| AC3 both absent in other profiles | `TestSupportWebTests$DefaultProfile` (2), `$DevProfile` (1); new: `the public test chain does not open the rest of the API` (e2e chain limited to `/api/test/**`) | same | Pass |
| AC4 smoke spec (login, see shell) passes; run time recorded in ARCHITECTURE.md; not in `.taskcheck` | smoke.spec.ts | `npm run e2e`; `grep e2e docs/ARCHITECTURE.md` (line 130 records ~20 s with cached layers); `scripts/check.sh` has no e2e step | Pass. Observed: 68 s to healthy on the first run (image rebuilt after a source change, which the doc mentions), 19 s on the cached second run |
| e2e TS sources type-check and lint | n/a | `cd web && npm run e2e:typecheck`; `npx ng lint --max-warnings=0` | Pass (no errors; "All files pass linting.") |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests '*testsupport*'`

Result:

Pass. BUILD SUCCESSFUL. ShiftableClockTests 5/5, TestSupportWebTests$E2eProfile 5/5, $DefaultProfile 2/2, $DevProfile 1/1, TestSupportLoginWebTests 9/9. No compiler warnings (a deprecated Long `shouldBeBetween` in the first draft was replaced with the comparable one).

## Full test execution

Command:

`cd server && ./gradlew test --rerun jacocoTestReport check`

Result:

Pass. BUILD SUCCESSFUL in 1m 24s. 61 result files, 731 tests, 0 failures, 0 errors, 0 skipped.

Also: `cd web && npm run e2e` (2 passed), `npm run e2e -- e2e/support/smoke.spec.ts -g "loggedIn fixture"` (1 passed), `npm run e2e:typecheck` (pass), `npx ng lint --max-warnings=0` (pass).

## .taskcheck

Result:

Pass. `sh scripts/check.sh` prints "== all checks passed" (server, web, android).

## Bugs found

None. No production defects found.

Observations for the parent (design notes, not defects; no test fails):
- The e2e security chain (`@Order(HIGHEST_PRECEDENCE)`, `securityMatcher("/api/test/**")`) has no session filter, so `/api/test/**` requests are always anonymous. That is fine for these hooks, but a later hook that needs the logged-in user would not see one.
- After `POST /api/test/clock`, logins need TOTP codes for the server time (`totp(secret, serverNow)` in `api.ts`, now covered by a server test). The worker-scoped `loggedIn` session is a remembered (30-day) session. A spec that moves the clock more than 30 days ahead, or calls reset, invalidates it for later tests of the same worker. `api.ts` documents this for reset but not for large clock jumps.
- The multi-worker path of the `account` fixture (mkdir lock, shared `account.json`) only ran with 1 worker here (`Running 2 tests using 1 worker`).

## Coverage

Status: verified (server, JaCoCo). Not applicable to the web/deploy files (see below).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equals whole-file coverage, because all three scoped files were added by the task (`git diff --name-status 0d7d42c..HEAD -- server/src/main web/src android/app/src/main` shows only `A` entries). The changed-branch figure is the same branch counter.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-e2e-harness`; base `0d7d42c31558bd84b91a454775bfebb48d2d054f` (given by parent); HEAD `5c3138c61a70d418464762265421129a04e428f5`; task ownership from `git log 0d7d42c..HEAD` (3 commits, all `p0-e2e-harness:`), which matches the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD above plus untracked `TestSupportLoginWebTests.kt` sha256 `a04290d9...f1f64f`; sha256 of the measured sources: `ShiftableClock.kt` f0e5eda6..., `TestSupportConfiguration.kt` 5fddfcdb..., `TestSupportController.kt` efc4348b..., `ShiftableClockTests.kt` 5b645847..., `TestSupportWebTests.kt` c8421595... Recorded before the run and checked again after it with `sha256sum -c`: all OK.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/core/testsupport/{ShiftableClock,TestSupportConfiguration,TestSupportController}.kt`. Excluded from unit coverage: `web/e2e/support/{run-e2e.mjs,api.ts,fixtures.ts}`, `web/playwright.config.ts`, `deploy/docker-compose.e2e.yml` (e2e harness and configuration outside `web/src`; Vitest coverage does not instrument them; they were run end to end by `npm run e2e` instead). Not in scope: docs, `web/angular.json`, `web/package.json`, `web/.gitignore`. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (project configuration unchanged); `./gradlew test --rerun jacocoTestReport check` in `server/`; full test suite (`--rerun` forces a fresh test execution).
- Run start/completion time and exit status: started after 2026-10-20T14:09:48+02:00, report written 2026-10-20T14:11:25+02:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 `637972f9a3e2c2cdc8d0e3806c2c9693160cc2edcce98088d875b07ea9b18ba6`.
- Evidence outputs are fresh and inputs stayed unchanged: yes (the report's mtime is inside the run window, the test task was forced to rerun, and the source hashes were unchanged after the run).
- Final run after latest test additions and production fixes: yes (no production changes; the run includes the final test file).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/testsupport/ShiftableClock.kt` | 80/80 (100%) | 2/2 (100%) | 7/7 (100%) | 9/9 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/testsupport/TestSupportConfiguration.kt` | 70/70 (100%) | N/A (0/0) | 7/7 (100%) | 12/12 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/testsupport/TestSupportController.kt` | 108/109 (99.1%) | 2/4 (50%) | 7/7 (100%) | 24/24 (100%) |
| **Aggregate (summed counters, 3 files)** | 258/259 (99.6%) | 4/6 (66.7%) | 21/21 (100%) | 45/45 (100%) |

The aggregate covers all three scoped server files; nothing is unmeasured. The web/deploy harness files above are excluded from it (they were validated by running e2e).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TestSupportController.kt:55` (2 of 4 branches: `if (tables.isNotEmpty())` false path) | Reset when the schema has no tables except Flyway's history | Justified gap: unreachable with the Flyway schema (there are always application tables). Reset on an empty-data DB is tested (`reset is repeatable ...`) |

## Unable to test

- Multi-worker sharing of the `account` fixture (lock directory, one setup per stack): not run with more than 1 worker. A follow-up is worth it once there are enough specs to run in parallel.
- The `E2E_BASE_URL`, `E2E_KEEP` and `E2E_COMPOSE` paths of `run-e2e.mjs`, and the "non-fresh stack without known credentials is reset first" branch of `sharedAccount`: not run. Only the default compose path was run, through the Podman docker shim, not Docker Engine.
- `docker compose` with real Docker Engine: not available here. Podman (`podman-compose`) was used.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient; no production defects were found.
