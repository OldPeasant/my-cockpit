# Code Review

Date: 2026-11-08

Task: and-bookmarks-plus - Android bookmarks: add, read-later toggle, reader, to inbox

Scope: task commits 2f0bc106 (implementation) and 4bb0f38f (tests) on top of base 6fbabadf, Android bookmarks feature (`android/app/src/main/java/ch/sonensei/cockpit/bookmarks`, its tests, `strings_bookmarks.xml` en/de)

Requirements:
- tasks/and-bookmarks-plus.md acceptance criteria
- SPEC.md §4.2 (ETag/If-Match), §4.3 (links), §4.5 (captures from bookmarks create Inbox items linked to the origin)
- api/openapi.yaml: `POST /api/v1/bookmarks`, `PATCH /api/v1/bookmarks/{id}`, `POST /api/v1/bookmarks/{id}/snapshot`, `GET /api/v1/files/{id}/preview`, `POST /api/v1/tasks`, `POST /api/v1/links`, schemas `CreateBookmarkRequest`, `BookmarkPatchRequest`, `BookmarkResponse`, `CreateTaskRequest`, `CreateLinkRequest`
- server `bookmarks/domain/BookmarkService.kt` and `BookmarkUrls.kt` (validation the client must match)
- AGENTS.md project notes

Git range:
- Branch: main
- HEAD: 4bb0f38f2183e273c9309345b5f461174609bdb6
- Base/start: 6fbabadfbafe865bbdba739d8dc5de77cb446065
- Commits reviewed: 2

Review limitation: shell access (git, ls, tasks.py) was denied in this review session, so the exact diff, the full list of changed files and the recorded `tasks.py verify` evidence could not be inspected. The review is based on reading the current files in full: `BookmarksViewModel.kt`, `BookmarksScreen.kt`, `BookmarkAdder.kt`, `BookmarkReader.kt`, `BookmarksRepository.kt`, `BookmarksModels.kt`, both `strings_bookmarks.xml`, and the tests `BookmarksRepositoryTest.kt`, `BookmarksModelsTest.kt`, `BookmarksViewModelTest.kt` and `BookmarksScreenTest.kt`. The test files found contain repository and model tests for the new calls (create, read-later PATCH with If-Match, snapshot save/preview, to-Inbox task plus link, `isWebAddress`, `resolveLink`). They contain no view-model tests for `add`/`openReader`/`saveSnapshot`/`toInbox`/`toggleReadLater` and no Compose tests for the add dialog or the reader. Such tests may be in a test file this session could not find by name. The parent worker should confirm they exist before ticking criterion 6. That is not reported as a finding because it could not be validated.

