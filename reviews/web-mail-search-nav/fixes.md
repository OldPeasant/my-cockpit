# Fix Report

Date: 2026-11-08

Task: web-mail-search-nav - Web mail search survives opening a match and has previous/next

Scope: Current task. The parent worker supplied findings CR-1 and CR-2 from `reviews/web-mail-search-nav/review.md`. CR-3 was accepted as-is by the parent and is not part of this fix.

## Fixed findings

### CR-1 - Archive/Spam/Delete at the end of a loaded folder page goes back to the newer message instead of loading the next one

Files changed:
- `web/src/app/features/mail/list/mail-list-session.ts`
- `web/src/app/features/mail/read/mail-read-page.ts`
- `web/src/app/features/mail/list/mail-list-session.spec.ts`
- `web/src/app/features/mail/read/mail-read-page.spec.ts`

Problem:
`MailListSession.remove(id)` picked `nextId ?? previousId` from the loaded items only. For the last loaded item of a folder list that has a `nextCursor`, it returned the newer message before it. `step(id, 1)` loads the next page in the same situation.

Fix:
`remove` is now async. When the message is the last loaded item and the list can load more (a folder list with a `nextCursor`, not a search), it first awaits the same single-flight `loadMore()` that `step` uses. Then it computes the position again and returns the next item, else the previous one, else null. If the page fails to load, it falls back to the previous item. The message is taken out of the saved list after the load, so the cursor moves to the newly loaded next item. `MailReadPage.triage` now awaits `session.remove(id)`. The existing `destroyed` / `message()?.id !== id` guard runs after that await, so it also covers the page-load wait. The triage buttons stay disabled while it waits because `triaging` is still set.

Tests:
- Session tests: the next page loads before the last loaded item is removed (the call returns `d`, the cursor moves to `d`, `nextCursor` advances). If the page fails, the call falls back to the previous item. A folder list without a next page loads nothing.
- Read-page tests: `e` on the last loaded item of a folder list with `nextCursor` loads `cur-1` and navigates to the new message. When the user goes Back while the page loads, the read page stays on the list.
- The existing synchronous `remove` calls in the session spec now await the result.
- All five new tests (CR-1 and CR-2) failed when the production change was temporarily reverted, and pass with it.

Validation:
- `cd web && npx ng test --watch=false --include 'src/app/features/mail/**/*.spec.ts'` - Pass (20 files, 717 tests)

### CR-2 - Returning to a folder list with more than 200 loaded items shrinks it and moves the cursor and scroll position

Files changed:
- `web/src/app/features/mail/list/mail-list-page.ts`
- `web/src/app/features/mail/list/mail-list-page.spec.ts`

Problem:
After `store.restore(saved)`, the list page always called `store.refresh()` for folder lists. `refresh` fetches at most `MAIL_MAX_PAGE_SIZE` (200) items and replaces the list with them. A restored list longer than that was cut short, and its cursor and scroll position moved.

Fix:
This is the minimal fix the parent suggested. The background refresh on restore is skipped when the restored folder list has more than `MAIL_MAX_PAGE_SIZE` items, so such a list is shown exactly as it was saved. Lists up to 200 items are still refetched in the background, as before. `MailListStore.refresh` is unchanged, so its behavior for live updates is not affected.

Trade-off: a restored list longer than 200 items is not refetched on return. It still updates on the next live change event, and that path keeps its existing 200-item cap, which is older code outside this finding.

Tests:
- New list-page test: restoring a folder list of 230 items with a cursor on item 220 makes no list request, shows all 230 rows, keeps the cursor, and saves the same `nextCursor` and cursor when the list is left again.

Validation:
- `cd web && npx ng test --watch=false --include 'src/app/features/mail/**/*.spec.ts'` - Pass (20 files, 717 tests)

## Not fixed

### CR-3 - The saved list is identified by an exact URL string, so a non-canonical list URL turns off previous/next

Status:
Out of scope

Reason:
The parent worker accepted this P3 finding as-is and said not to fix it.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include 'src/app/features/mail/**/*.spec.ts'` - Pass (20 files, 717 tests)
- Negative check: with the CR-1 and CR-2 production changes temporarily reverted, the five new tests failed. With the changes restored, they pass.

Full test suite:
- `cd web && npm run check` - Pass (lint: all files pass; 188 test files, 4250 tests; build succeeds, with only the existing initial-bundle budget warning)

Lint/type-check/build:
- Covered by `cd web && npm run check` - Pass

`.taskcheck`:
- `sh scripts/check.sh` - Pass

Remaining unresolved findings:
0 (CR-3 was accepted by the parent and not fixed)

## Final status

All validated findings fixed and validation passed.
