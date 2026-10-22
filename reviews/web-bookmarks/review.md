# Code Review

Date: 2026-10-22

Task: web-bookmarks - Bookmarks UI

Scope: second review pass of the task commits after base 7e5c5064 up to e5175734 (`git log 7e5c5064..e5175734`): `web/src/app/features/bookmarks/` (page, reader, service, display helpers, styles, specs), `web/src/app/app.spec.ts`, `web/src/app/app.i18n.spec.ts`. Focus: the fixes for CR-1 to CR-4 and TW-2 from the first review (`reviews/web-bookmarks/fixes.md`), and the final code, especially the new `insert`, `changeSeq` and per-id `changedAt` logic.

Requirements:
- tasks/web-bookmarks.md acceptance criteria and recorded assumptions
- SPEC.md §8.4 (and §4.1 for the favicon/IP-leak question)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- reviews/web-bookmarks/fixes.md (claimed fixes)
- server `BookmarkService` (list order `createdAt desc, id desc`, keyset cursor), to check the client-side insert order

Git range:
- Branch: main
- HEAD: e5175734
- Base/start: 7e5c50644d247bc7ee46c1d4279a5c815d2f42bb
- Commits reviewed: 3 (a77cc233, 1bf79432, e5175734)

Review method note: the reviewer had no shell access, so the reviewer could not print the diff or run tests. The review covers the full current contents of `bookmarks-page.ts/.html`, `bookmark-reader.ts/.html`, `bookmarks.service.ts`, `bookmark-display.ts`, `bookmarks-page.spec.ts`, `bookmark-reader.spec.ts` and the server `BookmarkService.kt`. Test and check results are the ones reported in fixes.md (73 focused tests, `npm run check` passing). The reviewer did not re-run them.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Verification of the earlier findings

- CR-1 (favicons blocked by CSP, IP leak): fixed. No `<img>` is rendered. The `aria-hidden` letter tile always shows (`bookmarks-page.html:116-117`). `faviconOf` was removed. The first-page test asserts that no `img` is present and that the letter shows. The limitation is recorded as a task assumption.
- CR-2 (changes of bookmarks not shown were ignored): fixed. `refreshOne` fetches any changed id while a list is shown. It inserts the bookmark when `belongs()` is true. `insert` follows the server order (`createdAt desc, id desc`, confirmed in `BookmarkService.list`). With more pages loaded, it only inserts a bookmark newer than the last shown one. That is correct for the keyset cursor: an older bookmark comes with the next page, and `loadMore` dedupes by id. With more pages loaded or a change running, `CREATED` falls back to `refreshOne(id)`. Tests cover the insert position, appending when no more pages exist, an older bookmark with a cursor, not belonging, 404, and the list still loading.
- CR-3 (a quiet reload overwrote a local change): fixed for local changes. `changeSeq` is bumped at the start and end of `run()` (also on errors, before the conflict reload) and of `add()`. `reloadQuietly` discards its result when the counter moved. `refreshOne` uses the per-id `changedAt`. Because of that, a delete, patch or add of the same id here discards a refresh that was started earlier, including its 404 drop. A change to a different row does not. I traced the add path both ways. A `CREATED` refresh that resolves before the POST is inserted once, because `add()` skips known ids. One that resolves after the POST is discarded through `changedAt(created.id)`. Tests cover each case. See CR-1 below for the remaining remote-vs-remote ordering gap.
- CR-4 (Back lost the view and tag): fixed. "Read here" passes `viewParams()`, and Angular drops the null `tag`. The reader's back link uses `queryParamsHandling="preserve"`. Page and reader tests assert the resulting hrefs.
- TW-2 (style budget): fixes.md reports a passing build without the budget warning. The reviewer did not re-run it.
- CR-5 (tests for race and error paths): now covered. There are tests for a stale list for an earlier view, a quiet reload on `CREATED` and resync, an add error, and a 404 on a row change and on a refresh.

Security: nothing new. Outbound links keep `rel="noopener noreferrer"`. No third-party requests remain. The reader still renders Markdown through `renderMarkdown` plus Angular's `[innerHTML]` sanitizer, and the test asserts that `<script>` is stripped.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A quiet reload that answers late can overwrite a newer change that arrived from elsewhere, or a newer quiet reload**
    - File: `web/src/app/features/bookmarks/bookmarks-page.ts:314-320` (with `:284-302`)
    - Category: Correctness
    - Problem: `reloadQuietly` only checks `loadSeq` and `changeSeq`, and `changeSeq` counts local changes only. Two cases are not guarded. (a) Several quiet reloads overlap, for example a burst of `CREATED` events when the phone syncs several shares, or a `CREATED` event followed by a resync. Whichever response arrives last wins, even if it is the oldest. (b) A `refreshOne` result is applied while an older quiet reload is still in flight. A concrete case: a bookmark is created on another device, so its `CREATED` event starts a reload. Its metadata `UPDATED` event follows quickly, and `refreshOne` shows the fetched title. If the reload response arrives after that, `bookmarks.set(page.items)` puts back the older `PENDING` version ("fetching title…"). No further event repairs it, so the row stays stale until the user navigates. Case (a) can likewise drop the newest created bookmark from the list.
    - Why it matters: This is the out-of-order async result class again (an older request overwrites a newer result). It is narrower than the original CR-3: it needs remote events close together and responses that arrive out of order. It shows stale state without any sign, but it heals on the next navigation or event.
    - Suggested fix: Give quiet reloads their own request identity, so a reload is applied only if it is still the latest one (`++reloadSeq`, checked on arrival). Also bump a generation when `refreshOne` applies a result, so an older reload is discarded. A simpler option is to merge the reload by `version` and keep the newer one per id. Add a test that flushes two quiet reloads out of order, and one where a refresh lands before an older reload.

## Review conclusion

No material issues found. All fixes from the first review are correct and complete. The new insert, `changeSeq` and per-id `changedAt` logic is sound for local changes and matches the server's order and cursor. Both acceptance criteria look implemented and tested: Read-later queue, all bookmarks by tag, add by URL, mark read, the snapshot view, and Vitest coverage of the queue actions. `npm run check` passing is reported by the fixer and was not re-run here. CR-1 is an optional robustness improvement.
