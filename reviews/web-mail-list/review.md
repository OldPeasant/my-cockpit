# Code Review

Date: 2026-10-21

Task: web-mail-list - Mail list and triage

Scope: `git diff 3e6a11a2..c5b934aa`, excluding `tasks/` and `reviews/` (all `web-mail-list:` commits; final code revision c5b934aa). This is the second review. It checks the fixes for CR-1..CR-7 of the first review (`reviews/web-mail-list/fixes.md`) and reviews the whole implementation again. Uncommitted test-writer changes in the working tree were not reviewed.

Requirements:
- tasks/web-mail-list.md (acceptance criteria 1-4 and the recorded assumptions)
- SPEC.md §7.3, §7.4, §9.1
- docs/ARCHITECTURE.md (web conventions, test commands, e2e, environment variables)
- AGENTS.md project notes (i18n, lint, e2e-only test hook, security, shared files)
- Parent instructions: verify the CR-1..CR-7 fixes and review the whole implementation

Git range:
- Branch: main
- HEAD: c5b934aa7a2affa5e1843be98e958229e0af6aeb
- Base/start: 3e6a11a2f490f4a81e1666d0496a146a832875d2 (merge-base with origin/main)
- Commits reviewed: 5 (4e6ad577, 6df83de2, 3bfa2d4f, e4a8f646, c5b934aa)

Summary: 0 P0, 0 P1, 0 P2, 3 P3

Earlier findings, checked against c5b934aa:
- CR-1 (move uses stale targets): fixed. `move()` takes a snapshot of `store.targets()` before it opens the dialog and passes the ids to `apply(action, target, ids)`. `apply` uses only ids that are still loaded, in list order, and sends nothing when none are left. Two page specs cover a refetch while the picker is open.
- CR-2 (rollback undoes concurrent changes): fixed. For a flag action, `rollback` restores only the fields that action changed (`CHANGED_FIELDS`), only on items that are still shown, and only where the field still holds the value the action set. For a removing action, it puts back only the failed items that are still missing, and keeps the current version of the items that are present. After every rollback a refetch is queued, and it runs once no action is running. Specs cover each overlap case that the first review described.
- CR-3 (missing tests): fixed. There are specs for the folder tree, the folders store and the move dialog, plus store specs for overlapping actions and a failure while a refetch is queued.
- CR-4 (a refetch overlapping a load more shrinks the list): fixed for the case where the load more finishes first (`mutationSeq++` in `loadMore`, with a spec). The opposite order is still open, see CR-1 below.
- CR-5 (1000-id limit): fixed. Ids go out in chunks of `MAIL_MAX_ACTION_IDS`. A chunk that fails as a whole counts all of its ids as failed, and only the items behind failed ids are rolled back. A spec covers this.
- CR-6 (j/k auto-repeat): fixed. `REPEATABLE` allows repeats only for `next`/`previous`. A spec covers this.
- CR-7 (cursor not perceivable to assistive technology): fixed. After j/k/e/#/!, `focusCursor()` moves focus to the `.open` button of the cursor row. The optimistic change runs synchronously before `apply`'s first `await`, so the cursor already points at the next row when focus moves. A page spec covers this.

