# Fix Report

Date: 2026-10-20

Task: p0-web-api-client - Generated API client and HTTP infrastructure

Scope: current task, findings CR-1 and CR-2 from `reviews/p0-web-api-client/review.md`, approved by the parent worker.

## Fixed findings

### CR-1 — New i18n strings not extracted into `messages.xlf`

Files changed:
- `web/src/locale/messages.xlf`

Problem:
The `$localize` messages `http.error.unreachable`, `http.error.server`, `http.error.request` and `notification.dismiss` were missing from the committed source catalog.

Fix:
Ran `cd web && npm run i18n:extract` (7 messages extracted). The diff only adds the four trans-units. The existing `app.scaffold` and `app.title` entries are unchanged.

Validation:
- `npm run i18n:extract` — Pass ("Extraction Complete. (Messages: 7)")
- `git diff web/src/locale/messages.xlf` — 32 insertions, 0 deletions, only the four new ids

### CR-2 — ARCHITECTURE.md still describes the dev proxy as future work and leaves out `api:check`

Files changed:
- `docs/ARCHITECTURE.md`

Problem:
The Run table said the dev proxy would come with the first API task. The Testing table described `npm run check` without the `api:check` step.

Fix:
Two one-line edits. The Web row in Run now says the dev proxy (`web/proxy.conf.json`) forwards `/api` and `/actuator` to `localhost:8080`, which matches `web/proxy.conf.json`. The `npm run check` row in Testing now lists the generated-client freshness check (`api:check`; `npm run api:gen` regenerates) before lint, tests and the production build, which matches `web/package.json`.

Validation:
- `git diff docs/ARCHITECTURE.md` — 2 lines changed, both in the rows named by the finding

## Not fixed

None.

## Validation summary

Focused tests:
- `npm run check` in `web/` (api:check + lint + test:ci + ng build) — Pass: all files pass linting, 6 test files / 56 tests passed, production build succeeded

Full test suite:
- Web suite is covered by `npm run check` above. Server/android suites were not run: no server or android files changed.

Lint/type-check/build:
- Included in `npm run check` — Pass

`.taskcheck`:
- Not run: `sh scripts/check.sh` needed a permission approval this fixer session did not get. The parent worker must run it.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
