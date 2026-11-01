# Code Review

Date: 2026-11-01

Task: web-mail-search - Mail view: search box with 'search on server' option

Scope: `git diff 059b59076..HEAD -- web reviews` (task commits 4cf829fe1, 98b53de4c), committed changes only

Requirements:
- tasks/web-mail-search.md acceptance criteria and recorded assumptions
- SPEC.md §7.3 "Search"
- api/openapi.yaml `GET /api/v1/mail/search` (q 1..200 chars, folderId, server, limit, hasMore, server.failures)
- AGENTS.md project notes (i18n, conventions, style budget)
- Existing mail list conventions (mail-list-store.ts, mail-shortcuts.ts, shell/hotkeys.ts)

Git range:
- Branch: main
- HEAD: 98b53de4cbcebc13b7e133e1dbc7bcd2d3a0d4ac
- Base/start: 059b59076c0295df24b627be92367d0ac5560b2e
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Switching Messages/Conversations during a search re-runs the search, including a slow server search**
    - File: `web/src/app/features/mail/list/mail-list-page.ts:148`
    - Category: Quality
    - Problem: `setThreaded` always calls `store.load(this.scope())`. While a search is shown, `scope()` includes the search, and `MailListStore.load` forces `threaded: false` for searches (mail-list-store.ts:128), so the result is the same list. When the server option is on, `keepsResults` is true (same query and folder), so `firstPage` sends `GET /mail/search?server=true` again and shows "Searching on the server…". The view switch has no effect on the results, but it starts another IMAP search on every account.
    - Why it matters: a server search is described as slow and caches mail as a side effect. Re-running it for a view change that does not change the result wastes server and IMAP work. It also shows a status that confuses the user.
    - Suggested fix: in `setThreaded`, still store the choice, but skip the reload while `search()` is set. The choice then applies when the search is cleared, because `clearSearch` loads `scope()` with the stored `threaded`. Add a spec for it.

## Verified without findings

- AC1: Enter submits the trimmed query. `MailService.search` passes `folderId` for a folder and omits it for the unified Inbox. Results are shown flat, in server order, in the usual rows, and they link to `/mail/messages/{id}`. A blank submit, an emptied field (`input` event) and Escape all return to the folder list. A route change clears the search and the field. Older answers are dropped through `loadSeq`, and the `scope() === scope` guard covers `searchResult`.
- AC2: The checkbox repeats a shown search with `server=true` (`server: server || undefined`). `server.failures` maps to account ids, which are shown as addresses (unknown ids get a generic name) in a `role="status"` warning, not a blocking dialog. Cached rows stay visible during the server search, and the `hasMore` hint is shown.
- AC3: Loading, error and retry use the existing `loadError`/`reload()` branch. "No messages found" appears only for an empty search result. The list shortcuts skip typing targets (`isTypingTarget` in `mailShortcutFor`). `/` focuses the field, but not with modifiers, inside other inputs or while a dialog is open, and it does not conflict with shell hotkeys. Live refresh and queued refreshes after failed actions are skipped for search scopes, as the assumption says.
- AC4: All new strings, including `mail.search.unknownAccount` and the updated `mail.keysHint`, are in messages.xlf and messages.de.xlf with German targets and matching interpolation placeholders.
- AC5: Store, page and service specs cover scoped search, unified search, server search with several and unknown failed accounts, keeping cached rows, server failure, clearing (emptied field, blank submit, Escape, route change), the empty and error states with retry, the `/` key, and shortcuts being suppressed while typing. The parent reports that lint, the specs and the build pass. I did not re-run them in this review.
- `maxlength="200"` matches the API's `q` maxLength. No secrets, no unsafe HTML (only interpolation), no new dependencies.

## Review conclusion

No material issues found.
