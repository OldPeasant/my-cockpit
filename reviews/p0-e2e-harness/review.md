# Code Review

Date: 2026-10-20

Task: p0-e2e-harness - Playwright e2e harness against the compose stack

Scope: `git diff bdd9ca3d084505a3c0d2a00e2d0155ff56d57cc7..872129d01098e2422ebda54eaaeb7dc58434c931`. This is the third and final pass, on revision 872129d. The scope covers the server `core/testsupport` main and test code, `deploy/docker-compose.e2e.yml`, `web/playwright.config.ts`, `web/e2e/**`, `web/package.json`, `web/angular.json`, `web/.gitignore`, `docs/ARCHITECTURE.md` and the review artifacts. This pass focuses on commit 872129d. My second pass already reviewed everything up to 67a3e21. I reviewed committed changes only.

Requirements:
- `tasks/p0-e2e-harness.md`: the four acceptance criteria and three recorded assumptions. The clock is real time plus an offset. `/api/test/**` is public and CSRF-free in profile `e2e` only. The loggedIn fixture shares one setup through a file lock.
- `SPEC.md` §4.1, `docs/ARCHITECTURE.md` (Testing, profiles, environment variables), `AGENTS.md` project notes
- Earlier reviews (CR-1..CR-4), `reviews/p0-e2e-harness/fixes.md`, `reviews/p0-e2e-harness/tests.md` (TW-1)
- Parent instructions for the third pass: review the `@DirtiesContext` change, check that it matches `CsrfCookieWebTests`, confirm the rest of the scope is unchanged, and run the server testsupport tests

Git range:
- Branch: main
- HEAD: 872129d01098e2422ebda54eaaeb7dc58434c931
- Base/start: bdd9ca3d084505a3c0d2a00e2d0155ff56d57cc7
- Commits reviewed: 7 (8dd904f, 146cc20, 89be721, 9d1e346, ac3a929, 67a3e21, 872129d)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Commands run

Third pass (revision 872129d):
- `git diff --stat 67a3e21..872129d -- . ':!reviews'`: only `TestSupportLoginWebTests.kt` and `TestSupportWebTests.kt` changed, with 3 added lines each (an import, the annotation and a comment). No production, web, compose or docs file changed after the second pass.
- `cd server && ./gradlew test --tests '*testsupport*'`: `BUILD SUCCESSFUL in 44s`. The JUnit XML shows 17 tests, 0 failures, 0 errors and 0 skipped: `TestSupportWebTests$E2eProfile` 5, `$DefaultProfile` 2, `$DevProfile` 1, `TestSupportLoginWebTests` 9. The log shows HikariPool shutdowns during the run, so the e2e contexts are closed. This run replaces the second pass's two attempts that collided with another Gradle run. No other Gradle run used this clone this time; the only other Gradle client ran in another clone (agent-4).
- `python tasks.py verify p0-e2e-harness` (read-only listing): all four criteria are still unrecorded.

Second pass (revision 67a3e21, still valid because the files were not changed after it): `npm run -s e2e:typecheck` passed. `node --check e2e/support/run-e2e.mjs` passed. Prettier check: clean. `npm run e2e` on Podman: `2 passed`, per-project image tag `cockpit-e2e-72159f73`, teardown done, exit 0.

## Review of commit 872129d (TW-1 fix)

- **Correctness:** `@DirtiesContext` uses the default `classMode = AFTER_CLASS`. That closes the e2e-profile context, and with it its Hikari pool, after the class. These contexts have a key nobody else uses (profile `e2e` + MockMvc), so caching them only kept their connections open against the shared Testcontainer (`PostgresTestBase`). The `ProfileTests$E2e` and `FlywayConfigurationTests` failures in TW-1 ("too many clients") come from that. Closing the contexts fixes the cause; it does not hide it. In `TestSupportWebTests` the annotation sits on the `@Nested` `E2eProfile` class and not on the outer class. That is correct: only the e2e context is closed, and the default- and dev-profile contexts stay shared with the rest of the suite. Spring applies `@DirtiesContext` to a `@Nested` class by itself, and my run shows the contexts closing.
- **Consistency:** it matches `core/auth/CsrfCookieWebTests.kt:37-38` in placement (between `@SpringBootTest` and `@AutoConfigureMockMvc`) and in its comment ("so it does not hold database connections for the rest of the run").
- **Cost:** `TestSupportWebTests$E2eProfile` and `TestSupportLoginWebTests` could otherwise share one cached context. Now each starts its own context, a few seconds per run. That is acceptable, and much better than pools that stay open.
- **Test assertions:** unchanged. No test was weakened, removed or skipped.
- Lint and format: both changes follow the files' tab indentation and import order. `tests.md` reports `./gradlew check` (759 tests, which includes ktlint) and `sh scripts/check.sh` passing on this revision.

