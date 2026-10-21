# Fix Report

Date: 2026-10-21

Task: web-mail-list - Mail list and triage

Scope: current task; round 1: findings CR-1 .. CR-7 of the first review, round 2: findings CR-1 .. CR-3 (P3) of the second review in `reviews/web-mail-list/review.md`; changes limited to `web/src/app/features/mail/list/`

# Round 1 (first review, CR-1 .. CR-7)

## Fixed findings

### CR-1 — Move acts on the targets as they are when the dialog closes

Files changed:
- `web/src/app/features/mail/list/mail-list-store.ts`
- `web/src/app/features/mail/list/mail-list-page.ts`
- `web/src/app/features/mail/list/mail-list-store.spec.ts`
- `web/src/app/features/mail/list/mail-list-page.spec.ts`

Problem:
`move()` opened the dialog, then `apply('MOVE')` re-read `store.targets()`. A live refetch while the dialog was open could move the cursor or prune the selection, so the move hit a different message.

Fix:
`MailListStore.apply(action, targetFolderId?, targetIds?)` takes an optional explicit id list. It applies only to the ids that are still loaded, in list order, and does nothing (returns false, no request) when none of them is loaded. `move()` snapshots `store.targets()` before it opens the dialog and passes their ids.

Validation:
- Store specs: "applies to the given ids that are still loaded, in list order", "does nothing when none of the given ids is loaded any more" — Pass
- Page specs: "when a refetch changes the list while the folder picker is open": "does not move the item the cursor moved to" (the refetch removes the cursor item while the dialog is open, so nothing is moved) and "moves the picked items that are still there, not the cursor item" — Pass

### CR-2 — Rollback of a failed action can undo a concurrent action's change

Files changed:
- `web/src/app/features/mail/list/mail-list-store.ts`
- `web/src/app/features/mail/list/mail-list-store.spec.ts`

Problem:
`rollback` put back the snapshot version of every failed item. That brought back items a later action had removed, and dropped a later action's flag changes.

Fix:
`rollback(action, snapshot, failed)` now undoes only what the failed action changed:
- Removing actions (MOVE/ARCHIVE/DELETE/SPAM) put back only the failed items that are still missing, at their old place. Items that are present keep their current version.
- Flag actions (READ/UNREAD/FLAG/UNFLAG) touch only items that are still in the list, and only the fields the action changes (`CHANGED_FIELDS`). A field is put back to its snapshot value only if it still has the value the failed action set, so a later action's change to that field is kept (`withFieldsRestored`).
- After every rollback a refetch is queued (`refreshQueued = true`). It runs once no action is running, so the server's state settles any remaining overlap even when SSE is down.

Validation:
- Store specs: "does not bring back an item a later action removed when an earlier flag action fails", "keeps a later successful flag change when an earlier action on the same item fails", "keeps a field a later action changed again when the earlier action on it fails", "restores the removed item as it was after an earlier action changed it", "refetches the list after a rollback, once no action runs", and in "live updates" "keeps a queued refetch waiting while another action still runs after a failure" — Pass
- The existing rollback specs still pass unchanged ("rolls back only the failed items…", "rolls back only its own items when overlapping actions finish out of order", "keeps an earlier failure rolled back…", "runs a refetch queued during a failed action after its rollback").

### CR-3 — Missing tests for folder tree, folders store, move dialog, overlapping actions

Files changed:
- `web/src/app/features/mail/list/mail-list-store.spec.ts`

Problem:
The review saw no specs for `folderTree`, `MailFoldersStore` or `MailMoveDialog`, and no store tests for overlapping actions or a failure while a refetch is queued.

Fix:
I checked the specs that are now committed:
- `mail-folders-store.spec.ts` covers `folderTree`: role order, nesting at any depth, children sorted by name, orphaned parents, delimiter handling, ties and the empty list. It also covers `MailFoldersStore`: load and index, move targets, load errors with and without server detail, stale-answer and stale-error guards, and live reloads for unknown, created or deleted folders, account events, SSE reconnects, debounced bursts and ignored event types.
- `mail-folder-tree.spec.ts` covers the component.
- `mail-move-dialog.spec.ts` covers filtering by label or path, Enter picking the first match or the first folder, the empty result, no other folder, and cancel.

The store gap remained. It is now filled by the overlapping-action and queued-refresh specs listed under CR-2, plus the CR-4 and CR-5 specs.

Validation:
- `npx ng test --include='src/app/features/mail/list/**/*.spec.ts' --watch=false` — Pass (7 files, 163 tests)

### CR-4 — A refetch that overlaps a "load more" shrinks the list back

