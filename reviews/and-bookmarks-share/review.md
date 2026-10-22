# Code Review

Date: 2026-10-22

Task: and-bookmarks-share - Android: share URL to read later

Scope: `git diff 236a674ae..256fd8d16 -- android/` (committed changes only). This covers the task commits c59e5e8b5, 3d451d14c, b055edf87 and c87b42d84, merged in 8358e093f. 256fd8d16 changes only `reviews/and-bookmarks-share/fixes.md`. This review covers the final revision 256fd8d16 in full and checks that c87b42d84 fixes CR-1 and CR-2 of the previous review. At review time the working tree had an uncommitted 54-line addition to `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/ReadLaterUploaderTest.kt`, probably from the parallel test-writer. It is outside this scope and was not reviewed.

Requirements:
- tasks/and-bookmarks-share.md: acceptance criteria 1-2 and the three recorded assumptions (shared capture queue with a new `pending_bookmark` table and auto-migration 1->2, no client id on `POST /bookmarks`, the choice offered only for http(s) URLs without files)
- SPEC.md §8.4 (Android share of a URL offers "Inbox" or "Read later"), §9.3 (offline capture)
- docs/ARCHITECTURE.md and the project conventions in AGENTS.md
- Server contract: generated `BookmarksApi.createBookmark` (`Response<BookmarkResponse>`), `server/.../bookmarks/api/BookmarkDtos.kt` `CreateBookmarkRequest` (url max 2048, title max 500), `server/.../bookmarks/domain/BookmarkUrls.kt` `normalize`, `BookmarkService.normalizeUrl`
- Previous reviews/and-bookmarks-share/review.md and fixes.md (round 2)

Git range:
- Branch: main
- HEAD: 256fd8d16ddebe10b31b502af07d5f8a3bc79643
- Base/start: 236a674ae0cd7d802b090b62351109d53d86e8d4
- Commits reviewed: 4 task commits (c59e5e8b5, 3d451d14c, b055edf87, c87b42d84), their merge 8358e093f, and 256fd8d16 (report only)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Commands run and results

- `git diff --stat 236a674ae..256fd8d16 -- android/`: 18 files, +1933/-43. Main code is in `capture/share` (CaptureSheet, ShareActivity, ShareViewModel, SharedContent, strings_share.xml) and `capture/queue` (CaptureQueue, CaptureQueueDatabase, CaptureQueueModule, CaptureUploader, schema `2.json`). Tests: 4 new test classes, 3 adapted.
- `git diff --stat 8358e093f..256fd8d16`: only `reviews/and-bookmarks-share/fixes.md` changed. The final code equals the merge result.
- `git diff c87b42d84 8358e093f -- android/`: empty. The merge introduced no Android changes.
- `git status --short`: one uncommitted test file (see Scope).
- Running Gradle myself (`./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*' --tests 'ch.sonensei.cockpit.capture.queue.*'`) was denied by the sandbox in this review session. As the nearest evidence, I read the existing JUnit result files in `android/app/build/test-results/testDebugUnitTest/`. They were written at 2026-10-22 13:19:42, after c87b42d84 (13:13:03), and later commits do not touch code. Test counts: ShareReadLaterTest 10, ShareReadLaterEdgeTest 17, ShareReadLaterActivityTest 5, ShareIntentParserTest present, ReadLaterQueueTest 12, ReadLaterUploaderTest 17. `grep -l '<failure\|<error'` over all result files matched nothing (0 failures, 0 errors). The parent must still record its own run of criterion 2 as evidence.

## Verification of the round-2 fixes (c87b42d84)

