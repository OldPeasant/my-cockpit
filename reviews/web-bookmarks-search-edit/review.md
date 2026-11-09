# Code Review

Date: 2026-11-09

Task: web-bookmarks-search-edit - Web bookmarks: search, edit, undo, to inbox

Scope: final revision of the full task diff `git diff a73c68c4a..66b577797` (web/src/app/features/bookmarks, web/src/locale). This is the second review, after the fixes for the first review's findings. Uncommitted test-writer work running in parallel is not part of this scope.

Requirements:
- tasks/web-bookmarks-search-edit.md (acceptance criteria and recorded assumptions)
- SPEC.md §4.2 (optimistic locking), §4.5 (bookmark to Inbox task), §8.4 (bookmarks, search)
- docs/ARCHITECTURE.md "Code conventions"; AGENTS.md project notes
- First review (earlier version of this file) and reviews/web-bookmarks-search-edit/fixes.md

Git range:
- Branch: main
- HEAD: 66b5777978c1a554453ab1a260e6622d7aec1221
- Base/start: a73c68c4a47065c97cdfc02a2f001404e0b71ffe
- Commits reviewed: 5 (3e2e17cb8, 8fd1957c3, 3acdcdf3d, 85f6d104f, 66b577797)

Summary: 0 P0, 0 P1, 1 P2, 1 P3

What the earlier findings came to:
- CR-1 (no tests) is resolved. bookmark-display.spec.ts covers `matchesQuery`. bookmarks.service.spec.ts covers restore and toInbox: task body, the origin link to the id the server returned, the 500-character title cap, the unlinked case, and a failed create. bookmarks-page.spec.ts covers search (including the empty state and Escape), the editor (open, save, blank fields sent as null, cancel, failure, conflict, the stale-version case, mark read while editing, maxlength), undo (restore, dismiss, restore failure, no undo after a failed delete), and To Inbox (linked and unlinked, the same id on retry and a new one after success, one id per bookmark, no double submit).
- CR-2 (lost update) is resolved. `Draft.version` is set in `startEdit` and sent as `If-Match` in `saveEdit` (bookmarks-page.ts:326). A conflict reloads the bookmark, moves the draft to the current version and shows `bookmarks.edit.conflict`. `change()` moves the draft version along only when the change started from the draft's own version (bookmarks-page.ts:397). So a fetched title that arrived meanwhile still causes a conflict, and the user's own mark read does not.
- CR-3 is resolved: the notes field has `maxlength="2000"` (bookmarks-page.html:196).
- CR-4 (the `uuidv7` import from features/capture) is recorded as a task assumption. Moving the helper is outside this task's touches, so it is not repeated here.

Checked and correct (no finding): all 14 new message ids (13 from the first revision plus `bookmarks.edit.conflict`) are in messages.xlf and have German targets in messages.de.xlf. Placeholders match between source and target. Existing units changed only in their line-number context.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | "Show more" is hidden when no loaded bookmark matches the search**
    - File: `web/src/app/features/bookmarks/bookmarks-page.html:108` (the button is at line 301)
    - Category: Correctness
    - Problem: Search filters only the loaded pages, as the task assumption says. The "Show more" button sits only in the `@else` branch for a non-empty `shown()` list. When the query matches none of the loaded bookmarks but `nextCursor()` is set, the page says "No loaded bookmark matches {query}." and has no "Show more" button. The text itself suggests that unloaded bookmarks might match, yet the only way to search them is to clear the search, load more and search again.
    - Why it matters: For users with more than one page of bookmarks, searching for an older bookmark looks like a dead end, which weakens the search feature from criterion 1.
    - Suggested fix: Also show the "Show more" button in the no-match branch whenever `nextCursor()` is set (for example, move the button out of the non-empty branch), and add a page test for it.

## P3 Low

- [ ] **CR-2 | A failed "To Inbox task" is reported as "could not be changed"**
    - File: `web/src/app/features/bookmarks/bookmarks-page.ts:357` (message at `bookmarks-page.html:294`)
    - Category: Quality
    - Problem: `toInbox` runs through `run()`, so any failure appears as the row error `bookmarks.changeFailed`: "{title} could not be changed: {error}". The bookmark is not being changed. A task is being created.
    - Why it matters: The message misleads the user about what failed. They may think the bookmark is damaged instead of retrying the task creation.
    - Suggested fix: Catch the toInbox failure separately, or pass a message id to `run`, and show a specific text such as "No Inbox task could be created for {title}: {error}". It needs English and German strings.

## Review conclusion

Material issues found; fix before completing the task. CR-1 is a non-blocking P2 usability gap and CR-2 is cosmetic, so the parent worker may instead mark them out of scope with a reason. No P0 or P1 issues remain.
