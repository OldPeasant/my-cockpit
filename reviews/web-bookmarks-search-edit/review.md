# Code Review

Date: 2026-11-09

Task: web-bookmarks-search-edit - Web bookmarks: search, edit, undo, to inbox

Scope: committed task changes `git diff 4e2b5d5e4..c578b8ad0` (web/src/app/features/bookmarks, web/src/locale). Uncommitted test-writer work running in parallel is not part of this scope.

Requirements:
- tasks/web-bookmarks-search-edit.md (acceptance criteria)
- SPEC.md §4.2 (optimistic locking), §4.5 (bookmark to Inbox task), §8.4 (bookmarks, search)
- docs/PARITY.md (row "Bookmarks: no search, no edit, no undo, bookmark to inbox")
- docs/ARCHITECTURE.md "Code conventions"; AGENTS.md project notes
- Android parity reference: android/app/src/main/java/ch/sonensei/cockpit/bookmarks/ (BookmarksRepository.toInbox/save/restore, BookmarksViewModel)
- Server contract: server/.../bookmarks/api/BookmarkDtos.kt, BookmarksWebSupport.kt (PATCH fields by presence)

Git range:
- Branch: main
- HEAD: c578b8ad00bd9427b60deeac3a4b862ee86a6e4b
- Base/start: 4e2b5d5e44feb5bc3ec365ec2adb1fccd50cb14e
- Commits reviewed: 3 (fe8f7acb3, 615ddc5d2, c578b8ad0)

Summary: 0 P0, 1 P1, 1 P2, 2 P3

Verified as correct (no finding): client-side search over title, display title, URL, description and `#tag` with every-word, case-insensitive matching and a "no loaded bookmark matches" empty state; the editor sends `title`/`description` as explicit `null` when blank, which the server treats as "clear" (presence-based PATCH, BookmarksWebSupport.kt:40) and matches Android `save`; undo uses `restoreTrashItem` with type BOOKMARK, mirrors `TaskUndo` in gtd, and handles restore failure with an error message; "To Inbox task" creates the task with a client UUIDv7 reused on retry until success, title truncated to 500, URL as notes, list INBOX, and an `origin` link TASK to BOOKMARK, reporting an unlinked task separately, all identical to Android; row busy state prevents double submits; all 13 new message ids exist in messages.xlf and have German targets in messages.de.xlf.

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | New behavior has no unit tests in the reviewed commits**
    - File: `web/src/app/features/bookmarks/bookmarks-page.spec.ts:258`
    - Category: Tests
    - Problem: The only spec change in the range adapts the existing delete test to the undo snackbar (asserts message and "Undo" action, then dismisses). There are no tests for `matchesQuery` (bookmark-display.ts), the search field and its empty state, startEdit/saveEdit/cancelEdit (including blank-clears-field and editor-stays-on-failure), the Undo action calling restore and re-inserting the bookmark (or the restore-failure message), or `toInbox` (task body, origin link, same id reused after a failed attempt, unlinked message).
    - Why it matters: Acceptance criterion 5 ("Unit tests cover the new behavior") is not met by the committed code. The idempotent-retry and undo paths are the kind of thing that breaks quietly.
    - Suggested fix: Add page and service/helper specs for the behaviors above. The test-writer running in parallel may already cover this; check its committed result before recording the criterion.

## P2 Medium

- [ ] **CR-2 | Editor save bypasses optimistic locking and can silently overwrite newer values (e.g. the fetched title)**
    - File: `web/src/app/features/bookmarks/bookmarks-page.ts:310` (also `bookmarks-page.ts:290`, `bookmarks-page.html:165`)
    - Category: Correctness
    - Problem: `startEdit` copies title, tags and notes into `draft` but does not keep the bookmark version. The template calls `saveEdit(b)` with the row's current bookmark, and `refreshOne` replaces that row whenever a change event brings a newer version. So the PATCH goes out with `If-Match` of the refreshed version but with the values from the older draft. The server cannot detect the conflict. Concrete case: add a URL, then open Edit while the row still says "fetching title…". The draft title and notes are empty. The server's metadata fetch then updates the row. Saving (for example after only adding a tag) sends `title: null, description: null` and wipes the fetched title and description. A change made on another device while the editor is open is overwritten the same way. Android has the same pattern (BookmarksViewModel `run` takes the current list item), so this is not a parity break. It does defeat the purpose of SPEC.md §4.2's If-Match on the web.
    - Why it matters: Silent lost update of user-visible data (title and notes) in a normal flow, with no 409/412 shown.
    - Suggested fix: Store the version (or the whole base bookmark) in `Draft` at `startEdit` and send that version in `If-Match`, so the existing conflict path (row error plus `reloadOne`) runs. Alternatively, send only the fields the user changed compared to the draft's base.

## P3 Low

- [ ] **CR-3 | Notes textarea has no client-side length limit**
    - File: `web/src/app/features/bookmarks/bookmarks-page.html:193`
    - Category: Quality
    - Problem: The title input has `maxlength="500"`, which matches the server, but the notes textarea has no `maxlength`, while the server limits `description` to 2000 characters (BookmarkDtos.kt:92). Longer notes only fail after Save, with a server validation message.
    - Why it matters: Inconsistent with the title field, and gives avoidable failed saves.
    - Suggested fix: Add `maxlength="2000"` to the textarea.

- [ ] **CR-4 | Bookmarks imports a helper from the capture feature folder**
    - File: `web/src/app/features/bookmarks/bookmarks-page.ts:16`
    - Category: Architecture
    - Problem: `import { uuidv7 } from '../capture/uuidv7'` makes bookmarks depend on another feature's internals. It is the only use of that file outside `features/capture`. ARCHITECTURE.md describes one feature folder per module, and AGENTS.md keeps each feature in its own folder.
    - Why it matters: It couples two lazy features, and a change in capture can break bookmarks. The task's `touches` does not allow moving the helper, so this is a follow-up.
    - Suggested fix: Keep it for now, and file a follow-up to move `uuidv7` to a shared location such as `core/`. Or record the decision as an assumption.

## Review conclusion

Material issues found; fix before completing the task.
