# Code Review

Date: 2026-10-22

Task: and-bookmarks-share - Android: share URL to read later

Scope: `git diff d7f0f1cc9..68b87bc99` (committed changes only): the implementation commit 5ffa92b8c and the fix commit 68b87bc99, which addresses the earlier findings CR-1 and CR-2. Uncommitted test files from the parallel test-writer (`ReadLaterUploaderTest.kt`, `ShareReadLaterActivityTest.kt`, `ShareReadLaterEdgeTest.kt`) are outside this scope.

Requirements:
- tasks/and-bookmarks-share.md (acceptance criteria 1-2 and three recorded assumptions)
- SPEC.md §8.4 (read-later), §9.3 (offline capture)
- docs/ARCHITECTURE.md and the AGENTS.md project conventions
- Server contract: api/openapi.yaml `CreateBookmarkRequest` / `BookmarkResponse`, `server/.../bookmarks/domain/BookmarkUrls.kt`, `BookmarkService.normalizeUrl`
- The previous reviews/and-bookmarks-share/review.md and fixes.md (to check that the fixes are correct)

Git range:
- Branch: main
- HEAD: 68b87bc997230ce22b2b1840f23a5a1b8a14fb16
- Base/start: d7f0f1cc99024751eb2bb8efcf3b7360b6c28645 (`git merge-base HEAD origin/main`)
- Commits reviewed: 2 (5ffa92b8c, 68b87bc99)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The fix for the earlier CR-1 only catches `SerializationException`, so a 2xx body with a date-time or UUID the client cannot read can still save duplicates**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureUploader.kt:108-121`
    - Category: Correctness
    - Problem: The generated `BookmarkResponse` decodes `createdAt`/`updatedAt`/`readAt` with `OffsetDateTimeAdapter` (`OffsetDateTime.parse`, which throws `DateTimeParseException`) and `id`/`snapshotFileId` with `UUIDAdapter` (`UUID.fromString`, which throws a plain `IllegalArgumentException`). kotlinx.serialization does not wrap these exceptions in `SerializationException`. The project already documents this behavior in `core/network/TokenRefresher.kt:44-48`, which catches `IllegalArgumentException` and `DateTimeException` for that reason. In `sendSafely(bookmark)` those exceptions reach the outer `catch (_: Exception)` and become `Outcome.Failed`. The row stays sendable, so every retry posts the same bookmark again. That is the same unbounded-duplicate case the earlier CR-1 described.
    - Why it matters: The cause is less likely than an unknown enum value. Still, if the server's date-time format and the client's ever drift apart, the read-later list fills with copies, one per backoff retry.
    - Suggested fix: Decide the outcome from the HTTP status alone. One way is a small Retrofit declaration that returns `Response<Unit>` or `Response<ResponseBody>`. The other is to count any exception thrown after a 2xx as sent, for example by also catching `IllegalArgumentException` and `DateTimeException`, as `TokenRefresher` does. Extend the regression test with a 201 whose `createdAt` is not ISO-8601.

- [ ] **CR-2 | The client URL check compares the raw length, but the server limits the normalized (percent-encoded) length**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueue.kt:186-187` (server: `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/BookmarkService.kt:271-273`)
    - Category: Correctness
    - Problem: `isBookmarkUrl` says it accepts only URLs that are "short enough for the server", but it checks `url.length <= 2048` on the raw string. `normalizeUrl` on the server first percent-encodes non-ASCII characters and characters a URI does not allow, which turns each one into 3-12 characters. It then rejects a result longer than 2048 with 400. A shared URL with a long non-ASCII path, for example a decoded Cyrillic or CJK Wikipedia link of a few hundred characters, passes the client check. The user then sees "Saved to read later", and the upload is marked `rejected = true` and stays in the pending count for good. This is the same failure as the earlier CR-2, reached through the length rule instead of the authority rule.
    - Why it matters: These inputs are rare and nothing is lost, but the user is told the page was saved when it never will be.
    - Suggested fix: Compute the length the way the server's encoding would, counting the UTF-8 percent-encoded length of non-ASCII and disallowed characters, and compare that with 2048. Add a test with a URL that is short before encoding and too long after.

## Notes (no finding)

- Earlier CR-1 (an unreadable 2xx body counted as a failure): the main case is fixed and tested. A 201 with an unknown `metadataStatus` now counts as sent, the row is removed, and a second run sends nothing. The request side cannot throw `SerializationException`, because `CreateBookmarkRequest` has only strings and a boolean, so no queued bookmark is wrongly dropped. The remaining gap is CR-1 above.
- Earlier CR-2 (authority check): fixed. `isHostAndPort` follows `BookmarkUrls.normalize` line by line: the part after the last `@`, an IPv6 bracket, `PORT` `\d{0,5}`, `IDN.toASCII(ALLOW_UNASSIGNED)` and `HOST_NAME`. The regexes copy the server's. The tests cover both invalid authorities (refused, nothing queued) and valid ones (user info, IPv6 with a port, `_` with a trailing dot, an IDN host).
- Routing: the choice appears only for a share that has an http(s) URL and no files, and Inbox is the default. Read later skips the capture text, attachment and notes checks. A title equal to the URL is sent as null, and a title over 500 characters is shortened without splitting a surrogate pair. `saving` blocks a double submit. A failed store shows `FAILED` and keeps the sheet open, and because nothing was inserted, a retry cannot create a duplicate. `onDestinationChange` is ignored while saving, after saving, and when there is no URL. The capture notes are kept when the user switches back to Inbox.
- Queue and upload: bookmarks are sent after the captures, with the same outcome mapping (offline or 401 retries and stops the run, 408/429/5xx retries, other 4xx keeps the row as rejected, unexpected errors are retried while the remaining rows are still sent). The pending count and the last error combine both tables. Rejected bookmarks stay in the queue the same way rejected captures already do. Room v2 is a pure auto-migration that adds a table. `2.json` is committed, `1.json` is unchanged, and a migration test opens a v1 database built from `1.json`.
- Criterion 2 is covered by Robolectric and Compose tests in `capture.share` (`ShareReadLaterTest`: parser, ViewModel routing, and the sheet's segmented choice and changing fields). I was not allowed to run Gradle. `fixes.md` reports `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.*'` and `sh scripts/check.sh` passing. `tasks.py verify and-bookmarks-share` still shows both criteria unverified. The parent worker has to record evidence before `done`.
- I found no secrets, personal data or logging of shared content in the diff.

## Review conclusion

No material issues found. The two P3 findings are minor resilience gaps in the earlier fixes. They can be fixed or recorded as accepted before the task is completed.
