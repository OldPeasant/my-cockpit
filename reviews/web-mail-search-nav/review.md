# Code Review

Date: 2026-11-08

Task: web-mail-search-nav - Web mail search survives opening a match and has previous/next

Scope: Final review of the whole task diff `git diff 0843d39baddc3058c55b223c75f0029951597eb3..298163db9c2ccb889cb49d4f1aca9df1ed69290f`. This covers the production code (web/src/app/features/mail/list/{mail-list-session.ts, mail-list-store.ts, mail-list-page.ts, mail-list-page.html, mail-folders-store.ts}, web/src/app/features/mail/read/mail-read-page.{ts,html,scss}, web/src/locale/messages{,.de}.xlf) and the spec files (mail-list-session.spec.ts, mail-list-store.spec.ts, mail-list-page.spec.ts, mail-read-page.spec.ts). The production code is unchanged since the fix commit 5785b4600. The two later commits (cba84e987, 298163db9) add tests only, and those were read in full. mail-folders-store.ts has only Prettier formatting changes. No uncommitted changes were present.

Requirements:
- tasks/web-mail-search-nav.md: acceptance criteria and the four recorded assumptions (including the accepted URL-canonical finding)
- AGENTS.md project notes (i18n in web/src/locale, lint, feature folders, touches)
- docs/ARCHITECTURE.md conventions
- reviews/web-mail-search-nav/fixes.md (earlier CR-1 and CR-2 fixed, CR-3 accepted)

Git range:
- Branch: main
- HEAD: 298163db9c2ccb889cb49d4f1aca9df1ed69290f
- Base/start: 0843d39baddc3058c55b223c75f0029951597eb3
- Commits reviewed: 6 (97b2dc3d0, cdc292d18, 26d835546, 5785b4600, cba84e987, 298163db9)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The saved list is identified by an exact URL string, so a list URL in another form turns off previous/next (accepted by the parent, still present)**
    - File: `web/src/app/features/mail/list/mail-list-session.ts:129-133`, `web/src/app/features/mail/list/mail-list-page.ts:188-218`
    - Category: Integration
    - Problem: `fromList()` requires `BackNavigation.previous().url === saved.url`, and `restorable()` compares the same way. `saved.url` comes from `listUrl()`, which always writes the same form: `q` first, `server=1` or nothing, the query trimmed, no other parameters. `applyRoute` accepts list URLs in other forms (`/mail?server=1&q=x`, `?q=x&server=0`, a query with spaces around it, extra parameters) and shows their search, but it does not rewrite the URL. For a list opened this way the read view shows no previous/next, and Archive goes back to the list instead of moving to the next message.
    - Why it matters: AC2 to AC4 do not work for a list opened from a bookmarked or typed URL in another form. Every URL the app builds itself has the expected form. The parent recorded this as an accepted assumption (2026-11-08 13:37).
    - Suggested fix: If it is revisited, have `applyRoute` replace the URL with `listUrl()` (`replaceUrl: true`) when the two differ, or compare the path and the `q`/`server` parameters instead of raw strings.

## Review notes (no findings)

- AC1: the query and the server option are in the URL, also on folder routes (`listUrl`, `showSearch`). A new search pushes a history entry. Toggling the server option replaces it. `applyRoute` reads `route.snapshot` from the merged param and query-param streams and ignores the duplicate emission, so a route change is applied once. On the first apply, the saved list is restored with `MailListStore.restore` (it bumps `loadSeq`, `listGeneration` and `mutationSeq`, so loads still in flight are dropped). Search results are not searched again. Folder lists of up to 200 items get a background refresh. The `[value]` binding together with the imperative `input.value` puts the query back in the search field.
- AC2/AC3: `position()` is a computed based on `BackNavigation.previous()`. A step uses `replaceUrl`, so the list stays the entry behind the message. Previous is disabled at the start. Next is disabled at the end unless a folder list can load more, and `step` loads the next page through the shared single-flight `loadMore`. `fetchMore` drops a page when the list was saved again in the meantime. `j`/`k` go through `mailShortcutFor`, which skips typing targets, and they are ignored while a dialog is open.
- AC4: `remove` awaits the next page when needed and returns the next message, else the previous one, else null (back to the list). The `destroyed` / `message()?.id !== id` guard in `triage` runs after that wait.
- AC5: `trackScroll` / `restoreScroll` restore the scroll position, and the cursor row is scrolled into view. `visited()` moves the list cursor to the message read last.
- AC6: the six new `mailRead.*` units are in messages.xlf and messages.de.xlf, with matching interpolation placeholders.
- Tests added after the earlier review (cba84e987, 298163db9) are meaningful. They cover the edges of the next-page wait during removal: an empty page, sharing the request with a concurrent `step`, a list saved again meanwhile, a search with a cursor, the only loaded item, and the list no longer behind the message. They also cover the 200 vs 201 refresh boundary, the window-scroll fallback, triage buttons staying busy while the page loads, and `j` during an archive asking for the page only once. The assertions check observable results (returned ids, saved items and cursor, requests made, URL), not internals.
- Evidence for AC7 from this review: `cd web && npx ng test --watch=false --include 'src/app/features/mail/**/*.spec.ts'` at HEAD 298163db9 gave 20 files and 732 tests passed. `npx eslint src/app/features/mail/list src/app/features/mail/read` passed with no output. This review did not run `npm run check` or `sh scripts/check.sh`. `python tasks.py verify web-mail-search-nav` still lists all seven criteria as unchecked, so the parent must record the evidence before `done`.

## Review conclusion

No material issues found.
