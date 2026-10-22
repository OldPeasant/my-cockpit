# Fix Report

Date: 2026-10-22

Task: web-habits - Habits UI

Scope: current task, limited by the parent to `web/src/app/features/habits/`. Findings came from `reviews/web-habits/review.md` (CR-1 to CR-4) and `reviews/web-habits/tests.md` (TW-1). Base: HEAD 5df8ac54 on main, with the changes below left uncommitted.

## Fixed findings

### CR-1 / TW-1 - Conflict reload after a failed edit was not sequenced

Files changed:
- `web/src/app/features/habits/habits-page.ts`
- `web/src/app/features/habits/habits-page.spec.ts`

Problem:
`reloadKeepingForm()` called `habits.set()` without checking `loadSeq`. A late active-list reload after a 409/412 on edit could therefore overwrite the archived view, or a newer load of the same view.

Fix:
I renamed the method to `reloadQuietly()`. It now records `loadSeq` when it starts and applies its result only if no other `load()` started in the meantime. It does not increment `loadSeq`: if it did, a pending `load()` would be dropped, and the page would stay stuck in "Loading" if the quiet reload then failed. The test-writer's TW-1 spec is unchanged and now passes. I added a spec for the other direction: a conflict reload that arrives after a newer load of the same view is ignored.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/habits/**/*.spec.ts'` - Pass (4 files, 63 tests)

### CR-3 - A tab left open past midnight checked the previous day

Files changed:
- `web/src/app/features/habits/habits-page.ts`
- `web/src/app/features/habits/habits-page.spec.ts`

Problem:
`check()` sends `stats.today` from the last load. That value goes stale when the tab stays open past midnight.

Fix:
The page now listens for `visibilitychange` on the injected `DOCUMENT`. This follows the pattern in `core/notifications/reminder-notifications-toggle.ts`. The listener is removed through `DestroyRef`. When the page becomes visible, a new `refresh()` checks the state:
- If no form is open, nothing is saving and no row is busy, it reloads the list quietly with the sequence guard. The current list stays on screen, so there is no "Loading" flash.
- If the last load failed, it retries with `load()`.
- If a load is still running, it does nothing.

New specs check five things: the reload happens and the next check goes to the new `stats.today`; there is no reload when the page is hidden, a form is open or a check is running; there is no reload while a load is running; a failed load is retried; and there is no reload after the component is destroyed.

Remaining limitation (not part of the minimal fix): a check tapped on a stale tab before the page has been hidden and shown again still uses the old date.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/habits/**/*.spec.ts'` - Pass

### CR-2 - Streak plural built with `count === 1` in TS

Files changed:
- `web/src/app/features/habits/habit-display.ts`
- `web/src/app/features/habits/habit-display.spec.ts`
- `web/src/app/features/habits/habits-page.html`
- `web/src/app/features/habits/habits-page.ts`
- `web/src/app/features/habits/habits-page.spec.ts`

Problem:
`streakLabel()` used a hard-coded English plural rule and four `$localize` strings.

Fix:
I removed `streakLabel()`. The template now has a `#streak` `ng-template` (used for the current and the best streak) with one ICU plural per unit: `@@habits.streak.days` `{count, plural, =1 {1 day} other {{{ count }} days}}` and `@@habits.streak.weeks` (same form). This matches `gtd/lists/waiting-page.html`. The unit test `labels streaks in days or weeks` in `habit-display.spec.ts` tested the removed function, so I deleted it. Its cases (1 day, 0 weeks, plus 2 weeks) now live in a page spec, `uses the singular and plural of days and weeks for streaks`. The existing page expectations ('4 days', '1 week', '12 days', '5 days') are unchanged and pass. The ids `@@habits.streak.day` and `@@habits.streak.week` no longer exist. `messages.xlf` was never regenerated for habits (see the recorded assumption), so it needs no change.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/habits/**/*.spec.ts'` - Pass

## Not fixed

### CR-4 - Conflict and 404 handling paths have no tests

Status:
Invalid (no longer applies)

Reason:
The test-writer's specs already cover every case the review asks for:
- `keeps the edit form after a 412, reloads, and saves again with the new version` checks the error text, the reload GET and the next PATCH with `If-Match: "7"`.
- `drops a habit that is gone (404) on check or archive, without an error` covers 404.
- `reloads the habits after a 412 on archive` and `reloads the habits after a check conflict ...` cover the 409/412 reloads.

The new CR-1 spec adds coverage of the reload ordering. No further spec was missing.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/habits/**/*.spec.ts'` - Pass (4 files, 63 tests, TW-1 included)
- `cd web && npx eslint --max-warnings=0 src/app/features/habits` - Pass
- `cd web && npx prettier --check src/app/features/habits` - Pass (after `prettier --write` on `habits-page.html`)

Full test suite:
- `cd web && npm run check` - Pass (exit 0). test:ci: 137 files, 2738 tests passed. `ng build` finished with the existing initial-bundle budget warning, which this change does not affect (habits is a lazy chunk).

Lint/type-check/build:
- Included in `npm run check` (api:check, lint, e2e:typecheck, test:ci, build) - Pass

`.taskcheck`:
- Not run: running `sh scripts/check.sh` was denied by the sandbox permissions in this session. The parent worker must run it.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed. `.taskcheck` still has to be run by the parent worker.
