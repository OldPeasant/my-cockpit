# Fix Report

Date: 2026-10-22

Task: web-bookmarks - Bookmarks UI

Scope: current task. The parent approved findings CR-1, CR-2, CR-3 and CR-4 (`reviews/web-bookmarks/review.md`) and TW-2 (`reviews/web-bookmarks/tests.md`). All changes stay in `web/src/app/features/bookmarks/`.

## Fixed findings

### CR-1 — Remote favicons are blocked by the production CSP and leak the owner's IP

Files changed:
- `web/src/app/features/bookmarks/bookmark-display.ts`
- `web/src/app/features/bookmarks/bookmark-display.spec.ts`
- `web/src/app/features/bookmarks/bookmarks-page.ts`
- `web/src/app/features/bookmarks/bookmarks-page.html`
- `web/src/app/features/bookmarks/bookmarks-page.scss`
- `web/src/app/features/bookmarks/bookmarks-page.spec.ts`

Problem:
The list loaded `<img [src]="favicon">` straight from the bookmarked site. The production CSP (`img-src 'self' data: blob:`) blocks these images, so rows showed an empty icon box instead of the letter fallback. Loading them also sends the owner's IP to every bookmarked site.

Fix:
The list now always shows the host-initial letter tile, and no third-party image is loaded. Removed `faviconOf`, its spec and its usages, along with the `.icon img` style. This matches the assumption already recorded in the task: a server-side favicon proxy would be a later task. The first page test now checks that no `<img>` is rendered and that a bookmark with a favicon shows its letter (`E`).

Validation:
- `cd web && npx ng test --watch=false --include 'src/app/features/bookmarks/**/*.spec.ts'` — Pass

### CR-2 — Change events for bookmarks not currently listed were ignored

Files changed:
- `web/src/app/features/bookmarks/bookmarks-page.ts`
- `web/src/app/features/bookmarks/bookmarks-page.spec.ts`

Problem:
`refreshOne` returned early when the event's id was not shown. A bookmark that entered the view elsewhere (for example re-queued, marked unread or restored) never appeared. `reloadQuietly` skipped `CREATED` events whenever more pages were loaded.

Fix:
- `refreshOne` now fetches any changed id while a list is shown. If the bookmark is not shown yet and belongs to the view (`belongs()`), the new `insert()` places it in the server order: newest first by `createdAt`, then by `id`, the same as `BookmarkService` (`createdAt desc, id desc`). When more pages exist (`nextCursor`), it is only inserted if it is newer than the last shown bookmark. Otherwise "Show more" brings it.
- `CREATED` now calls `reloadQuietly(event.id)`. If a full reload is not possible (more pages loaded, or a row change running), only that id goes through `refreshOne` and nothing is skipped.

Tests added or updated: `loads only the created bookmark while more pages are loaded, and puts it on top` (replaces `does not reload the first page quietly while more pages are loaded`), `does not put a created bookmark older than the last shown one on the loaded pages`, `loads only the created bookmark while a change of another one is running`, `adds a changed bookmark that entered the view, in its place by date` (replaces `does not load a changed bookmark that is not shown`), `adds a changed bookmark at the end when no more pages exist`, `does not add a changed bookmark that does not belong to the view` (includes a 404), and `does not load a changed bookmark while the list loads`.

Validation:
- focused bookmarks tests — Pass

### CR-3 — A quiet reload overlapping a local change could overwrite it

Files changed:
- `web/src/app/features/bookmarks/bookmarks-page.ts`
- `web/src/app/features/bookmarks/bookmarks-page.spec.ts`

Problem:
`reloadQuietly` only discarded its result if a change was still running when the response arrived. A change that started and finished while the reload was in flight was undone on screen.

Fix:
Added a `changeSeq` counter, following `features/habits/habits-page.ts`. It is incremented by `noteChange()` when a change starts and when it ends: in `run()` (mark read/unread, queue toggle, delete), including the error path before the conflict reload, and in `add()`. `reloadQuietly` captures the counter and discards its result if the counter changed. `refreshOne` also discards its result, including a 404 drop, if the same bookmark was changed here meanwhile. It does this through a per-id `changedAt` map, so a refreshed title is not lost just because another row was changed at the same time. This guard matters now that CR-2 lets `refreshOne` insert rows: without it, a refresh could bring back a bookmark the user had just deleted.

