# Fix Report

Date: 2026-10-26

Task: web-journal-rollover - Journal: Today rolls over at midnight, prompt before dropping conflicting edits

Scope: findings CR-1 and CR-2 of `reviews/web-journal-rollover/review.md` (both P3, approved by the parent worker)

## Fixed findings

### CR-1 — "Save my version" in the leave prompt can start a second keep-mine while the banner's one is still running

Files changed:
- `web/src/app/features/journal/journal-page.ts`
- `web/src/app/features/journal/journal-page.spec.ts`

Problem:
If the user clicked "Keep mine" in the conflict banner and left the day before its GET answered, `canLeave()` prompted. On 'keep-mine' it then called `keepMine()` a second time. That second GET could come back with a stale version after the first PUT had already completed, so the next PUT got 412 and the page showed a conflict caused by its own save.

Fix:
A running "Save my version" is now tracked as `keeping: { seq, done }`, where `seq` is the load it belongs to. `keepMine()` returns the running promise when one is active for the current load, and starts a new one (`saveMine(seq)`) otherwise. `canLeave()` records the running promise before it prompts. If the user picks "Save my version", it waits for that promise and does not start another one, then decides by the resulting `saveState` (`'saved'` means leave). The prompt itself and the 'stay' and 'discard' answers behave as before. This keeps the existing tests valid that leave with 'discard' while a keep-mine runs. Two regression tests were added in the 'leaving a conflict' describe. They use the `pending()` helper while the guard waits.
- "waits for "Save my version" from the banner instead of saving again": only one GET and one PUT (If-Match "3"), then the other day opens.
- "stays when "Save my version" from the banner ends in a conflict again": the awaited PUT gets 412, so the user stays on the day with their edits and the conflict state, and no further requests are sent.
To confirm the tests catch the bug, the fix was temporarily turned off (canLeave no longer waits and keepMine no longer reuses the running one). Both new tests then failed. The file was restored afterwards.

Validation:
- `npx ng test --watch=false --include='src/app/features/journal/**/*.spec.ts'` — Pass (6 files, 134 tests)

### CR-2 — `savedSince` field comment does not match its behavior

Files changed:
- `web/src/app/features/journal/journal-page.ts`

Problem:
The comment said "Entries saved while a heatmap request runs". In fact `markDay()` records every save, and the map is cleared only when the next heatmap request starts. The missing test was already added by the test-writer.

Fix:
Only the comment changed. It now says the map holds entries saved since the newest heatmap request started, is cleared when a request starts, and is marked again on the answer. Behavior is unchanged: changing it would add risk without fixing a bug, because the replay is idempotent.

Validation:
- `npx ng test --watch=false --include='src/app/features/journal/**/*.spec.ts'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/journal/**/*.spec.ts'` — Pass, 6 files, 134 tests
- `cd web && npx eslint src/app/features/journal` — Pass, no findings
- `cd web && npx prettier --check src/app/features/journal` — Pass after `prettier --write` on the new spec lines
- `cd web && npx tsc -p tsconfig.app.json --noEmit` — Pass

Full test suite:
- `cd web && npm run check` — Pass (lint clean, 176 files, 3702 tests)

Lint/type-check/build:
- Covered above (eslint, prettier, tsc, `npm run check`)

`.taskcheck`:
- Not run by the fixer (`scripts/check.sh` also covers server and android, which are unchanged). The parent worker runs it before `done`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
