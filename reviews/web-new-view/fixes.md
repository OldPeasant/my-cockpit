# Fix Report

Date: 2026-10-25

Task: web-new-view - Combined New view (GTD inbox + mail inbox)

Scope: current task; findings CR-1, CR-2, CR-3 from `reviews/web-new-view/review.md` and TW-1 from `reviews/web-new-view/tests.md`, limited to `web/src/app/features/new/`

## Fixed findings

### CR-1 / TW-1 — "Loading…" never ends after a failed load and hides the other source under "All"

Files changed:
- `web/src/app/features/new/new-page.ts`
- `web/src/app/features/new/new-page.html`
- `web/src/app/features/new/new-page.spec.ts`

Problem:
`loading` counted a source as loading while its data was missing, which stays true forever after a failed request (`ListStore.loaded()` stays false, `MailListStore.items()` stays `null`). Under "All" the page showed "Loading…" next to the error and hid the rows of the source that did load.

Fix:
A shown source now counts as loading only while it has neither data nor an error (`!tasks.loaded() && !tasks.error()`, `mail.items() === null && !mail.loadError()`). The rows of the source that loaded are shown next to the error banner. When a mail load fails, `nextCursor` is `null`, so no task is held back and all tasks show. The empty state ("Nothing new") is no longer shown while a load error is displayed, because an empty list next to a failed source would be misleading. The two TW-1 tests were not changed and now pass. New tests: `load errors under All` (every task shown next to a failed mail load, the mails shown next to a failed Inbox load, no empty state next to the error).

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/new/**/*.spec.ts'` — Pass (60/60)

### CR-2 — Keyboard handling differs from the mail list

Files changed:
- `web/src/app/features/new/new-page.ts`
- `web/src/app/features/new/new-page.spec.ts`

Problem:
All repeated keys were ignored, so holding `j`/`k` did not keep moving the cursor (`mailShortcutFor` allows repeat for next/previous). `o` was ignored whenever any button had focus. The cursor never moved keyboard focus to the row, unlike `MailListPage.focusCursor`.

Fix:
Repeated keydowns are ignored except for `j`/`k`. Only `Enter` is left to a focused button, so `o` opens the cursor row from anywhere. After `j`/`k`, focus moves to the cursor row's `.open` button (`focus({ preventScroll: true })`), the same way the mail list's `focusCursor` does it. The existing scroll-into-view effect is unchanged. New tests: `j`/`k` repeat while other repeated keys (`o`, `t`) are ignored, focus follows the cursor, and `o` opens the row while a filter button has focus. The existing tests for repeated filter keys and for Enter on a focused button still pass.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/new/**/*.spec.ts'` — Pass (60/60)

### CR-3 — Mails without `receivedAt` sort last and turn off the hold-back

Files changed:
- `web/src/app/features/new/new-feed.ts`
- `web/src/app/features/new/new-feed.spec.ts`

Problem:
A mail without a parseable `receivedAt` was given time `0`. It sorted to the end of the list, and `oldest()` returned `0`, so no task was held back while more mail pages were pending.

Fix:
A new internal helper, `mailAt(mail)`, returns `receivedAt` when it is a parseable date, otherwise `sentAt`, otherwise `''`. The same value is used for sorting, for the row's `at`, and for the oldest-mail cutoff. A mail with no usable date at all is left out of the cutoff, so it can't turn off the hold-back. The server sorts by `coalesce(received_at, created_at)`, but `created_at` is not part of `MailMessageSummaryResponse`, so `sentAt` (the `Date` header) is the closest date the client has. A mail with no date at all still sorts last, as before. New tests in `mergeNew > mails without an arrival date`: sorting and `at` use the Date header, the hold-back uses the Date header, and a dateless mail is left out of the hold-back.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/new/**/*.spec.ts'` — Pass (60/60)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/new/**/*.spec.ts'` — Pass, 2 files, 60 tests (was 51 with 2 failing)

Full test suite:
- `cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, build) — Pass, exit 0, 168 test files, 3524 tests passed. The build prints the existing initial-bundle budget warning, which this change did not cause.

Lint/type-check/build:
- `cd web && npx ng lint --max-warnings=0` — Pass
- `cd web && npx prettier --check src/app/features/new` — Pass
- Type-check and build are part of `npm run check` — Pass

`.taskcheck`:
- Not run. Its server part needs Docker for the DB tests, and Docker is not available on this machine. Its web part (`npm run check`) passes.

E2E:
- `web/e2e/new/new.spec.ts` was not touched and was not run (no Docker).

Coverage:
- Not measured again. The test-writer has to measure it again on the final code.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
