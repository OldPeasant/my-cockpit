# Fix Report

Date: 2026-11-01

Task: web-mail-single-click-open — Mail list: a single click opens the message

Scope: current task

## Fixed findings

### CR-1 — e2e specs still double-click the removed button.open

Files changed:
- `web/e2e/mail-read/read.spec.ts`
- `web/e2e/mail-compose/compose.spec.ts`
- `web/e2e/phase5/demo.spec.ts`

Problem:
The mail list row is now an `a.open` link with a routerLink, but three e2e specs located `button.open` and double-clicked it, so they would no longer find the row element.

Fix:
Each call is now `locator('a.open').click()` (a single click). The surrounding assertions are unchanged: read.spec.ts and demo.spec.ts still wait for the URL `/mail/messages/{id}` and the heading; compose.spec.ts still waits for the heading, as it did before. A grep of `web/e2e` finds no other `button.open` or `dblclick` uses.

Validation:
- `npx prettier --check e2e src/app/features/mail/list` — Pass
- `npm run e2e:typecheck` — Pass
- `grep -rn "button.open\|dblclick" web/e2e` — no matches
- Playwright not run by the fixer (the parent runs it)

### CR-2 — stale "row's button" comment on focusCursor

Files changed:
- `web/src/app/features/mail/list/mail-list-page.ts`

Problem:
The doc comment on focusCursor still said focus moves to the row's button.

Fix:
The comment now says the row's link.

Validation:
- `npm run lint` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `npx prettier --check e2e src/app/features/mail/list` — Pass
- `npm run e2e:typecheck` — Pass

Full test suite:
- Not run (Playwright e2e is left to the parent, as instructed; the change only touches e2e locators and a comment)

Lint/type-check/build:
- `npm run lint` — Pass ("All files pass linting.")

`.taskcheck`:
- Not run (the parent runs it)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
