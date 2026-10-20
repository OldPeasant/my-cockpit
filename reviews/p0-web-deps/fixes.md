# Fix Report

Date: 2026-10-20

Task: p0-web-deps - Web dependencies, theme and i18n baseline

Scope: open findings CR-3 and CR-4 from `reviews/p0-web-deps/review.md` (CR-1 and CR-2 were accepted by the parent worker with recorded assumptions and were not part of this fix request).

## Fixed findings

### CR-4 — Project-wide `isolate: true` for web unit tests is not documented

Files changed:
- `docs/ARCHITECTURE.md`

Problem:
The `isolate: true` setting on the `web/angular.json` `test` target differs from the Angular unit-test builder default (`isolate: false`). It was explained only in a comment in `app.i18n.spec.ts`. A later task could remove it for speed without knowing that the i18n spec depends on it.

Fix:
Added one rule to the "Rules" list of the "Testing" section. It says that web unit tests run with Vitest `isolate: true` (the `web/angular.json` `test` target), so each spec file gets a fresh module graph. It also says that `app.i18n.spec.ts` needs this because Angular caches i18n template constants on first render, and that the setting must not be removed for speed. No other lines changed.

Validation:
- `cd web && npm run check` — Pass (lint: "All files pass linting"; Vitest: 2 files, 7 tests passed; production build OK)

### CR-3 — The i18n spec relies on per-file isolation, which the test builder did not turn on

Already fixed by the parent worker before this fixer ran. The fixer did not change any code for it.

Files changed (by the parent worker):
- `web/angular.json` (commit 4ca96bd: `"options": {"isolate": true}` on the `test` target)
- `web/src/app/app.i18n.spec.ts` (commits 4ca96bd and d8e43bf: comment)

Note: the fix request named these commits a81c577 and eb2681f. Those hashes do not exist in this clone, probably because the commits were rebased. The matching commits in the current history are 4ca96bd ("run unit tests isolated so the i18n spec does not depend on worker layout") and d8e43bf ("reword i18n spec isolation comment"). The review refers to the same change as 11176b0/c5af6ea.

Problem:
`app.i18n.spec.ts` loads translations before App is first rendered. Without per-file isolation, it could share a module graph with `app.spec.ts`, and its result would depend on how files are spread across workers.

Fix (verified by the fixer):
- `web/angular.json` lines 82-87: the `test` target has `"options": { "isolate": true }`. The review checked the builder source and confirmed that the builder passes this option through to Vitest.
- The comment in `app.i18n.spec.ts` (lines 5-7) correctly describes the caching on first render and points to the angular.json setting.
- The fix is correct and complete.

Validation:
- `cd web && npm run check` — Pass (2 test files, 7 tests passed)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npm run check` — Pass. The chain lint -> `test:ci` -> `ng build` completed: lint clean, Vitest 2 files / 7 tests passed, production build OK (main 208.52 kB, styles 9.59 kB).

Full test suite:
- Not run beyond the web part. The change is documentation-only, and `web/` is the only code area this task touches.

Lint/type-check/build:
- Covered by `cd web && npm run check` (eslint with `--max-warnings=0` and production `ng build`) — Pass.

`.taskcheck`:
- Not run. The fixer's sandbox required approval for `sh scripts/check.sh`. The parent worker runs it before `tasks.py done`.

Remaining unresolved findings:
0 (of the findings supplied to the fixer; CR-1 and CR-2 stay accepted P3 items with the parent's recorded assumptions)

## Final status

All validated findings fixed and validation passed.