- Previous CR-1 (an unreadable 2xx body led to retries and duplicate bookmarks). Fixed. `CaptureUploader.sendSafely(bookmark)` (`CaptureUploader.kt:105-132`) catches `SerializationException`, `DateTimeException` and `IllegalArgumentException` only around `bookmarksApi.createBookmark(request)`, and treats each as `Outcome.Sent`. The request is built outside that inner try, and `CreateBookmarkRequest` holds only strings and a boolean, so these exceptions can only come from decoding a 2xx body. Retrofit decodes only successful bodies. On the async path, OkHttp reports interceptor failures as an `IOException`, which still maps to `Retry(OFFLINE)`. Regression tests `ReadLaterQueueTest` "a 2xx answer that cannot be read ..." and "a 2xx answer with a date-time or id ..." check that the row is removed and that a second `sendAll()` sends nothing.
- Previous CR-2 (the client checked the raw URL length, the server checks the normalized length). Fixed. I compared `CaptureQueue.isBookmarkUrl` (`CaptureQueue.kt:78-147`) line by line with `BookmarkUrls.normalize`. They match on: the scheme/authority/path/query/fragment split, the USER_INFO/PATH/QUERY character sets, the encoding (valid `%XX` kept, other code points counted as 3 x their UTF-8 bytes), IDN-to-ASCII host conversion with the same `HOST_NAME`/`IPV6`/`PORT` patterns, and the raw-length pre-check. The test "the length limit counts the URL as the server encodes it" checks the exact 2048/2049 boundary for non-ASCII text, existing escapes, IDN hosts and a second `#`.
- One small remaining difference, not raised as a finding: the client skips the server's final `java.net.URI(...)` parse. Of the inputs the client accepts, I found only a malformed IPv6 literal (for example `https://[1:2]/`) that the server's final parse rejects. Such a share gets a 400, and the bookmark stays in the queue as rejected, the same way a rejected capture does. Such links are not realistic share input, so this does not justify a change.

## Review notes (no findings)

- Criterion 1 (choice Inbox / Read later; read later goes through the offline queue). Met:
  - `ShareIntentParser.parse` sets `url` only for shares without files and with an http(s) URL that passes `isBookmarkUrl`.
  - `CaptureSheet` shows a `SingleChoiceSegmentedButtonRow` only when `state.url != null`. Inbox is the default.
  - Read later goes to `CaptureQueue.readLater`. It stores a `pending_bookmark` row under `NonCancellable`, then schedules the job. A failed schedule is ignored, as it is for captures.
  - `CaptureUploader.sendAll` sends the queued bookmarks after the captures, with the same Retry/Rejected/Failed handling and the same logged-out handling.
  - The pending count and the last error include bookmarks.
  - Room `AutoMigration(1, 2)` with exported `2.json` adds a table only. The test "version 1 queues migrate to version 2 with their captures kept" builds the database from the committed `1.json` export.
- Criterion 2 (focused share tests pass and cover the choice routing). Covered by Robolectric/Compose tests:
  - ShareReadLaterTest: parser, ViewModel routing, sheet switching its fields.
  - ShareReadLaterEdgeTest: URL detection edge cases, locking during save and after save, retry after failure, disabled segments.
  - ShareReadLaterActivityTest: the real `ShareActivity` with Hilt, the real queue and Room, the toast text, and a configuration change.
  - The result files above show them passing. I could not run them myself (see Commands).
- State and concurrency:
  - `save()` checks `canSave` on the current state and sets `saving` before it launches, so a double submit cannot queue twice.
  - `onDestinationChange` ignores changes while saving or after saving, so the destination cannot change during a write.
  - Every failure path resets `saving` and shows `ShareError.FAILED`, and saving again works (tested).
- Contract:
  - Body `{url, title?, readLater: true}` matches `CreateBookmarkRequest`.
  - A title is trimmed, sent as null when blank or equal to the URL (so the server fetches the page title), and shortened to 500 UTF-16 units without splitting a surrogate pair. That matches the server's `title.length` check.
  - A 401 maps to Retry. 408, 429 and 5xx map to Retry. Any other 4xx maps to Rejected, as for captures.
- Architecture and conventions:
  - Read later reuses the existing queue and worker instead of adding a second offline mechanism. This is recorded as an assumption.
  - The changes outside the task's `touches` (`capture/queue`) are recorded as an assumption too.
  - No new library was added. The only build change is the exported Room schema.
  - No logging of URLs or titles was added.
- Security:
  - Only `content:` URIs are still accepted.
  - The URL is shown as plain Compose text.
  - No secrets, and the test data is fictitious (`example.org`).
- Existing tests: `ShareIntentParserTest`, `CaptureQueueConsistencyTest` and `CaptureQueueTestSupport` were only adapted to the new `url` field and the new constructor parameter. No assertions were removed or weakened.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
