# Fix Report

Date: 2026-11-01

Task: web-mail-search - Mail view: search box with 'search on server' option

Scope: current task, finding CR-1 from reviews/web-mail-search/review.md

## Fixed findings

### CR-1 — Switching Messages/Conversations during a search re-runs the search, including a slow server search

Files changed:
- `web/src/app/features/mail/list/mail-list-page.ts`
- `web/src/app/features/mail/list/mail-list-page.spec.ts`

Problem:
`setThreaded` always called `store.load(this.scope())`. While a search was shown, the scope included the search, so a view switch re-sent the same search (with `server=true` when the server option was on) and showed "Searching on the server…", although search results are always flat and the result does not change.

Fix:
`setThreaded` still stores the choice (the `threaded` signal and localStorage), but returns before the reload while `search()` is set. The choice applies when the search is cleared, because `clearSearch` loads `scope()` with the stored `threaded` value. New spec "keeps a server search on a view switch and lists conversations after clearing it" (search describe block): with a server search shown, switching to Conversations stores `true`, sends no new search and no list request, keeps the results and shows no server-search status; emptying the field then loads the folder list with `threaded: true`. The spec fails without the production change (checked by temporarily disabling the guard: 1 failed) and passes with it.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/mail/**/*.spec.ts'` — Pass (19 files, 604 tests)
- `cd web && npx ng lint --max-warnings=0` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/mail/**/*.spec.ts'` — Pass, 604 passed
- `cd web && npx prettier --check` on both changed files — Pass

Full test suite:
- Not run (web mail specs only; the parent runs `.taskcheck`)

Lint/type-check/build:
- `cd web && npx ng lint --max-warnings=0` — Pass (the test build compiled the changed TypeScript)

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
