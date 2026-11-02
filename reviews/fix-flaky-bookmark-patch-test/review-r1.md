# Code Review

Date: 2026-11-02

Task: fix-flaky-bookmark-patch-test - Flaky test: BookmarkWebTests PATCH races the background metadata fetch (409)

Scope: task commit 734ff2fc8 on top of base 3494f39d (`git diff 3494f39d..734ff2fc8`); a test-only change to `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkWebTests.kt`. The current HEAD ef7f7701d is the same commit rebased onto the latest main. `git diff 734ff2fc8 ef7f7701d` touches only `tasks/*.md`, so the code is identical. Static review only: Gradle was not run, at the parent's request.

Requirements:
- tasks/fix-flaky-bookmark-patch-test.md (acceptance criteria 1-4, notes)
- AGENTS.md (project notes, evidence rules)
- Production code read for context: `bookmarks/infra/BookmarkMetadataWorker.kt`, `bookmarks/domain/BookmarkService.kt`, `bookmarks/infra/PageFetcher.kt` (timeout handling), `bookmarks/infra/BookmarkFetchProperties.kt`, `bookmarks/infra/BookmarkTrashContributor.kt` (restore re-fetch)

Git range:
- Branch: main
- HEAD: ef7f7701d1d2cff9821fcc305bda1ab92552c5cd (rebased copy of 734ff2fc83e9d6656f45a371aafc3e8ea25f395a)
- Base/start: 3494f39dded577e7917f03642fdb1382194dea22
- Commits reviewed: 1

Summary: 0 P0, 1 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | The slow-fetch ordering test now always times out its slow fetch, so it no longer checks a stale successful result**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkWebTests.kt:256` (with `:53` and `:473`)
    - Category: Tests
    - Problem: The commit raised the WireMock delay of `/slow` from 700 ms to `SLOW_FETCH_MS = 2_000`. This test class sets `cockpit.bookmarks.fetch.timeout=2s` (line 53), and `PageFetcher` enforces that timeout as a deadline for the whole fetch (`PageFetcher.kt:61,78,177`), mapping it to `PageFetchError.TIMEOUT`. A response held back for 2000 ms therefore never arrives in time. In practice the worker always gets a `PageFetchException`, and `BookmarkMetadataWorker.run` passes `metadata = null` to `applyMetadata`. The `<title>Slow</title>` body is never parsed. Before this change, the test checked that a stale fetch which succeeded (it brought a title) was ignored. Now it only checks that a stale failed fetch was ignored. A regression that let a stale successful result fill the title, which is the case the test name describes, would no longer be caught. The task's criteria require the tests to keep checking the same behavior, and this change to the ordering test was not needed for the PATCH flake.
    - Why it matters: The test still passes but no longer covers the behavior it is named after. `BookmarkMetadataWorkerTests` mocks `BookmarkService`, so no other test covers ignoring a stale successful fetch end to end against Postgres.
    - Suggested fix: Keep the delay clearly below the fetch timeout. For example, raise the class's `cockpit.bookmarks.fetch.timeout` to around 5s and keep `SLOW_FETCH_MS = 2_000`. No test in this class depends on the 2 s timeout. Alternatively, revert the delay to a value below 2 s. Then confirm in the 20x stress run that the slow fetch really completes (for example, the worker logs no `TIMEOUT` for that bookmark).

## P2 Medium

None.

## P3 Low

None.

## Other checks (no finding)

- **Cause (AC 1):** confirmed from the code path. `create` saves the bookmark as `PENDING` and publishes `BookmarkMetadataRequested`. After the commit, the worker calls `applyMetadata` in a `REQUIRES_NEW` transaction, which runs `saveAndFlush` and bumps the version. A PATCH carrying the ETag fetched before that bump fails the optimistic lock (409). Recording this in the task notes is the parent's job.
- **`settled(id)` is a sound barrier:** `applyMetadata` only writes while `metadataStatus == PENDING` and `metadataFetch` matches. Once `settled` sees a status other than `PENDING`, that fetch cannot bump the version again. `PATCH` without a URL change and Trash restore of a non-`PENDING` bookmark start no new fetch (`BookmarkService.update`, `BookmarkTrashContributor.kt:44-48`).
- **PATCH test (lines 302-318):** the 428 (no If-Match), 412 (`"999"`), three 400 cases (unknown field, `url:null`, wrong type) and the partial update (`description:null`, tag dedup, title and URL unchanged) are all unchanged. Only the bookmark is now settled first. The 400 cases now use a current ETag, so they fail on validation and not on a stale version, as intended.
- **Other tests that change a freshly created bookmark with its ETag:**
  - Already settled: "a new URL clears the favicon" (226), "refresh-metadata" (243), "read-later queue" (341, 344, 351, re-fetched), "a deleted bookmark goes to the Trash" (419-447) and "every change publishes a change event" (457, 461; the readLater change starts no fetch).
  - Never send an If-Match on a fresh bookmark: tags, list, resolver, search, URL normalization, and 404 with a random id.
  - The one remaining unsettled change is the deliberate race in "a fetch that finishes after the URL changed again is ignored" (263). It cannot wait for `settled` without losing its purpose. Its PATCH must reach the server before the slow response, and the longer delay makes that more robust, but see CR-1.
- **Test evidence (AC 3, AC 4):** not checked here, because Gradle was not run at the parent's request. The parent records the 20x-under-load run and `scripts/check.sh`.

## Review conclusion

Material issues found; fix before completing the task.