Tests added: `drops a quiet reload that overlaps a change made here`, `drops a quiet reload that overlaps an add, a delete or a queue change`, `drops a refreshed bookmark that was deleted here meanwhile`, `keeps a refreshed bookmark when another bookmark was changed here meanwhile`, and `ignores a 404 of a refreshed bookmark that was changed here meanwhile`. The existing tests `ignores a refreshed bookmark when a change of it started meanwhile` and `does not reload quietly while a change of a bookmark is running` still pass.

Validation:
- focused bookmarks tests — Pass

### CR-4 — Reader's "Back to bookmarks" lost the view and tag

Files changed:
- `web/src/app/features/bookmarks/bookmarks-page.ts`
- `web/src/app/features/bookmarks/bookmarks-page.html`
- `web/src/app/features/bookmarks/bookmark-reader.html`
- `web/src/app/features/bookmarks/bookmarks-page.spec.ts`
- `web/src/app/features/bookmarks/bookmark-reader.spec.ts`

Problem:
`routerLink=".."` always returned to the Read later queue.

Fix:
The "Read here" link now passes `[queryParams]="viewParams()"`: `{ view: 'all', tag }` in the all view (a null tag is left out), and nothing in the queue. The reader's back link uses `queryParamsHandling="preserve"`.

Tests added: page `opens the reader with the view and tag, so it can lead back to them`, plus reader `leads back to the view and tag it was opened from` (`/bookmarks?view=all&tag=essays`) and `leads back to the read-later queue when opened from it` (`/bookmarks`).

Validation:
- focused bookmarks tests — Pass

### TW-2 — `bookmarks-page.scss` exceeded the 4 kB component style budget

Files changed:
- `web/src/app/features/bookmarks/bookmarks-page.scss`

Problem:
The build warned that the stylesheet was 4.34 kB, over the 4.00 kB budget.

Fix:
Merged duplicate declarations without changing the look:
- one muted-colour rule for `.label`, `.tag`, `.icon`, `.meta`, `.description`, `.empty` and `.hint`;
- one border and radius rule for the toggle, the add form and its text inputs;
- one shared rule for `.add-error` and `.error`, with the row's column override nested under `.bookmark`;
- one flex-wrap rule for `.tag-filter`, `.meta` and `.tags`;
- shared hover and focus-visible rules for `.tag` and `.title`.

The text-input selector is now `input:not([type='checkbox'])`, with the same specificity as before. The unused `.icon img` rule was removed (CR-1). The budget was not raised. Compiled size went from 4347 to 3906 bytes (`npx sass --style=compressed`).

Validation:
- `cd web && npm run check` — Pass. The build output has no budget warning for `bookmarks-page.scss`.

## Not fixed

None. CR-5 (missing race and error-path tests) was not in the approved list, but the new CR-2 and CR-3 tests cover its items (b), quiet reload on CREATED and resync, and (d), 404 handling. The test-writer had already covered items (a) and (c).

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include 'src/app/features/bookmarks/**/*.spec.ts'` — Pass: 4 files, 73 tests (61 before these fixes).

Full test suite:
- `cd web && npm run check` (api:check, lint, e2e typecheck, `ng test`, `ng build`) — Pass: exit 0, 149 files, 3009 tests.

Lint/type-check/build:
- `cd web && npx ng lint --max-warnings=0` — Pass ("All files pass linting.")
- `ng build` (part of `npm run check`) — Pass. One warning remains, and it was there before: the initial bundle is 672.04 kB against a 500 kB budget. The component style budget warning is gone.

`.taskcheck`:
- Not run. `sh scripts/check.sh` also runs the server and Android checks. Its web part (`npm run check`) was run directly and passed. The parent runs `.taskcheck` through `tasks.py done`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
