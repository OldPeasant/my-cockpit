# Code Review

Date: 2026-10-22

Task: and-bookmarks-share - Android: share URL to read later

Scope: `git diff 1cda56a79..HEAD -- android/` (committed changes only). An uncommitted test file from the parallel test-writer (`android/app/src/test/java/ch/sonensei/cockpit/capture/queue/ReadLaterUploaderTest.kt`) was in the working tree during the review. It is not committed, so it is outside the reviewed scope.

Requirements:
- tasks/and-bookmarks-share.md (acceptance criteria 1-2, three recorded assumptions)
- SPEC.md §8.4 (Bookmarks / read-later), §9.3 (Offline capture)
- docs/ARCHITECTURE.md and AGENTS.md project conventions
- Server contract: api/openapi.yaml `POST /api/v1/bookmarks`, server bookmarks `BookmarkService.normalizeUrl` / `BookmarkUrls.normalize`

Git range:
- Branch: main
- HEAD: 305b21850
- Base/start: 1cda56a79602bb43270f4483f1d6bb6943605a71
- Commits reviewed: 4 (3 `assume:` commits that touch only `tasks/`, and 1 implementation commit 305b21850)

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A 2xx bookmark answer whose body cannot be read is retried, so every run creates another bookmark**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureUploader.kt:104-117`
    - Category: Correctness
    - Problem: `sendSafely(bookmark)` calls the generated `bookmarksApi.createBookmark(request)`, which returns `Response<BookmarkResponse>`. Retrofit decodes the body before it returns the `Response`. If decoding fails, the call throws. That happens when a 201 body does not match the generated model, for example a server that is newer than the app adds a `metadataStatus` value: the client's `Json` has `ignoreUnknownKeys` but not `coerceInputValues`, so an unknown enum value fails to decode. The generic `catch (_: Exception)` turns the exception into `Outcome.Failed(UNEXPECTED_ERROR)`. The row stays sendable with `rejected = false` and the job asks WorkManager to retry. The server has already saved the bookmark, and `POST /bookmarks` has no client id. So each later run saves the same URL again, with no limit. For captures this path is safe because `POST /capture` is idempotent. Recorded assumption #2 accepts a duplicate after a lost answer, which is a rare, single event. It does not cover this repeated case, where the code already knows the server accepted the request.
    - Why it matters: Once the app and server versions drift, the read-later list fills with copies of the same page, one per backoff retry, until the app is updated. The queue entry also never clears from the pending count.
    - Suggested fix: Base the outcome on the HTTP status alone, so that a 2xx counts as `Sent` even if its body cannot be read. Two ways: call the endpoint through a small Retrofit declaration that returns `Response<Unit>` (or `ResponseBody`), or catch the body-decoding exception (`SerializationException`) around `createBookmark` and treat it as `Sent`. Add a test where MockWebServer answers 201 with an unknown `metadataStatus`. It should check that the row is removed and that a second `sendAll()` sends no request.

## P3 Low

- [ ] **CR-2 | The client URL check accepts URLs the server always rejects, and the user is still told "Saved to read later"**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueue.kt:175-178` (used by `android/app/src/main/java/ch/sonensei/cockpit/capture/share/SharedContent.kt:57`)
    - Category: Correctness
    - Problem: `isBookmarkUrl` documents that it rejects "a URL the server always rejects". In fact its authority pattern `[^\s/?#]+` accepts any non-blank authority. The server's `BookmarkUrls.normalize` rejects some of these: a non-numeric or over-long port (`https://example.org:abc/`), an empty host after user info (`https://user@/`), and host names that fail `IDN.toASCII` or the `HOST_NAME` pattern. For such a share the sheet shows the Read later choice and saving shows the success toast. The upload then gets 400, and the row is marked `rejected = true`. It stays in `pending_bookmark` and counts toward the pending count forever. Nothing in the app shows or removes it.
    - Why it matters: These inputs are rare, and the data is kept rather than lost. But the user is told the page was saved, it never reaches the read-later list, and the widget's pending count stays above zero for good.
    - Suggested fix: Make the client check match the server's rules for the authority: a host made of labels or an IPv6 literal, and an optional numeric port of up to 5 digits. Then such shares either get no Read later choice or are refused before they are queued. Add the two example URLs above to the "refused and nothing is queued" test.

## Notes (no finding)

- URL detection: trailing punctuation is stripped, `)` is kept when it is balanced, shares with files get no URL, and `ftp://`, `https://` and plain text give no choice. All of these are implemented and tested.
- Routing: Inbox is the default. Read later bypasses the capture length and attachment checks and calls `CaptureQueue.readLater`. A title equal to the URL is sent as null. `onDestinationChange` ignores calls while saving, after saving, or without a URL. A double submit is blocked by `saving`.
- Room v2: the auto-migration from 1 to 2 only adds the `pending_bookmark` table. `1.json` is unchanged and `2.json` is committed. The production builder has no destructive fallback. `ReadLaterQueueTest` builds a version 1 database from the committed `1.json`, opens it through Room (which checks the schema on open), and confirms that the existing capture is kept.
- Uploader failure handling for bookmarks: 401 or offline leads to retry and stops the run, 408, 429 and 5xx lead to retry, other 4xx mark the row rejected but keep it, and unexpected errors are retried while the other rows are still sent. This mirrors the capture loop. Only CR-1 is an exception.
- Segmented choice: `SingleChoiceSegmentedButtonRow` with a localized label, a localized label on each segment, a decorative icon with no content description, and segments disabled while saving. I found no validated accessibility defect.
- Test evidence: I was not allowed to run Gradle in this review. The existing results in `android/app/build/test-results/testDebugUnitTest` (written 11:57, before HEAD) show `ShareReadLaterTest` at 10 tests and `ReadLaterQueueTest` at 8 tests, all passing. The parent worker has to re-run the criterion-2 command and record the result. Neither criterion is recorded as verified yet (`tasks.py verify`).
- There is no committed test yet for the bookmark 401 path or the unexpected-exception (`Failed`) path. The uncommitted `ReadLaterUploaderTest.kt` from the test-writer may cover them. It should be reviewed once it is committed.

## Review conclusion

Material issues found; fix before completing the task.
