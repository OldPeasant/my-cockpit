# Fix Report

Date: 2026-10-21

Task: web-capture - Quick capture dialog

Scope: current task, findings CR-1 and CR-2 of `reviews/web-capture/review.md` (base 814f9162, task commits 368b8e94, ef8659ac, b009c1e7)

## Fixed findings

### CR-1 — Preview shows the previous capture's result while a new text is typed

Files changed:
- `web/src/app/features/capture/capture-dialog.ts`
- `web/src/app/features/capture/capture-dialog.html`
- `web/src/app/features/capture/capture-dialog.spec.ts`

Problem:
The preview signal kept the last server reading until the next debounced response arrived, and the template showed it whenever the text was non-empty. After Shift+Enter, or after an edit such as removing a `#project`, the old reading stayed next to the new text for at least the debounce time plus the round trip.

Fix:
The preview pipeline now emits `{ text, response }` (the trimmed text the reading was computed for). A `computed` `preview` returns the response only when that text equals the current `text().trim()`, so a reading of any other text is never shown. While the preview of the current text is pending, the syntax hint is shown, as it was before for empty text or a failed preview. The template condition is simplified to `@if (preview(); as p)` because the computed already covers empty text. Two regression tests were added: an edited text hides the old reading (including a removed project token) until the new preview arrives, and after Shift+Enter the saved capture's reading is not shown next to the next text typed within the debounce. Both tests fail against the previous behavior (checked by temporarily restoring the old condition) and pass with the fix.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/capture/*.spec.ts'` — Pass (4 files, 48 tests)
- `cd web && npm run lint` — Pass

### CR-2 — No test covers an out-of-order preview response

Files changed:
- None for this finding (see below)

Problem:
The review (of an earlier revision, 785dd09d) found no test where an older preview request answers after a newer one.

Fix:
The current code already contains this test, added in task commit b009c1e7 after the reviewed revision: `ignores an older preview that answers after a newer one` in `capture-dialog.spec.ts` types A, passes the debounce, types B, passes the debounce, flushes B and then A, and asserts the preview shows B's title. A companion test, `a failing older preview does not hide the newer one`, covers a failing older request. No duplicate test was added. With the CR-1 fix, an older reading would also be rejected by the text comparison, so the ordering is now guarded twice.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/capture/*.spec.ts'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/capture/*.spec.ts'` — Pass, 4 files, 48 tests
- Regression check: with the old preview condition restored temporarily, the 2 new CR-1 tests fail (2 failed, 46 passed); the fix was then restored

Full test suite:
- `cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, ng build) — Pass, 60 files, 1119 tests; the build prints the existing initial bundle budget warning (614 kB of 500 kB), not caused by this fix

Lint/type-check/build:
- `cd web && npm run lint` — Pass ("All files pass linting")
- Type check and build are part of `npm run check` above — Pass

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("all checks passed")

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
