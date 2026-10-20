# Code Review

Date: 2026-10-20

Task: p0-e2e-harness - Playwright e2e harness against the compose stack

Scope: `git diff 0d7d42c..HEAD` (server `core/testsupport` main and test, `deploy/docker-compose.e2e.yml`, `web/playwright.config.ts`, `web/e2e/**`, `web/package.json`, `web/angular.json`, `web/.gitignore`, `docs/ARCHITECTURE.md`, `reviews/p0-e2e-harness/tests.md`). Committed changes only; the worktree was clean.

Requirements:
- `tasks/p0-e2e-harness.md` acceptance criteria and the three recorded assumptions (clock = real time + offset; `/api/test/**` public and CSRF-free in profile `e2e` only; loggedIn fixture with a file lock shared by workers)
- `SPEC.md` (§4.1 login/TOTP, testing section), `docs/ARCHITECTURE.md` (Testing, profiles, environment variables), `AGENTS.md` project notes
- Parent instructions (focus: criteria, test-hook security, runner robustness, multi-worker fixtures, conventions)

Git range:
- Branch: main
- HEAD: 95c9cb6e706fe1648b0c2e840bd8b8d4be236ec7
- Base/start: 0d7d42c31558bd84b91a454775bfebb48d2d054f
- Commits reviewed: 4 (6dd2df7, 64f7e01, 5c3138c, 95c9cb6)

Summary: 0 P0, 0 P1, 0 P2, 3 P3

## What I checked

- AC1: ran `cd web && npm run e2e` (Podman 5.8.9 with the `docker compose` shim to podman-compose 1.6.0). Result: image built from cache, stack healthy after 19 s, 2 passed, stack torn down. Playwright CLI arguments go straight through (`process.argv.slice(2)`), so `npm run e2e -- <spec>` runs one spec. The compose stack uses profile `e2e`, Postgres runs on tmpfs, and the port is published on 127.0.0.1 only.
- AC2 with several workers (the test-writer could not cover this): ran `npm run e2e -- --workers=3 --fully-parallel --repeat-each=4`. Result: 8 passed using 3 workers. The setup ran once under the mkdir lock and each worker logged in with its own storage state. otplib 13 `generateSync({ epoch })` takes seconds (checked in `node_modules/otplib/dist/class.d.ts`), which matches `api.ts`.
- AC3: ran `cd server && ./gradlew test --tests '*testsupport*'`. Result: BUILD SUCCESSFUL. Both the controller and the configuration are `@Profile("e2e")`. The tests show that in the default and `dev` profiles there are no beans and the paths are 403 (no CSRF), 401 (no login) or 404 (with login). The public chain only matches `/api/test/**`, and its tests confirm it does not open `/api/v1/**`. The `@Primary` `ShiftableClock` replaces the `ClockConfiguration` clock, which `LoginThrottle`, `LoginService`, `SetupService` and the auditing `DateTimeProvider` use. Sessions are stored in the database (`auth_session`), so truncate ends them. The only other in-memory auth state, `LoginThrottle`, is cleared. No Flyway migration inserts seed data, so truncating every table except `flyway_schema_history` gives a fresh state.
- AC4: `support/smoke.spec.ts` covers UI login plus the shell, and the `loggedIn` fixture plus the shell. `docs/ARCHITECTURE.md` lists the run time, and `scripts/check.sh` has no e2e step.
- `npm run e2e:typecheck` passes.
- Security: no real secrets. The compose values are marked fictitious. `@Hidden` keeps the hooks out of `api/openapi.yaml`. A WARN at startup announces the open hooks. The Secure cookie is turned off only through env in the e2e compose file.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The e2e server image tag is shared by all clones on a host**
    - File: `deploy/docker-compose.e2e.yml:12`
    - Category: Integration
    - Problem: The runner gives each clone its own compose project (`run-e2e.mjs:564`, "so parallel clones on the same machine get separate stacks"). But every clone builds and runs the same fixed tag, `localhost/cockpit-server:e2e`. With podman-compose, `up -d --build` first runs `podman build -t localhost/cockpit-server:e2e` and then creates the container from the tag. If another clone (the agents share the user's Podman store) finishes its build between those two steps, this clone's stack runs the other clone's server code.
    - Why it matters: An e2e run can then pass or fail against code from another task. That is wrong evidence for a criterion. The window is small but real with several agents on one machine.
    - Suggested fix: Make the tag per project, e.g. `image: localhost/cockpit-server:${E2E_IMAGE_TAG:-e2e}`, and set `E2E_IMAGE_TAG` to the project name in `run-e2e.mjs`'s `env`. Optionally remove the image on teardown with `down --rmi local`.

- [ ] **CR-2 | SIGTERM to the runner does not stop the Playwright run**
    - File: `web/e2e/support/run-e2e.mjs:579-584` (with `runPlaywright`, `:641-649`)
    - Category: Quality
    - Problem: The SIGINT/SIGTERM handlers only set `interrupted`. That flag is checked only around `up` and in `waitForHealth`. A SIGTERM sent only to the node process (for example, a runner or `timeout` killing `npm run e2e`) is not passed on to the `npx playwright test` child. Because a handler is installed, node also does not exit. The whole suite keeps running to the end before teardown, and the exit status ignores the interruption. Ctrl-C still works because the terminal signals the whole process group.
    - Why it matters: A caller that times out and then escalates to SIGKILL leaves the stack running until the next `npm run e2e` in the same clone, which then runs `down -v` first. Interrupting a long future suite does not take effect quickly.
    - Suggested fix: Keep a reference to the spawned child. In the signal handler, call `child?.kill(signal)` and return a non-zero status when `interrupted` is set.

- [ ] **CR-3 | No automated check type-checks the e2e suites**
    - File: `web/package.json:16` (with `:17`)
    - Category: Tests
    - Problem: `e2e:typecheck` (`tsc -p e2e/tsconfig.json`) only runs by hand. `npm run check` (and so `.taskcheck`) runs lint over `e2e/**`, but lint does not check types. Playwright only transpiles. A type error in `support/fixtures.ts` or `api.ts`, which every later web task uses, then shows up only at e2e run time, or not at all on a branch nobody runs.
    - Why it matters: The fixtures are a shared contract for all later web tasks. A cheap static check would catch a broken fixture signature in `.taskcheck` without starting the stack.
    - Suggested fix: Add `npm run e2e:typecheck` to the `check` script. It takes a few seconds and needs no browser or stack.

## Review conclusion

No material issues found. All acceptance criteria are met by the code and by the runs I repeated (e2e default run, 3-worker e2e run, server testsupport tests). The three P3 items are optional hardening. Note: `python tasks.py verify p0-e2e-harness` still shows all four criteria unrecorded. The parent worker must record the evidence before `done`.
