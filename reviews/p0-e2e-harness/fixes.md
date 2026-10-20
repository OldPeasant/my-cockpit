# Fix Report

Date: 2026-10-20

Task: p0-e2e-harness - Playwright e2e harness against the compose stack

Scope: findings CR-1, CR-2, CR-3 of `reviews/p0-e2e-harness/review.md` (all P3), approved by the parent worker. Base 0d7d42c31558bd84b91a454775bfebb48d2d054f, HEAD 00b0c6a. Changes are uncommitted in the worktree.

## Fixed findings

### CR-1 — The e2e server image tag is shared by all clones on a host

Files changed:
- `deploy/docker-compose.e2e.yml`
- `web/e2e/support/run-e2e.mjs`
- `web/e2e/README.md`

Problem:
Every clone built and ran the fixed tag `localhost/cockpit-server:e2e`. If another clone's build finished between this clone's build and container creation, the stack could run the other clone's server code.

Fix:
The compose file now uses `image: ${E2E_IMAGE:-localhost/cockpit-server:e2e}`. The runner sets `E2E_IMAGE=localhost/<project>:latest`, where `<project>` is the per-clone compose project name (`cockpit-e2e-<hash>`). The image is kept after teardown for the layer cache. The README documents the per-project tag.

Validation:
- `cd web && npm run e2e` — Pass (2 passed). The build log shows `COMMIT localhost/cockpit-e2e-72159f73:latest`, and no `cockpit-e2e` containers remained afterwards (`podman ps -a`).

### CR-2 — SIGTERM to the runner does not stop the Playwright run

Files changed:
- `web/e2e/support/run-e2e.mjs`
- `web/e2e/README.md`

Problem:
The SIGINT/SIGTERM handlers only set a flag. The flag was not passed on to the Playwright child, so a SIGTERM sent only to node let the whole suite run to the end. The exit status also ignored the interruption.

Fix:
The signal handlers are now installed at module level, so they also cover the `E2E_BASE_URL` path. They record the first signal and forward each signal to the running Playwright child (`playwrightChild?.kill(signal)`). `runPlaywright` keeps the child reference and does not start Playwright if the run was already interrupted. An interrupted run exits with 130 (SIGINT) or 143 (SIGTERM) after teardown and prints `[e2e] interrupted by <signal>`. On interruption it skips the "server log on failure" dump. Teardown in `finally` is unchanged. The README documents this behavior.

Validation:
- `.tmp/sigterm-test.sh` (runs `node e2e/support/run-e2e.mjs --repeat-each=300 --workers=1` in the background and sends `kill -TERM` to the node PID 3 s after Playwright prints `Running`) — Pass. Playwright stopped after 5 of 600 tests, the log shows `[e2e] tearing down the stack` and then `[e2e] interrupted by SIGTERM`, the runner exit code was 143, no `cockpit-e2e` containers remained, and no `playwright test` process remained.

### CR-3 — No automated check type-checks the e2e suites

Files changed:
- `web/package.json`
- `web/e2e/README.md`

Problem:
`e2e:typecheck` only ran by hand, so `.taskcheck` did not catch type errors in the shared fixtures.

Fix:
The `check` script now runs `npm run e2e:typecheck` after `npm run lint`. The README says that both lint and the type check run in `npm run check`.

Validation:
- `sh scripts/check.sh` (`.taskcheck`, which runs `npm run -s check` in web) — Pass

Note: `npx prettier --check e2e ...` reported `web/e2e/README.md` because the options table, which existed before this fix, was not aligned. I ran `npx prettier --write e2e/README.md` on the file this fix already edits. It only realigned the table and changed no content.

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npm run e2e:typecheck` — Pass
- `cd web && npx ng lint --max-warnings=0` — Pass (all files pass linting)
- `cd web && npx prettier --check e2e playwright.config.ts package.json` — Pass (after formatting the README table)
- `cd web && node --check e2e/support/run-e2e.mjs` — Pass
- `cd web && npm run e2e` (Podman with the `docker compose` shim to podman-compose) — Pass, 2 passed, stack healthy after 40 s, torn down, no `cockpit-e2e` containers left
- SIGTERM path (`.tmp/sigterm-test.sh`) — Pass, exit 143, teardown done, no containers or Playwright processes left

Full test suite:
- `sh scripts/check.sh` — Pass ("all checks passed": server, web including e2e:typecheck, android)

Lint/type-check/build:
- Covered by `scripts/check.sh` above — Pass

`.taskcheck`:
- Pass

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
