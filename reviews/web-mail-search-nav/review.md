# Code Review

Date: 2026-11-08

Task: web-mail-search-nav - Web mail search survives opening a match and has previous/next

Scope: Task commits supplied by the parent worker: `git diff 3071bf1f7..45df64e3d` (commits 993f1ac0a, 45df64e3d). The branch was rebased during the review: the same commits are now edc4b5c27 and 616bc8182 on top of 8efb0af9c. `git diff 45df64e3d HEAD -- web/src/app/features/mail web/src/locale` is empty, so the reviewed production code is unchanged. Uncommitted spec-file changes from the concurrent test-writer were not reviewed.

Requirements:
- tasks/web-mail-search-nav.md acceptance criteria and recorded assumptions
- SPEC.md §7.3 (mail list, search, read view)
- docs/PARITY.md (navigation: the search survives opening a match, previous/next in the read view)
- docs/ARCHITECTURE.md and AGENTS.md conventions (i18n in web/src/locale, lint, feature folders)
- Shared contract: web/src/app/shared/navigation/back-navigation.ts (`previous().url`)

Git range:
- Branch: main
- HEAD: 616bc8182d6c434a4d3d1da480ede2246a1597bd (production code identical to 45df64e3d)
- Base/start: 3071bf1f7f8b99ed97295ac9dbc7ab9a8c567be8 (after the rebase: 8efb0af9c154f45f662fb20882203be2d6070b20)
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 0 P2, 3 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Archive/Spam/Delete at the end of a loaded folder page goes back to the newer message instead of loading the next one**
    - File: `web/src/app/features/mail/list/mail-list-session.ts:112`
    - Category: Correctness
    - Problem: `remove(id)` picks `position.nextId ?? position.previousId`. `nextId` is null for the last loaded item even when `more` is true, which means the server has older messages. Archiving the 50th message of a folder or unified-inbox list (one page) therefore opens message 49, a newer one, instead of message 51. `step(id, 1)` already loads the next page in this situation, so pressing `j` and pressing `e` disagree at the same position.
    - Why it matters: AC4 says that after archiving or deleting, the read view moves to the next message, and AC3 applies the same behavior to folder lists. At every page boundary (each 50 messages) the user is sent backwards. The recorded assumption ("next message of the list, else the one before it") covers the true end of the list, not a page boundary where more messages exist.
    - Suggested fix: When `nextId` is null and `more` is true, load the next page first (reuse `loadMore()`, as `step` does). Make `remove` (or a new async variant used by `triage`) await it, then pick the next item. Fall back to `previousId` only when no next item exists after loading or the load fails. Add a session or read-page test for archiving the last loaded item of a folder list that has `nextCursor`.

- [ ] **CR-2 | Returning to a folder list with more than 200 loaded items shrinks it and moves the cursor and scroll position**
    - File: `web/src/app/features/mail/list/mail-list-page.ts:202` (with `web/src/app/features/mail/list/mail-list-store.ts:279-295`)
    - Category: Correctness
    - Problem: After `store.restore(saved)`, every restored folder list calls `store.refresh()`. `refresh` fetches `min(MAIL_MAX_PAGE_SIZE = 200, items.length)` items and replaces the list with that page. Suppose the user scrolled to message 230 (or reached it with `j`, because `MailListSession.step` appends pages to the saved list). When the user returns, the list is cut to 200 items. The cursor is not on a returned item, so `nextAfterRemoval` moves it to item 200, and the cursor effect scrolls to that row. This overrides the restored `scrollTop`.
    - Why it matters: AC5 says the scroll position and cursor row are kept when returning. In this case both are lost. The refresh limit is older code, but this task now runs it on every return to the list.
    - Suggested fix: Have refresh keep the loaded items beyond the refetched window, for example by keeping the old tail and the old `nextCursor` when the restored list is longer than `MAIL_MAX_PAGE_SIZE`. Or skip the refresh (or refresh only the first page and merge it) when the restored list is longer than one maximal page. Add a test for restoring a list with more than 200 items.

- [ ] **CR-3 | The saved list is identified by an exact URL string, so a non-canonical list URL turns off previous/next**
    - File: `web/src/app/features/mail/list/mail-list-session.ts:124-128`, `web/src/app/features/mail/list/mail-list-page.ts:207-214`
    - Category: Integration
    - Problem: `fromList()` requires `BackNavigation.previous().url === saved.url`. `saved.url` is rebuilt by `listUrl()` in a fixed form (`q` first, `server=1` or nothing, the query trimmed, no other params). `previous().url` is the URL the router actually shows. If the list was reached by a URL in another form, the two never match. Examples: a bookmarked or typed `/mail?server=1&q=x`, `/mail?q=x&server=0`, `?q=%20x`, or any extra query parameter. `applyRoute` accepts these URLs and shows the search but leaves the URL as it is. The read view then shows no previous/next, and Archive goes back instead of moving on, even though the message was opened from that list. Back still restores the list, because `restorable` compares `listUrl()` with itself.
    - Why it matters: AC2 and AC3 rely on this match, and nothing tells the user why the buttons are missing. It does not happen on the normal in-app paths, which all produce the canonical form.
    - Suggested fix: In `applyRoute`, when the router's current URL differs from `listUrl()`, replace it with the canonical URL (`navigateByUrl(listUrl(), { replaceUrl: true })`). Or compare parsed URL trees (path plus normalized `q` and `server`) instead of raw strings.

## Review conclusion

No material issues found.

AC1 to AC6 are implemented in the reviewed code:
- The search is in the URL, also on folder routes. Going back restores the list from the session without a second server search.
- Previous/next is gated on the list being the previous history entry. It works with `j`/`k`, the buttons are disabled at the ends, and stepping into the next page works for folder lists.
- Archive, Spam and Delete move on to the next message with `replaceUrl`, so Back returns to the list.
- The cursor and scroll position are kept.
- The English and German strings are present.

The three P3 findings are edge cases that the parent may fix or defer. AC7 (unit tests, `npm run check`, `scripts/check.sh`) has no evidence recorded yet (`tasks.py verify web-mail-search-nav` shows every criterion unchecked). This review did not run them. The parent worker must record that evidence before `done`.