Files changed:
- `web/src/app/features/mail/list/mail-list-store.ts`
- `web/src/app/features/mail/list/mail-list-store.spec.ts`

Problem:
`refresh()` sized its request when it started. If `loadMore()` appended a page meanwhile, the refetch answer replaced the longer list with a shorter one.

Fix:
`loadMore()` increments `mutationSeq` when it appends a page. A refetch that started before that is then dropped by the existing staleness check and redone, with a limit sized for the longer list.

Validation:
- Store spec "drops a refetch that overlaps a load more and refetches the longer list": list length and `nextCursor` are kept, and the redone refetch asks for `2 + MAIL_PAGE_SIZE` — Pass

### CR-5 — Bulk actions ignore the server's 1000-id limit

Files changed:
- `web/src/app/features/mail/list/mail.service.ts`
- `web/src/app/features/mail/list/mail-list-store.ts`
- `web/src/app/features/mail/list/mail-list-store.spec.ts`

Problem:
`MailActionRequest.ids` allows at most 1000 ids. Above that the whole request failed and everything was rolled back.

Fix:
New constant `MAIL_MAX_ACTION_IDS = 1000`. `MailListStore.send` splits the ids into requests of at most 1000 and sends them in parallel, then merges the results. If a whole request fails, all of its ids count as failed. The error message is the first failure's message. Only the items behind failed ids are rolled back.

Validation:
- Store spec "sends at most 1000 ids per request and merges the results": 2500 ids go out as 1000/1000/500. One id fails in the first chunk, the second chunk is rejected and the third succeeds, so exactly those items come back and one error is shown — Pass

### CR-6 — Holding j/k does not move the cursor repeatedly

Files changed:
- `web/src/app/features/mail/list/mail-shortcuts.ts`
- `web/src/app/features/mail/list/mail-shortcuts.spec.ts`

Problem:
`mailShortcutFor` rejected every key event with `event.repeat` set, including j/k.

Fix:
Repeated key events are now accepted only for the `next`/`previous` shortcuts (`REPEATABLE`). Actions, selection and Escape still ignore repeats.

Validation:
- Spec "repeats j/k while held, but never an action or selection" — Pass

### CR-7 — Keyboard cursor moves are not perceivable to assistive technology

Files changed:
- `web/src/app/features/mail/list/mail-list-page.ts`
- `web/src/app/features/mail/list/mail-list-page.spec.ts`

Problem:
j/k changed only `aria-current`. Focus did not move and nothing was announced.

