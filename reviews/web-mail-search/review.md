# Code Review

Date: 2026-11-01

Task: web-mail-search - Mail view: search box with 'search on server' option

Scope: re-review of the final code, `git diff 059b59076..HEAD -- web reviews` (task commits 65feafa86, 2b7558f29, 30b07347a, c198e60df), committed changes only

Requirements:
- tasks/web-mail-search.md acceptance criteria and the four recorded assumptions (route change clears the search; no live refetch of results; results always flat; server re-search keeps cached rows with a status)
- SPEC.md §7.3 "Search"
- api/openapi.yaml `GET /api/v1/mail/search` (q 1..200 chars, folderId, server, limit, hasMore, server.failures)
- AGENTS.md project notes (i18n, conventions, style budget)
- Existing mail list conventions (mail-list-store.ts, mail-shortcuts.ts, shell/hotkeys.ts)
- reviews/web-mail-search/fixes.md (fix of the earlier CR-1)

Git range:
- Branch: main
- HEAD: c198e60df5057c10ae75e2b3a7fa2e5444824867
- Base/start: 059b59076c0295df24b627be92367d0ac5560b2e
- Commits reviewed: 4

Note on method: shell access was denied in this re-review session, so `git diff` and `git log` could not be run again. The HEAD hash was read from `.git/refs/heads/main`. The final state of the changed files was read in full: mail-list-page.ts, mail-list-page.html, mail-list-store.ts, mail.service.ts, mail-list-search.scss, and the search part of mail-list-page.spec.ts. The changes in the earlier commits were checked against the diff in the first review. Tests were not run again here. fixes.md reports 604 passing mail specs and a clean lint run.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Earlier finding re-checked

- CR-1 (P3, first review), "Switching Messages/Conversations during a search re-runs the search": **resolved**. `setThreaded` (web/src/app/features/mail/list/mail-list-page.ts:148-160) still sets the `threaded` signal and writes localStorage (the write is guarded with try/catch). It then returns before `store.load` while `search()` is set. `clearSearch` (line 198) loads `scope()`, which uses the stored `threaded` value, so the choice applies once the search is cleared. The route-change path and `submitSearch` also build their scope from `threaded()`, so the choice is not lost on any path. The new spec "keeps a server search on a view switch and lists conversations after clearing it" (mail-list-page.spec.ts:809) checks four things: the stored value, that no search or list request is sent, that the rows stay unchanged with no "Searching on the server…" status, and that clearing loads `{ folderId: 'inbox-1', threaded: true }`. `afterEach` removes `THREADED_KEY`, so the stored value does not leak into other tests. Remaining side effect: while a search is shown, the "Conversations" button shows as pressed but the results stay flat. The recorded assumption "results are always flat" covers this, and it is the intended behavior of the fix.

## Verified without findings

- AC1: Enter submits the trimmed query. `MailService.search` sends `folderId` for a folder and leaves it out for the unified Inbox. Results are flat, in server order, and shown in the usual rows that link to `/mail/messages/{id}`. A blank submit, an emptied field (`input` event) and Escape all return to the folder list. A route change clears the search and the field. Older answers are dropped through `loadSeq`. `searchResult` is only written when `scope() === scope`, and every `load` creates a new scope object, so this identity check is sound.
- AC2: The checkbox repeats a shown search with `server=true` (`server || undefined`). `server.failures` is mapped to account ids, which are shown as addresses (unknown ids get a generic name) in a `role="status"` warning, so nothing blocks. `keepsResults` keeps the cached rows and the cursor while the server search runs. An HTTP error then shows the usual load error, as the assumption says. The `hasMore` hint is shown.
- AC3: Loading, error and retry use the existing `loadError`/`reload()` branch. "No messages found" appears only for an empty search result. The list shortcuts skip typing targets (`isTypingTarget`). `/` focuses the field. It does nothing with modifiers, during IME composition, inside other inputs, or while a dialog is open. Live refreshes and queued refreshes are skipped for search scopes (`refresh` returns early when `scope().search` is set).
- AC4: The strings were checked in the first review: `mail.search.*`, `mail.search.unknownAccount` and the updated `mail.keysHint` are in messages.xlf and messages.de.xlf. The fix commit added no strings.
- AC5: The store, page and service specs cover scoped, unified and server searches. They cover several and unknown failed accounts, keeping cached rows, a server failure, and clearing (emptied field, blank submit, Escape, route change). They also cover the empty and error states with retry, the `/` key, shortcuts being suppressed while typing, no live refetch, and the view-switch regression.
- `maxlength="200"` matches the API's `q` maxLength. Search styles are in their own sheet, `mail-list-search.scss`. There are no secrets, no unsafe HTML (interpolation only) and no new dependencies. All changes are inside the task's `touches`.

## Review conclusion

No material issues found.
