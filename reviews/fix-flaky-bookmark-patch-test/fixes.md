# Fix Report

Date: 2026-11-02

Task: fix-flaky-bookmark-patch-test - Flaky test: BookmarkWebTests PATCH races the background metadata fetch (409)

Scope: current task. Finding CR-1 from `reviews/fix-flaky-bookmark-patch-test/review-r1.md` (reviewed revision 734ff2fc8, which is 94eff1191 rebased). The code range checked is base 19dd927034426e16a7a6d4c5fa0d13c18e16ab4a to HEAD (commits 94eff1191 and 9d2c5f514). It is test-only and touches just `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkWebTests.kt`.

## Fixed findings

### CR-1 - The slow-fetch ordering test always timed out its slow fetch

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkWebTests.kt` (by the parent worker in commit 9d2c5f514, not by this fixer)

Problem:
The WireMock delay of `/slow` (`SLOW_FETCH_MS = 2_000`) was the same as the class's `cockpit.bookmarks.fetch.timeout=2s`. `PageFetcher` applies that timeout as a deadline for the whole fetch (`PageFetcher.kt:78`, `remaining()` at :209, the request timeout at :177, and `HttpTimeoutException` mapped to `PageFetchError.TIMEOUT` at :199). So the slow fetch always ended as `TIMEOUT`. The test "a fetch that finishes after the URL changed again is ignored" then only checked a stale *failed* fetch, not a stale *successful* one.

Fix:
The parent worker applied the reviewer's suggested fix in commit 9d2c5f514. It raised the class fetch timeout from `2s` to `5s` and kept `SLOW_FETCH_MS = 2_000`. This fixer made no code changes. It checked the following:
- The deadline is now 5 s against a 2 s response delay, which leaves about 3 s for a loaded machine. The slow response arrives in time, so `<title>Slow</title>` is parsed and handed to `applyMetadata` as a successful but stale result. The test's `title == "Fast"` assertion therefore again covers ignoring a stale successful fetch.
- No other test in the class depends on the 2 s value. `timeout` appears only on line 53. The test's `settled()` helper waits 10 s, which is still longer than the 5 s fetch deadline, so fetches that end in failure (`unreachable.invalid` and similar) still finish before `settled` gives up.
- In the run below, the `BookmarkMetadataWorker` log contains no "The server did not answer in time" (`TIMEOUT`) message for any bookmark. The only failure messages are the expected ones: 15 x "could not be reached", 1 x "not HTML", 1 x "answered with an error" and 1 x "Not an http(s) URL". So the slow fetch really completed instead of timing out.

Validation:
- `cd server && ./gradlew test --tests '*BookmarkWebTests*'` - Pass (BUILD SUCCESSFUL; 17 tests, 0 failures, 0 errors, 0 skipped)
- Worker log lines in `build/test-results/test/TEST-ch.sonensei.cockpit.bookmarks.BookmarkWebTests.xml` searched for TIMEOUT - none found

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests '*BookmarkWebTests*'` - Pass, run once (17/17)

Full test suite:
- Not run by this fixer. The parent worker runs the 20x-under-load run and `sh scripts/check.sh` (acceptance criteria 3 and 4).

Lint/type-check/build:
- Test sources compiled as part of the Gradle run above. ktlint was not run separately by this fixer, because no code changed here.

`.taskcheck`:
- Not run by this fixer (the parent worker runs it)

Remaining unresolved findings:
0

## Final status

No valid findings required code changes. CR-1 was valid. The parent worker fixed it in commit 9d2c5f514, and this fixer checked that fix against `PageFetcher`'s timeout handling and with a passing focused run in which the slow fetch did not time out.
