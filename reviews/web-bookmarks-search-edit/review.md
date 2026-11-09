# Code Review

Date: 2026-11-09

Task: web-bookmarks-search-edit - Web bookmarks: search, edit, undo, to inbox

Scope: final revision of the full task diff (web/src/app/features/bookmarks, web/src/locale) at HEAD 2d12fed81. This is the third review. It checks the Round 2 fixes for the second review's CR-1 ("Show more") and CR-2 (To Inbox error message), and the optional conflict-message change. Test-writer work running in parallel and not yet committed is outside this scope.

Requirements:
- tasks/web-bookmarks-search-edit.md (acceptance criteria and recorded assumptions)
- SPEC.md §4.2 (optimistic locking), §4.5 (bookmark to Inbox task), §8.4 (bookmarks, search)
- docs/ARCHITECTURE.md "Code conventions"; AGENTS.md project notes
- Second review (earlier version of this file) and reviews/web-bookmarks-search-edit/fixes.md ("Round 2")

Git range:
- Branch: main
- HEAD: 2d12fed818db54cf935dd92f22ce841c481dea6d
- Base/start: a73c68c4a47065c97cdfc02a2f001404e0b71ffe (the task base used in the second review; Bash was not available in this session, so I could not resolve it again with git merge-base)
- Commits reviewed: every task commit from the base up to 2d12fed81: the 5 from the second review (ending at 66b577797), then the later commits, which include 8d2bf7271, 78905b744, ba7b2ec33 and 2d12fed81. Without git I could not list the range, so the exact count is not stated.

Limitation: Bash was denied in this session. The review is based on reading the current files in full: bookmarks-page.ts, bookmarks-page.html, bookmarks.service.ts and bookmark-display.ts. I could not print the exact git diff or grep the locale files. I did not check the new `bookmarks.taskFailed` unit in messages.xlf and messages.de.xlf myself. The fixer reports that `ng build` ran with no translation errors and that `ng extract-i18n` gives the same source text.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Status of the second review's findings:
- CR-1 ("Show more" hidden when nothing matches) is resolved. The `@if (nextCursor())` button now comes after the empty/list block inside the `shown()` branch (bookmarks-page.html:308-320). It appears with the no-match message and with an empty view. It still does not appear in the loading and load-error branches, which is correct.
- CR-2 (wrong message for a failed To Inbox task) is resolved. `run()` takes a `task` flag (bookmarks-page.ts:519), and `toInbox` passes `true` (bookmarks-page.ts:374). The flag is stored in `rowError`. `isTaskError` picks the new message `bookmarks.taskFailed` (bookmarks-page.html:294-297). Every other failure still shows `bookmarks.changeFailed`.
- Optional conflict-message change: it is correct. After a reload, `saveEdit` shows `bookmarks.edit.conflict` only when the row error and the open editor both still belong to this bookmark (bookmarks-page.ts:343). The draft version moves to the reloaded version only when the editor for that bookmark is still open (bookmarks-page.ts:341).

Re-checked and correct (no finding):
- `busy` blocks overlapping writes to the same row.
- The task id stays the same across retries (`taskIds`) and is cleared only after success.
- `change()` moves the draft version only when the change started from the draft's own version. So a fetched title that arrives during editing still causes a conflict on save.
- The search filter `matchesQuery` covers title, display title, URL, description and `#tag`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Two doc comments on `rowError`; the old one is now wrong**
    - File: `web/src/app/features/bookmarks/bookmarks-page.ts:73`
    - Category: Quality
    - Problem: Round 2 added a new JSDoc for `rowError` at line 74 but left the old one, "The last failed change, by bookmark id.", at line 73. The field now holds failed row actions, including a To Inbox task that changes no bookmark, so the old comment contradicts the new one. Only the comment directly above the field counts as its doc comment, which leaves the old one as dead text.
    - Why it matters: It is a small readability issue. A reader sees two descriptions of the same field that disagree.
    - Suggested fix: Delete line 73.

## Review conclusion

No material issues found. The only finding is a P3 comment cleanup. It does not block completion, and the parent worker may fix it or leave it.