No new findings from this commit.

## Earlier findings

- **CR-1 (shared e2e image tag): resolved** in 67a3e21. The image tag is now set per project through `E2E_IMAGE` (`deploy/docker-compose.e2e.yml:13`, `run-e2e.mjs:42`). Unchanged since then.
- **CR-2 (SIGTERM not forwarded to Playwright): resolved** in 67a3e21 for the Playwright phase. Unchanged since then.
- **CR-3 (no automated type check of e2e suites): resolved** in 67a3e21. `npm run check` runs `e2e:typecheck`. Unchanged since then.
- **CR-4 (P3, SIGTERM during `compose up --build`): open.** It is optional hardening and does not block the task. `run-e2e.mjs` has not changed since the second pass, so the finding below still applies as written.

## Acceptance criteria (code and runs)

- AC1: `npm run e2e` runs `down -v`, then `up -d --build` (profile `e2e`, Postgres on tmpfs), then polls the health endpoint, runs Playwright on chromium and finally runs `down -v`. Arguments are passed through to Playwright. My second-pass run confirmed this, and nothing in this part changed after it.
- AC2: the worker-scoped `account` / `sessionState` / `loggedIn` fixtures do the setup once under an mkdir lock, using a TOTP from otplib. The first pass covered this with a 3-worker run. The fixtures are unchanged.
- AC3: `TestSupportController` and `TestSupportConfiguration` are both `@Profile("e2e")`. Clock and reset are covered by tests, and so is their absence in the default and dev profiles. This pass re-ran these tests on 872129d: 17 passed.
- AC4: `support/smoke.spec.ts` passed in my second-pass run. The run time is recorded in `docs/ARCHITECTURE.md`. `scripts/check.sh` has no e2e step.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-4 | A SIGTERM sent only to the runner is not handled while the stack is building or starting** (open, unchanged from the second pass)
    - File: `web/e2e/support/run-e2e.mjs:57-58` (with `:44`, `:25-30`)
    - Category: Quality
    - Problem: `compose up -d --build` runs through `spawnSync`, which blocks Node's event loop. If only the node process receives SIGTERM during this phase (for example, `timeout npm run e2e`), the signal is not forwarded to compose. The installed handler also keeps node from exiting. It runs only after `up` has returned. The stack is then torn down and the runner exits 143. This phase is the longest of the run: 77 s in my run, and minutes on a cold clone. Ctrl-C is not affected, because the terminal signals the whole process group. The README says SIGINT/SIGTERM stop the run. That is only true after `up` has returned.
    - Why it matters: some callers escalate to SIGKILL after a grace period (`timeout -k`, an agent runner). They kill node before the deferred teardown runs, and the stack then keeps running until the next `npm run e2e` runs its initial `down -v`.
    - Suggested fix: run `up` with the async `spawn` and forward the signal to the child, the same way as `playwrightChild`. Alternatively, document that SIGTERM takes effect only once the stack is up. This is optional and does not block the task.

## Review conclusion

No material issues found. Commit 872129d is a correct, minimal fix for TW-1, scoped to tests only. It matches the existing `CsrfCookieWebTests` pattern, and the testsupport tests pass on the final revision (17 of 17). The rest of the scope is unchanged since the second pass. CR-1 to CR-3 remain resolved. CR-4 is open optional P3 hardening. Before `done`, the parent worker must still record evidence for all four acceptance criteria with `tasks.py verify`; none is recorded yet.
