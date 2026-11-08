# Fix Report

Date: 2026-11-08

Task: fix-mail-images-firefox - Mail images never load in Firefox (frame CSP 'self' in srcdoc)

Scope: current task; findings CR-1 and CR-2 of `reviews/fix-mail-images-firefox/review.md` (base 54f0e79f, task
commits 1c03e70f..fce80bf9).

## Fixed findings

### CR-1 — Firefox mail-read can overlap the `inbox-exclusive` project

Files changed:
- `web/playwright.config.ts`

Problem:
`inbox-exclusive` depended only on `chromium`, so the `firefox` project (which adds an item to the shared inbox via
"→ Inbox task") could still run while the inbox-counting specs ran.

Fix:
`inbox-exclusive` now has `dependencies: ['chromium', 'firefox']`; its comment says it runs after both projects.

Validation:
- `node .tmp/list.mjs` (runs `playwright test --list` in `web/` with a dummy `E2E_BASE_URL`, no stack) — Pass:
  config loads, `{ phase-demos: 35, chromium: 27, firefox: 1, inbox-exclusive: 7 }`, Total 70 tests in 29 files
- `npm --prefix web run lint` — Pass
- `npm --prefix web run e2e:typecheck` — Pass

### CR-2 — `web/e2e/README.md` still describes a chromium-only run and install

Files changed:
- `web/e2e/README.md`

Problem:
The README installed only chromium, said `npm run e2e` runs chromium only, and said inbox-exclusive runs after "all
other specs" without naming the new project.

Fix:
Install line is now `npx playwright install --with-deps chromium firefox` (comment column realigned); the run
description mentions the `firefox` project for `e2e/mail-read/`; the "Writing specs" section says inbox-exclusive runs
after both the `chromium` and `firefox` projects.

Validation:
- `npm --prefix web exec -- prettier --check web/playwright.config.ts web/e2e/README.md` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `playwright test --list` (dummy `E2E_BASE_URL`, via `.tmp/list.mjs`) — Pass, 70 tests listed, project split as above
- `npm --prefix web run e2e:typecheck` — Pass
- `npm --prefix web exec -- prettier --check web/playwright.config.ts web/e2e/README.md` — Pass

Full test suite:
- Not run: the change only touches Playwright project ordering and docs; the full e2e suite needs the Docker stack
  and is outside `.taskcheck`.

Lint/type-check/build:
- `npm --prefix web run lint` — Pass

`.taskcheck`:
- Not run by the fixer (no production or unit-test code changed); the parent worker runs it before `done`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
