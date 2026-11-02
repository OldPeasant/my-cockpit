# Code Review

Date: 2026-11-02

Task: fix-flaky-bookmark-patch-test - Flaky test: BookmarkWebTests PATCH races the background metadata fetch (409)

Scope: `git diff 19dd927034426e16a7a6d4c5fa0d13c18e16ab4a..e4bc1df5dbb87cca26f788380465985f75303705`. The code commits are 94eff1191 (settle before PATCH) and 9d2c5f514 (fetch timeout 5s, which fixes CR-1). The other two commits touch only `reviews/`. This is a test-only change to `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkWebTests.kt`. Static review only: Gradle was not run, at the parent's request. The working tree was clean.

Requirements:
- tasks/fix-flaky-bookmark-patch-test.md (acceptance criteria 1-4, notes, recorded assumption about the 5s timeout and 2000 ms delay)
- AGENTS.md (project notes, evidence rules)
- reviews/fix-flaky-bookmark-patch-test/review-r1.md (CR-1) and fixes.md
- Production context as described in review-r1 (`BookmarkMetadataWorker`, `BookmarkService.applyMetadata`, `PageFetcher` timeout handling)

Git range:
- Branch: main
- HEAD: e4bc1df5dbb87cca26f788380465985f75303705
- Base/start: 19dd927034426e16a7a6d4c5fa0d13c18e16ab4a
- Commits reviewed: 4 (2 with code changes, 2 that touch only reviews/)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Other checks (no finding)

- **CR-1 (from review-r1) is resolved.** The class fetch timeout is now `5s` (line 53), and the `/slow` WireMock delay is `SLOW_FETCH_MS = 2_000` (lines 256 and 473). The fetch deadline is 3 s longer than the response delay, so the slow fetch ends in a stale *successful* result (`<title>Slow</title>`). The `title == "Fast"` assertions (lines 267-268) therefore again check that such a stale result is ignored. The fixer's run log showed no TIMEOUT message from the worker. The parent recorded the change as an assumption in the task notes.
- **The ordering test is robust in both directions.**
  - Normally, the PATCH (line 263) reaches the server well before the 2 s response, `settled` returns after the fast fetch, and `Thread.sleep(SLOW_FETCH_MS + 300L)` (line 265) waits until the stale slow result has been delivered and dropped.
  - Under extreme load, the PATCH could arrive after the slow result. The slow result would then be applied first, the PATCH would change the URL, and the fast fetch would overwrite the title with "Fast". The test still passes in that case, so the longer delay cannot cause a new false failure.
  - The new comment at line 261 explains why the delay matters.
- **The 5s timeout does not affect the other tests.** `settled()` waits 10 s (line 135), which is still longer than the 5 s fetch deadline. The `unreachable.invalid` hosts fail at DNS resolution, not on the timeout. No other test in the class depends on the timeout value.
- **The PATCH test (lines 301-318) checks the same behavior as before.** The bookmark is settled before the first request. The 428, 412 (`"999"`), three 400 cases and the partial-update assertions are unchanged. Settling is a sound barrier: `applyMetadata` only writes while the status is `PENDING`, and none of these PATCH requests changes the URL, so no new fetch starts. As a result, the `fetch()`/`change()` pair at lines 310-311 can no longer race a version bump. This fixes the reported 409 at its cause.
- **Other tests that change a freshly created bookmark all settle first** (lines 184, 198, 211, 224/230, 239/246, 337-338, 417, 457). The one exception is the deliberate race in the ordering test, which cannot wait for `settled` without losing its purpose.
- **Quality:** the constant is named and placed in the companion object, matching `BOOKMARKS`. The comments are accurate and the change has no debug output. No production code, build files or other tests changed.
- **Test evidence (criteria 3 and 4)** was not checked here, because Gradle was not run. The parent records the 20x-under-load run and `sh scripts/check.sh` with `tasks.py verify`. Criterion 2 has no recorded evidence yet either. From the code, it is satisfied.

## Review conclusion

No material issues found.