Summary: 0 P0, 0 P1, 2 P2, 3 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Add dialog rejects web addresses the server accepts (IDN hosts, `_` in host, spaces, `|`, `{`, `[`)**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModel.kt:219` (check), `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksModels.kt:129-133` (`isWebAddress`)
    - Category: Correctness / Integration
    - Problem: `add()` checks the URL locally with `isWebAddress`, which parses it with `java.net.URI` and requires a non-null `host`, before anything is sent. The server's `BookmarkUrls.normalize` (server `bookmarks/domain/BookmarkUrls.kt:10-16`) is built to accept URLs "as users copy them from a browser". It converts IDN hosts to punycode, allows `_` in host names, and percent-encodes spaces, `|`, `{`, `[`/`]` and non-ASCII characters. `java.net.URI` throws on these or returns a null host, e.g. `https://münchen.example/`, `https://my_site.example.org/`, `https://example.org/search?q=a|b`, `https://example.org/a b`. The dialog then shows "Enter a full web address starting with http:// or https://" for an address that is valid and starts with https://, and the user cannot save it from the screen.
    - Why it matters: the "Add bookmark" criterion fails for real URLs the API supports, and the error message is wrong. The share target, which sends to the same endpoint, takes these URLs.
    - Suggested fix: keep only a light local check (trimmed text starts with `http://` or `https://` and has something after it) and let the server decide. Its 400 for `url` is already mapped to `INVALID_URL`. Or mirror the server's rules. Add tests for an IDN host, an underscore host and a query with `|`.

- [ ] **CR-2 | Every 400 on create is shown as an invalid web address, including too many or too long tags**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModel.kt:246`
    - Category: Correctness
    - Problem: `(result as? BookmarksResult.Failed)?.status == BAD_REQUEST -> AddFailure.INVALID_URL` treats any 400 as a URL error. The server also returns 400 for tags (`BookmarkService.normalizeTags`: a tag over 50 characters, more than 50 tags, NUL characters). The add dialog does not limit tag length or count (`BookmarkAdder.kt:78-86`). A user who types a long tag sees the URL field marked as an error with "Enter a full web address...", while the URL is fine.
    - Why it matters: the error points at the wrong field, so the user cannot see what to fix and the save keeps failing.
    - Suggested fix: map a 400 to `INVALID_URL` only when the Problem Details name the `url` field, and use a generic or tag-specific message otherwise. Or check tag count and length locally (50/50, same limits as the server) and show a tag error. Cover it with a view-model test.

## P3 Low

- [ ] **CR-3 | After "page could not be read" the reader offers no way to try again**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarkReader.kt:144-146`
    - Category: Correctness
    - Problem: when `failure == ReaderFailure.UNREADABLE` (the server's 502 on save), `ReaderBody` shows the error and, if present, the old copy. It shows neither the save button nor a retry, because the `failure != null` branch comes before the snapshot/empty branches that render `SaveCopyButton`. The only way to try again is to close and reopen the reader.
    - Why it matters: a page that was temporarily unreachable cannot be saved again from the reader. The project's own async-state rule asks that every failure leaves a recoverable screen with retry possible.
    - Suggested fix: also render `SaveCopyButton` (or a retry that calls `saveSnapshot`) in the `UNREADABLE` branch.

- [ ] **CR-4 | Saving a copy and the reader's other actions can run at the same time on the same version**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModel.kt:284-289`, `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarkReader.kt:105-121,174`
    - Category: Correctness
    - Problem: `saveSnapshot()` does not use the per-bookmark `busy` guard that `run()` gives the other changes. The reader's Read, Read later and To Inbox buttons are disabled only by `busy`, not by `reading.saving`. The save-copy button is disabled only by `saving`, not by `busy`. Tapping "Mark as read" while a copy is being saved, or the other way round, sends two writes with the same `If-Match`. The second one gets a 412, which shows "changed elsewhere" and reloads, although nobody else changed anything.
    - Why it matters: a confusing conflict message and a lost tap in normal use of one screen.
    - Suggested fix: run `saveSnapshot` through the same `busy` set, or disable the reader actions while `saving` and the save button while `busy`.

- [ ] **CR-5 | "To Inbox task" can create duplicate tasks when the answer is lost**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksRepository.kt:203-219`
    - Category: Correctness
    - Problem: the task is created without a client id. `POST /api/v1/tasks` supports an idempotent create with a client-generated UUIDv7 `id` (openapi `createTask` description, `CreateTaskRequest.id`), and SPEC.md §4.2 has Android generate UUIDv7 for idempotent creates. If the connection drops after the server saved the task, the user sees "Can't reach the server. Nothing was changed." and a retry makes a second Inbox task. The "Nothing was changed" text is also not true in that case.
    - Why it matters: duplicate Inbox items on flaky mobile connections.
    - Suggested fix: generate a UUIDv7 once per action and send it as `id`, so a retry returns the existing task (200). Then create the link with that id.

## Review conclusion

Material issues found; fix before completing the task.

Checked and found consistent with the API and the spec: the read-later toggle sends only `{"readLater":...}` with `If-Match` and handles 409/412 by reloading. The snapshot save sends `If-Match`, treats 502 as "unreadable" and reads the copy through `GET /api/v1/files/{id}/preview` including `X-Preview-Truncated`. The reader renders the server-sanitized HTML as text, not in a WebView, and opens only http(s) links resolved against the page. The to-Inbox action sends a valid `CreateTaskRequest` (`title` cut to 500, `notesMd`, `list: INBOX`) and a valid `CreateLinkRequest` (TASK to BOOKMARK, label `origin`). All new strings exist in English and German. No secrets or sensitive logging were found. CR-1 and CR-2 affect the "Add bookmark" criterion and should be fixed. CR-3 to CR-5 are small robustness improvements. Before completing, the parent worker should also confirm the view-model and Compose tests for the new behavior and the recorded `tasks.py verify` evidence, which this session could not inspect (see the review limitation above).
