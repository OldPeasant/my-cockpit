# Code Review

Date: 2026-11-08

Task: web-mail-search-nav - Web mail search survives opening a match and has previous/next

Scope: Re-review of the final task revision. The diff is `git diff fb32caf871bb44c03fa7346b79aaac04f85cfb55..16582c6e2` for the task's production files: web/src/app/features/mail/list/{mail-list-session.ts, mail-list-store.ts, mail-list-page.ts, mail-list-page.html}, web/src/app/features/mail/read/mail-read-page.{ts,html,scss} and web/src/locale/messages*.xlf. The task commits are 90c676d5e, 992dae6eb, 81deb952a and 16582c6e2. The fix commit 16582c6e2 was checked line by line against fixes.md. web/src/app/features/mail/list/mail-folders-store.ts also changed, but only by Prettier reformatting. Spec files were read only where they were needed to check the fixes. No uncommitted production changes were present.

Requirements:
- tasks/web-mail-search-nav.md: acceptance criteria and the recorded assumptions
- SPEC.md §7.3 (mail list, search, read view)
- docs/PARITY.md (navigation: the search survives opening a match, previous/next in the read view)
- docs/ARCHITECTURE.md and AGENTS.md conventions (i18n in web/src/locale, lint, feature folders)
- reviews/web-mail-search-nav/fixes.md (CR-1 and CR-2 fixed, CR-3 accepted by the parent)

Git range:
- Branch: main
- HEAD: 16582c6e2
- Base/start: fb32caf871bb44c03fa7346b79aaac04f85cfb55
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The saved list is identified by an exact URL string, so a non-canonical list URL turns off previous/next (accepted by the parent, still present)**
    - File: `web/src/app/features/mail/list/mail-list-session.ts:129-133`, `web/src/app/features/mail/list/mail-list-page.ts:188-218`
    - Category: Integration
    - Problem: `fromList()` requires `BackNavigation.previous().url === saved.url`. `saved.url` is built by `listUrl()` in a fixed form: `q` first, `server=1` or nothing, the query trimmed, no other parameters. `applyRoute` accepts list URLs in other forms and shows the search for them, but leaves the URL unchanged. Examples are `/mail?server=1&q=x`, `?q=x&server=0` and a query with surrounding spaces. For a list reached this way the read view shows no previous/next, and Archive goes back instead of moving to the next message.
    - Why it matters: AC2 to AC4 do not work for a list reached by a bookmarked or typed URL in another form. All in-app paths produce the canonical form.
    - Suggested fix: In `applyRoute`, replace the URL with `listUrl()` (`replaceUrl: true`) when the two differ. Alternatively, compare normalized paths and the `q`/`server` parameters instead of raw strings.

## Verification of earlier findings

- Earlier CR-1 (Archive at the end of a loaded page went to the newer message): fixed. `MailListSession.remove` (mail-list-session.ts:113-126) is now async. When the message is the last loaded item and `more` is true, it awaits the same single-flight `loadMore()` that `step` uses, then works out the position again. It returns `nextId ?? previousId`. If the page fails to load, it falls back to `previousId`. `MailReadPage.triage` (mail-read-page.ts:232) awaits it, and its `destroyed` / `message()?.id !== id` guard runs after that wait, so leaving or stepping away during the page load is respected. A concurrent `j` shares the in-flight load. New session tests and read-page tests cover the next page loading, the failure fallback, a list without a next page, and going Back during the load.
- Earlier CR-2 (a restored list with more than 200 items was cut short): fixed with the minimal approach. `applyRoute` (mail-list-page.ts:209) skips the background refresh when the restored folder list has more than `MAIL_MAX_PAGE_SIZE` items. A list of up to 200 items is refetched with a limit that covers all its items (mail-list-store.ts:279), so it is not cut. A new list-page test restores 230 items with the cursor on item 220 and checks that no request is made, all rows are shown, and the cursor is kept. The documented trade-off: a long list is not refetched on return and is still cut by the next live-change refresh. That cut comes from older store code outside this task.
- Earlier CR-3: accepted by the parent and unchanged. It is listed again above as CR-1.

## Review conclusion

No material issues found.

The final code implements AC1 to AC6:
- The query and the server option are in the URL, also on folder routes. Back restores the list from the session without searching again.
- Previous/next appears when the message was opened from the saved list, in the buttons and with `j`/`k`. The buttons are disabled at the ends. Stepping past the last loaded item loads the next page of a folder list.
- Archive, Spam and Delete move to the next message, now also across a page boundary, with `replaceUrl`. When no message is left they go back.
- The cursor and scroll position are kept, also for restored lists longer than 200 items.
- The new strings are in English and German.

For AC7, this reviewer ran `cd web && npx ng test --watch=false --include 'src/app/features/mail/**/*.spec.ts'` at HEAD: 20 files, 717 tests passed. `npm run check` and `sh scripts/check.sh` were not run by this review. fixes.md reports both as passing. `tasks.py verify web-mail-search-nav` still shows every criterion unchecked, so the parent worker must record the evidence before `done`.
