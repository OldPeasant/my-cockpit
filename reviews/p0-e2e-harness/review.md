# Code Review

Date: 2026-10-20

Task: p0-e2e-harness - Playwright e2e harness against the compose stack

Scope: `git diff bdd9ca3d084505a3c0d2a00e2d0155ff56d57cc7..67a3e21` (second pass on the final code). This covers the server `core/testsupport` main and test code, `deploy/docker-compose.e2e.yml`, `web/playwright.config.ts`, `web/e2e/**`, `web/package.json`, `web/angular.json`, `web/.gitignore`, `docs/ARCHITECTURE.md` and the review artifacts. I paid particular attention to fix commit 67a3e21. I reviewed committed changes only and ignored uncommitted test-writer edits in the worktree.

Requirements:
- `tasks/p0-e2e-harness.md`: the four acceptance criteria and three recorded assumptions (the clock is real time plus an offset; `/api/test/**` is public and CSRF-free in profile `e2e` only; the loggedIn fixture shares one setup through a file lock)
- `SPEC.md` §4.1, `docs/ARCHITECTURE.md` (Testing, profiles, environment variables), `AGENTS.md` project notes
- The earlier review (CR-1..CR-3) and `reviews/p0-e2e-harness/fixes.md`
- Parent instructions: second pass, focused on the per-project `E2E_IMAGE` tag, signal forwarding in `run-e2e.mjs`, and `e2e:typecheck` in `npm run check`

Git range:
- Branch: main
- HEAD: 67a3e214e437650d65b362380e308eac6cecaeea
- Base/start: bdd9ca3d084505a3c0d2a00e2d0155ff56d57cc7
- Commits reviewed: 6 (8dd904f, 146cc20, 89be721, 9d1e346, ac3a929, 67a3e21)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Commands run

- `cd web && npm run -s e2e:typecheck`: pass (exit 0)
- `cd web && node --check e2e/support/run-e2e.mjs`: pass
- `cd web && npx prettier --check e2e playwright.config.ts package.json`: all files formatted
- `cd web && npm run e2e` (Podman, `docker compose` shim to podman-compose): pass. The build log shows `COMMIT localhost/cockpit-e2e-72159f73:latest` (the per-project tag from CR-1). The stack was healthy after 77 s (the server jar was rebuilt) and both smoke tests passed: `2 passed (3.2s)`. Afterwards the log shows `[e2e] tearing down the stack`, and the runner exited 0.
- `cd server && ./gradlew test --tests '*testsupport*'`: could not complete, twice. Both attempts failed inside Gradle's own result handling (`NoSuchFileException .../in-progress-results-generic.bin`, then `EOFException`). `pgrep` showed another Gradle test worker running in this same clone's `server/build` (the concurrent test-writer), so the two runs collided on the results directory. No test assertion failed. Commit 67a3e21 does not touch any server file. The server code is the same code the first pass reviewed and ran successfully (`BUILD SUCCESSFUL`), and I re-read it in full for this pass.
- `python tasks.py verify p0-e2e-harness` (read-only listing): all four criteria are still unrecorded.

## Earlier findings (first pass)

- **CR-1 (shared e2e image tag): resolved.** `deploy/docker-compose.e2e.yml:13` now uses `image: ${E2E_IMAGE:-localhost/cockpit-server:e2e}`, and `run-e2e.mjs:42` sets `E2E_IMAGE=localhost/<project>:latest` in the env that every compose call uses. My run confirms the per-project tag. When the compose file is used directly, it still falls back to the old tag.
- **CR-2 (SIGTERM not forwarded to Playwright): resolved for the Playwright phase.** The handlers are now installed at module level (`run-e2e.mjs:23-30`) and forward the signal to `playwrightChild`. They also cover the `E2E_BASE_URL` path. `runPlaywright` does not start Playwright after an interruption (`:111`). The exit status is 130/143 after teardown (`:125-128`). The server-log dump is skipped on interruption (`:76`). The fixer's SIGTERM test (exit 143, stack and Playwright gone) is consistent with the code. A gap remains during the `compose up --build` phase; see CR-4.
- **CR-3 (no automated type check of e2e suites): resolved.** `web/package.json:62` runs `npm run e2e:typecheck` in `check`, which `scripts/check.sh` (`.taskcheck`) runs. I ran it and it passes.

No regressions found in the fix commit. Other changes in it: the README now documents the per-project tag, the signal behavior and the check, and its table was realigned by prettier (format only).

## Acceptance criteria (code and runs)

- AC1: `npm run e2e` runs `down -v`, then `up -d --build` on the e2e compose file (profile `e2e`, Postgres on tmpfs), then polls `/actuator/health`, runs `npx playwright test` (chromium project only) and finally `down -v`. Arguments pass through with `process.argv.slice(2)`. Confirmed by my run above.
- AC2: the worker-scoped `account` fixture runs the setup through the API under an mkdir lock (TOTP from otplib `generateSync`) and shares the account through a file. `sessionState` logs in once per worker and stores the storage state. `loggedIn` uses that state. The first pass covered the multi-worker case with a 3-worker run. The fix commit does not change the fixtures.
- AC3: `TestSupportController` and `TestSupportConfiguration` are both `@Profile("e2e")` and `@Hidden`. The public filter chain matches only `/api/test/**`. Reset truncates every table except `flyway_schema_history`, resets the clock and clears the login throttle. `TestSupportWebTests` / `TestSupportLoginWebTests` cover the endpoints being absent in other profiles. These files are unchanged since the first pass, which ran them successfully. This pass could not re-run them (see Commands run).
- AC4: `support/smoke.spec.ts` covers a UI login that ends on the shell, and the `loggedIn` fixture opening the shell. Both passed in my run. `docs/ARCHITECTURE.md` records the run time, and `scripts/check.sh` has no e2e step.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-4 | A SIGTERM sent only to the runner is not handled while the stack is building or starting**
    - File: `web/e2e/support/run-e2e.mjs:57-58` (with `:44`, `:25-30`)
    - Category: Quality
    - Problem: `compose up -d --build` runs through `spawnSync`, which blocks Node's event loop. A SIGTERM sent only to the node process during this phase (the same case CR-2 was about, e.g. `timeout npm run e2e`) is not forwarded to the compose process. Because a handler is installed, it also does not end node. The handler runs only after the build and `up` have finished; then `:59` tears the stack down and the runner exits 143. On a cold or changed source, this phase is the longest of the run (77 s in my run, minutes on a cold clone). Ctrl-C is not affected because the terminal signals the whole process group. The README (`web/e2e/README.md:400`) says SIGINT/SIGTERM stop the run, which is true only after `up` has returned.
    - Why it matters: a caller that sends SIGTERM and escalates to SIGKILL after a grace period (`timeout -k`, an agent runner) kills node before the deferred teardown runs. The started stack then keeps running until the next `npm run e2e` in that clone runs its initial `down -v`.
    - Suggested fix: run `up` with the async `spawn`, keep the child like `playwrightChild`, and forward the signal to it from the same handler. Or state in the README that a SIGTERM takes effect only once the stack is up. This is optional hardening and does not block the task.

## Review conclusion

No material issues found. CR-1, CR-2 and CR-3 are resolved, and the fix commit introduces no regressions. My e2e run confirms AC1 and AC4 again, including the new per-project image tag. CR-4 is optional P3 hardening. Before `done`, the parent worker must still record evidence for all four criteria with `tasks.py verify` (none is recorded yet). It should re-run `./gradlew test --tests '*testsupport*'` when no other Gradle run is using this clone's `server/build`, because my two attempts collided with a concurrent run.
