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
server log on failure and tears the stack down with its volumes. The server image is tagged per project
(`localhost/<project>:latest`, kept for the layer cache). SIGINT/SIGTERM stop the Playwright run, tear the stack
down and exit non-zero (130/143). Options (environment):

| Variable        | Effect                                                                        |
| --------------- | ----------------------------------------------------------------------------- |
| `E2E_COMPOSE`   | compose command, default `docker compose` (e.g. `podman-compose`)             |
| `E2E_PORT`      | host port of the server instead of a free one                                 |
| `E2E_KEEP=1`    | keep the stack running afterwards (prints the `down` command)                 |
| `E2E_BASE_URL`  | run against an already running e2e stack, no compose up/down                  |
| `E2E_IMAP_PORT` | host port of GreenMail's IMAP, default a free one (18143 with `E2E_BASE_URL`) |
| `E2E_FRESH_PORT` | host port of the fresh-install server `server-fresh`, default a free one        |
| `E2E_FRESH_BASE_URL` | with `E2E_BASE_URL`: that stack's `server-fresh`; without it, phase 2 step 5 is skipped |

The stack has a second server, `server-fresh`, with its own empty Postgres and data directory: a fresh install that
only the phase 2 demo (`e2e/phase2/`) uses, to restore its backup into (SPEC.md §13.2 step 5). `run-e2e.mjs` waits for
both and passes its address as `E2E_FRESH_BASE_URL`.

Reports: `test-results/` (traces and screenshots of failures), `playwright-report/` (`npx playwright show-report`).

## Writing specs

Specs live in `e2e/<feature>/*.spec.ts`. The phase demo scripts of SPEC.md §13.2 live in `e2e/phase<N>/` and run as
their own Playwright project `phase-demos` before all other specs (they reset the stack, trigger the global login
backoff and move the server clock). Every run includes them, also with a spec filter; `npm run e2e -- --no-deps <spec>`
skips them. Specs that need the whole shared inbox (`e2e/gtd-inbox/`) run in the project `inbox-exclusive`, after all
other specs. Import `test` and `expect` from `support/fixtures`:

- `account` (worker): the owner account. The first worker runs the setup through the API (TOTP code computed
  with otplib) and shares the credentials with the other workers.
- `loggedIn` (test): a page logged in as the owner, from a session created once per worker (API login, stored
  as Playwright storage state).

Helpers in `support/api.ts`: `totp(secret, at?)`, `setupAccount`, `loginViaApi`, and the e2e-only server hooks
`resetStack` (`POST /api/test/reset`: empties the database, resets clock and login lockout) and `setClock`
(`POST /api/test/clock {now}`: the server clock jumps to `now` and runs on from there). After `setClock`,
compute TOTP codes for the server's time; a jump of more than 30 days expires the worker's `loggedIn` session. A spec that resets the stack removes the shared account: run it
with `test.describe.configure({ mode: 'serial' })` and set up its own account (and, with several workers, don't
mix it with specs that use `loggedIn`).

Mail: the stack includes GreenMail (`greenmail`, SMTP 3025 / IMAP 3143, no TLS, any login accepted and its mailbox
created). `support/imap.ts` has `GREENMAIL_SERVERS` (the account settings as the server sees GreenMail) and a minimal
`Imap` client on the host port (`E2E_IMAP_PORT`) to create folders, deliver mail with APPEND and check the result with
SEARCH. Nothing syncs mail on a schedule in the e2e stack: `POST /api/test/mail/sync` (e2e profile) reads the folder list
and new headers of every account (`mail.testsupport`).

Test data is fictitious. `npm run e2e:typecheck` type-checks the suites, `npm run lint` lints them (both run in
`npm run check`).
