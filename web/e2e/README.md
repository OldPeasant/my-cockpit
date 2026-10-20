# End-to-end tests (Playwright)

Browser tests against the real stack: the server image (with the SPA) in profile `e2e` and a fresh
Postgres, from `deploy/docker-compose.e2e.yml`. Not part of `.taskcheck`.

```sh
cd web
npx playwright install --with-deps chromium     # once per Playwright version
npm run e2e                                     # all specs
npm run e2e -- support/smoke.spec.ts            # one spec (any Playwright CLI arguments work)
```

`npm run e2e` (`support/run-e2e.mjs`) builds and starts the stack on a free port of 127.0.0.1 (one compose
project per clone), waits for `/actuator/health`, runs Playwright (chromium) with `E2E_BASE_URL`, prints the
server log on failure and tears the stack down with its volumes. Options (environment):

| Variable | Effect |
|---|---|
| `E2E_COMPOSE` | compose command, default `docker compose` (e.g. `podman-compose`) |
| `E2E_PORT` | host port of the server instead of a free one |
| `E2E_KEEP=1` | keep the stack running afterwards (prints the `down` command) |
| `E2E_BASE_URL` | run against an already running e2e stack, no compose up/down |

Reports: `test-results/` (traces and screenshots of failures), `playwright-report/` (`npx playwright show-report`).

## Writing specs

Specs live in `e2e/<feature>/*.spec.ts`. Import `test` and `expect` from `support/fixtures`:

- `account` (worker): the owner account. The first worker runs the setup through the API (TOTP code computed
  with otplib) and shares the credentials with the other workers.
- `loggedIn` (test): a page logged in as the owner, from a session created once per worker (API login, stored
  as Playwright storage state).

Helpers in `support/api.ts`: `totp(secret, at?)`, `setupAccount`, `loginViaApi`, and the e2e-only server hooks
`resetStack` (`POST /api/test/reset`: empties the database, resets clock and login lockout) and `setClock`
(`POST /api/test/clock {now}`: the server clock jumps to `now` and runs on from there). After `setClock`,
compute TOTP codes for the server's time. A spec that resets the stack removes the shared account: run it
with `test.describe.configure({ mode: 'serial' })` and set up its own account (and, with several workers, don't
mix it with specs that use `loggedIn`).

Test data is fictitious. `npm run e2e:typecheck` type-checks the suites, `npm run lint` lints them.