Fix:
This is roving focus. After j/k, and after the keyboard actions that remove the cursor item (e, #, !), `focusCursor()` moves focus to the `.open` button of the cursor's row, using `preventScroll`; the existing effect keeps the row scrolled into view. The button's accessible name (sender, subject, flags, date) is then announced. No new user-facing strings were added, so no i18n changes were needed.

Validation:
- Page spec "moves focus with the keyboard cursor to its row" (j, a held j, k, then e moves focus to the next message after the archived row is removed) — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --include='src/app/features/mail/list/**/*.spec.ts' --watch=false` — Pass (7 files, 163 tests)
- `cd web && npx prettier --check src/app/features/mail/list/` — Pass, after formatting `mail-list-page.spec.ts` with `prettier --write`

Full test suite:
- `cd web && npm run check` (api:check, `ng lint --max-warnings=0`, e2e:typecheck, `ng test --watch=false`, `ng build`) — Pass: "All files pass linting.", 91 test files, 1683 tests passed, build complete. The existing warning that the initial bundle exceeds its budget is unchanged.

Lint/type-check/build:
- Included in `npm run check` above — Pass

`.taskcheck`:
- Not run by the fixer. Only web files changed and the web part of `scripts/check.sh` (`npm run -s check`) passed. The parent worker runs the full `.taskcheck`.

Remaining unresolved findings:
0

Round 1 status: all validated findings fixed and validation passed.

# Round 2 (second review, CR-1 .. CR-3, P3)

## Fixed findings

### CR-1 (second review) — A refetch that finishes before an overlapping "load more" can leave a gap

Files changed:
- `web/src/app/features/mail/list/mail-list-store.ts`
- `web/src/app/features/mail/list/mail-list-store.spec.ts`

Problem:
`loadMore()` appended its page without checking whether a refetch had replaced the list since the page was requested. When the refetch resolved first (and new mail had moved the list's end), the page after the old cursor was appended to the shorter list. The item between the new end and the old cursor was then missing.

Fix:
Added a private `listGeneration` counter. `load()` increments it, and so does `refresh()` when it replaces `items`/`nextCursor`. `loadMore()` records the generation when it starts and drops its page if the generation changed. The list then keeps the refetch's `nextCursor`, so the next load more continues from there. The existing order (load more first, refetch later, handled by `mutationSeq`) is unchanged. Known limitation: a dropped page is not re-requested automatically. If the end-of-list sentinel stays in view, the IntersectionObserver does not fire again. More mail then loads by scrolling, by `j` on the last item, or with the existing "Load more" button.

Validation:
- New store spec "drops a next page that arrives after a refetch replaced the list, leaving no gap": the refetch (`[new, a]`, cursor `c-a`) resolves before the load more (cursor `c-b`). The stale page is dropped, the list stays `[new, a]` with cursor `c-a`, and the next load more requests `c-a` and gives `[new, a, b, c]` — Pass
- Existing spec "drops a refetch that overlaps a load more and refetches the longer list" — Pass

### CR-2 (second review) — Conversation actions fetch every thread in parallel without a limit

Files changed:
- `web/src/app/features/mail/list/mail-list-store.ts`
- `web/src/app/features/mail/list/mail-list-store.spec.ts`

Problem:
In the threaded view, `messageIds` called `mail.thread()` for every multi-message target at once through an unbounded `Promise.all`.

Fix:
Added the exported constant `THREAD_LOOKUP_CONCURRENCY = 6` and a module-private helper `mapWithLimit`. It runs at most that many lookups at a time, keeps results in item order, rejects on the first failure and starts no further lookups after it. `messageIds` uses it. The existing behavior on failure is unchanged: the action is not sent, every target is rolled back and the error is shown.

Validation:
- New spec "looks up at most 6 conversations at a time": 19 conversations, held lookups. Exactly 6 run at first, the peak stays at 6, all 19 are looked up, and the ids are sent in list order — Pass
- New spec "stops looking up conversations after a failed lookup and rolls back every item": fewer than all lookups are made, nothing is sent, all items are restored and the server message is shown — Pass
- Existing conversation-action specs (single-message conversation, shared ids, rollback of a conversation, thread lookup failure) — Pass

### CR-3 (second review) — `#` (delete) cannot be typed on layouts that need AltGr/Option for it

Files changed:
- `web/src/app/features/mail/list/mail-shortcuts.ts`
- `web/src/app/features/mail/list/mail-shortcuts.spec.ts`

Problem:
`mailShortcutFor` ignored every event with `ctrlKey` or `altKey`. AltGr on Windows reports Ctrl+Alt, and Option on macOS reports Alt. So `#` (and `!`) typed that way on layouts such as Swiss German was ignored.

Fix:
For the symbol keys `#` and `!` only, an event is now accepted when its modifiers are those of AltGr or Option: no Meta, and either `getModifierState('AltGraph')` or `altKey`. That covers Ctrl+Alt on Windows, AltGraph on Linux and Option on macOS. Other events with Ctrl, Alt, Meta or AltGraph are still rejected. This includes Ctrl or Ctrl+Shift without Alt, any Meta chord, and every letter shortcut with a modifier. AltGraph now counts as a modifier for letters too, so on Linux AltGr+e does not archive. Repeated symbol keys still don't trigger an action.

Validation:
- New specs: `#` and `!` are taken with Ctrl+Alt, with AltGraph (with and without Ctrl+Alt), with Alt (Option) and with Alt+Shift. They are ignored with Ctrl, Meta, Meta+Alt and Ctrl+Shift. Letters (`e`, `j`) with Ctrl+Alt, AltGraph or Alt are ignored, and a repeated AltGr `#` is ignored. Existing modifier specs are unchanged — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --include='src/app/features/mail/list/**/*.spec.ts' --watch=false` — Pass (7 files, 182 tests). The first run failed one new spec (AltGr+`e` with only the AltGraph state was accepted). Fixed by counting AltGraph as a modifier, then rerun — Pass
- `cd web && npx prettier --write src/app/features/mail/list/` — applied before the test runs

Full test suite:
- `cd web && npm run check` (api:check, `ng lint --max-warnings=0`, e2e:typecheck, `ng test --watch=false`, `ng build`) — Pass: "All files pass linting.", 91 test files, 1702 tests passed, build complete. The existing warning that the initial bundle exceeds its budget is unchanged.

Lint/type-check/build:
- Included in `npm run check` above — Pass

`.taskcheck`:
- Not run by the fixer. Only web files changed and the web part of `scripts/check.sh` (`npm run -s check`) passed. The parent worker runs the full `.taskcheck`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
