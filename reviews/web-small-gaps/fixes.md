# Fix Report

Date: 2026-11-08

Task: web-small-gaps

Scope: current task (findings TW-1 from reviews/web-small-gaps/tests.md and CR-1 from reviews/web-small-gaps/review.md)

## Fixed findings

### TW-1 — Search type alias lookup matches inherited Object.prototype keys

Files changed:
- `web/src/app/features/search/search-query.ts`

Problem:
`lookup()` read `ALIASES[key]` directly, so words such as `type:constructor` or `type:toString` resolved to inherited `Object.prototype` members, and the word was dropped instead of being kept as free text.

Fix:
The alias lookup now only takes own keys: `(Object.hasOwn(ALIASES, key) ? ALIASES[key] : undefined)`. `Object.hasOwn` is already used the same way elsewhere in the web app (calendar views, task detail). The test-writer's regression tests ("does not take an inherited object key (...)" in `search-query.spec.ts`) are unchanged and now pass.

Validation:
- `npm --prefix web run test:ci -- --include='src/app/features/search/**/*.spec.ts'` — Pass (4 files, 69 tests)

### CR-1 — Dev proxy does not forward /download

Files changed:
- `web/proxy.conf.json`
- `docs/ARCHITECTURE.md`

Problem:
Under `npm start` the dev proxy forwarded only `/api` and `/actuator`, so the Settings Android row's `/download/android/version` request and the `/download/android` link did not reach the server.

Fix:
Added a `/download` entry to `web/proxy.conf.json` with the same target and options as the existing entries, and updated the dev-proxy sentence in `docs/ARCHITECTURE.md` (Run table, Web row) to say it forwards `/api`, `/actuator` and `/download`.

Validation:
- `npm --prefix web run lint` — Pass (config file; behavior under `npm start` against a running server not exercised here)

## Not fixed

None.

## Validation summary

Focused tests:
- `npm --prefix web run test:ci -- --include='src/app/features/search/**/*.spec.ts'` — Pass, 69 tests in 4 files

Full test suite:
- Not run by the fixer (parent worker runs `.taskcheck`)

Lint/type-check/build:
- `npm --prefix web run lint` — Pass, all files pass linting (the test run also built the bundle without errors)

`.taskcheck`:
- Not run (left to the parent worker as instructed)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