Checked with no findings: the e2e hook `POST /api/test/mail/sync` is `@Profile("e2e")` and `@Hidden`, so it is not in the API contract. Only the e2e permit-all chain of `core.testsupport` (also `@Profile("e2e")`) makes it public, and a Spring test checks that the bean does not exist outside that profile. It logs only counts. GreenMail is published on 127.0.0.1 only and uses fictitious credentials. `E2E_IMAP_PORT` appears in ARCHITECTURE.md, web/e2e/README.md and run-e2e.mjs. Every user-facing string is externalized (`$localize` / `i18n`) under `mail.*` ids. No unsafe HTML binding is introduced; the CSS selectors use `CSS.escape`. Reading and writing the threaded/flat choice in `localStorage` is guarded with try/catch, and a malformed value falls back to flat. Load, load more and action failures all end in a visible state that can be retried. The e2e spec covers mark read (Shift+I), archive (selection and toolbar) and move (`v` and the picker) against GreenMail, checked over IMAP, plus a live update through the change event of the sync. Changes outside the task's `touches` (`mail.routes.ts`, `app.spec.ts`, the server hook, the compose file, docs) are needed to replace the placeholder route and to run the e2e criterion, and the task's assumptions record them. I did not run the suites myself. `tasks.py verify web-mail-list` shows no recorded evidence yet for criteria 1-4. The parent worker still has to record it, including an actual `npm run e2e -- e2e/mail-list/list.spec.ts` run for criterion 4.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A refetch that finishes before an overlapping "load more" can leave a gap in the list**
    - File: `web/src/app/features/mail/list/mail-list-store.ts:146`
    - Category: Correctness
    - Problem: the CR-4 fix only handles a load more that finishes before the refetch. Here is the opposite order. 50 items are loaded with `nextCursor` c50. A live event starts `refresh()` (limit 50), and the user scrolls, so `loadMore()` starts with cursor c50. Meanwhile a new message X arrived. The refetch resolves first: the items become `[X, 1..49]` and `nextCursor` becomes c49. `mutationSeq` has not changed yet, so the store accepts this result. Then the load more resolves and appends the page after c50 (`51..100`) without checking whether the list was replaced since it started. Item 50 is now missing from the list. It comes back only with the next refetch, which needs another change event.
    - Why it matters: during incoming mail, a message silently disappears from the scrolled list. The window is small, but this is the same overlap that the earlier CR-4 was about.
    - Suggested fix: in `loadMore`, record a list generation that `refresh()` (and `load()`) bump when they replace `items`. Drop the page, or redo the load from the new `nextCursor`, when the generation changed. Alternatively, have `refresh()` defer and redo while `loadingMore()` is true. Add a store spec where the refetch resolves before the load more.

- [ ] **CR-2 | Conversation actions fetch every thread in parallel without a limit**
    - File: `web/src/app/features/mail/list/mail-list-store.ts:312`
    - Category: Quality
    - Problem: in the conversations view, `messageIds` calls `mail.thread(item.id)` for every target whose `threadSize > 1`, all at once via `Promise.all`. With "select all" after some scrolling, one keypress (`e`, `#`, `v`) sends hundreds of concurrent `GET /messages/{id}/thread` requests before the action itself is sent. If any one of them fails, the whole action is rolled back.
    - Why it matters: bulk triage in the threaded view is slow, puts load on the server, and fails completely on a single transient error.
    - Suggested fix: limit the concurrency (for example, batches of 6-10), or retry failed thread lookups. Either way, keep the existing rollback when a lookup finally fails. A server-side "apply to conversation" option would remove the extra requests, but that belongs to a separate API task.

- [ ] **CR-3 | `#` (delete) cannot be typed on layouts that need AltGr/Option for it**
    - File: `web/src/app/features/mail/list/mail-shortcuts.ts:43`
    - Category: Correctness
    - Problem: `mailShortcutFor` rejects every event with `ctrlKey` or `altKey`. On Windows, AltGr reports `ctrlKey` and `altKey` together. On macOS, Option reports `altKey`. On layouts where `#` is AltGr+3 or Option+3 (for example Swiss German), the event has `key === '#'` but is ignored, so the delete shortcut that SPEC.md §7.3 requires does not work there.
    - Why it matters: one of the five required shortcuts does not work on common European layouts. The user has to fall back to the toolbar.
    - Suggested fix: match printable symbol keys by `event.key` and allow `event.getModifierState('AltGraph')` (Windows) and `altKey` without `ctrlKey`/`metaKey` (macOS) for symbol keys such as `#` and `!`. Keep rejecting real Ctrl/Meta chords. Add a spec for an AltGr `#`.

## Review conclusion

No material issues found. All seven earlier findings (CR-1..CR-7 of the first review) are fixed correctly and have tests. The three remaining findings are low priority and can be fixed or recorded as accepted at the parent worker's discretion. Criteria 1-4 still need recorded evidence (`tasks.py verify`), including an actual e2e run, before the task can be completed.
