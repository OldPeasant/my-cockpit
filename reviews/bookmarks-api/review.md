# Code Review

Date: 2026-10-21

Task: bookmarks-api - Bookmarks API (review round 3, on the final code after fix commit 2f581586 and test commit 41cda1b2)

Scope: `git diff 169c9434 HEAD` (HEAD bad14d5a merges task commits 5f84adf2..41cda1b2 onto main). This covers the whole bookmarks module (`server/.../bookmarks/{api,domain,infra}`), migration `db/migration/bookmarks/V202610211800__bookmarks_model.sql`, the bookmarks tests, `api/openapi.yaml` and the generated web client under `web/src/app/api/generated/`. Round 3 looks closely at d82afa2f (the TW-2 body cancel), 2f581586 (CR-5..CR-7 and the non-HTML body skip) and 41cda1b2 (size-limit tests). The one uncommitted change in the working tree (`BookmarkUrlsTests.kt`, from the concurrent test-writer) is test-only and was ignored.

Reviewed revision: bad14d5afd5c9801146ae09c3e19262225acfd48

Requirements:
- tasks/bookmarks-api.md: acceptance criteria 1-4 and the recorded assumptions
- SPEC.md §8.4 (bookmarks / read-later), §4.2 (ids, ETag/If-Match, Problem Details), §4.3 (resolver)
- AGENTS.md "Project notes" (module boundaries, migrations, logging, generated files)
- reviews/bookmarks-api/review.md (rounds 1 and 2, CR-1..CR-7) and reviews/bookmarks-api/fixes.md

Git range:
- Branch: main
- HEAD: bad14d5afd5c9801146ae09c3e19262225acfd48
- Base/start: 169c9434be6b9c3c91ddc08d92543e94f11506bb (task base 2780ebe8 per the parent)
- Commits reviewed: 12 (5f84adf2, 1737c020, 02174ada, 2610e590, 64b2bce2, 9f740303, 53d16e73, d82afa2f, 2f581586, 41cda1b2, and the merge bad14d5a)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Round 2 findings: verification

- **CR-5 (a second result of the same fetch could flip FETCHED to FAILED): resolved.** `BookmarkService.applyMetadata` (`domain/BookmarkService.kt:175`) stores a result only while `metadataFetch == fetch && metadataStatus == PENDING`, and `Bookmark.metadataFetched` (`domain/Bookmark.kt:75`) checks the same rule. If two results race, the loser hits the optimistic lock and retries (`BookmarkMetadataWorker.store`). On the retry the row is no longer `PENDING`, so the result is dropped with `false`. Covered by `BookmarkEdgeCaseTests` "only the first result of a fetch is stored ...". The `pendingAgain` test helper only resets the status with JDBC so the older fill/overwrite tests still test what they say. The assertions were not weakened.
- **CR-6 (a request rejected during shutdown was stored FAILED): resolved.** `onRequested` (`infra/BookmarkMetadataWorker.kt:51-58`) returns without storing when `executor.isShutdown`. Only a full queue stores `FAILED`. The test at `BookmarkMetadataWorkerTests.kt:225` expects no store, and the store-failure test (`:239`) now gets its rejection from a full queue, so it still tests what its name says.
- **CR-7 (non-ASCII URL words not searchable): resolved.** `indexBookmark` (`BookmarkService.kt:32-44`) indexes the stored URL plus `BookmarkUrls.readable(url)` when that form differs. `readable` keeps escapes that are invalid UTF-8 or decode to control characters, so no NUL or newline gets into the search text. The stored URL and the fetched URL are unchanged. Covered by `BookmarkUrlsTests` and an end-to-end search test in `BookmarkEdgeCaseTests`.
- **TW-2 / non-HTML bodies (d82afa2f, 2f581586): correct.** `TruncatedBody` requests one item at a time and completes at `max` without cancelling. `send` cancels every registered subscriber in `finally`, after `future.get` has returned or failed (`infra/PageFetcher.kt:202-206`). Answers that are not 2xx HTML get `TruncatedBody(0)` (`:219-225`). That subscriber completes on the first data or at the end of the body, so the bodies of redirects, errors and non-HTML answers are not downloaded. I checked these paths:
  - Cancelling after completion does nothing.
  - On timeout or interrupt, `future.cancel(true)` runs and the subscription is cancelled too.
  - `subscription.join()` in `onNext` cannot block, because `onSubscribe` always comes first.
  - `max = 0` takes 0 bytes and completes.

  Covered by the 9 `PageFetcherSizeLimitTests`: an exact cut at max-size, HTTP/2 connection reuse, HTTP/1.1 connection close, gzip, and large non-HTML, error and redirect bodies.

## Acceptance criteria check (code)

1. **CRUD, tags, read_later/read_at, async OpenGraph with SSRF/timeouts/size limits:** implemented.
   - CRUD and the read state: `BookmarkController` (list with tag/readLater/read filters and a keyset cursor; create 201 with ETag and Location; get; PATCH with field-presence semantics; refresh-metadata; DELETE 204). Read is `PATCH {read}`, using server time.
   - Tags: trimmed, a leading `#` removed, case-insensitive dedup, the existing spelling reused, at most 50 tags of 50 characters each.
   - Metadata fetch: `BookmarkMetadataWorker` runs after commit on a bounded pool and queue.
   - SSRF protection: `PageFetcher` allows http(s) only, no userinfo, ports 80/443 only, and every resolved address must be public (`PublicAddresses`). The check runs again on each redirect, and redirects are followed manually.
   - Timeouts: one deadline covers DNS (on a helper thread), every request and body, and the redirects. The connect timeout is separate.
   - Size limits: the body is cut at 1 MB, also after gzip decompression. Fetched titles and descriptions are cut without splitting a surrogate pair, and the favicon length is checked.
2. **Resolver BOOKMARK, search, trash:** implemented.
   - Resolver: `BookmarkItemResolver` (title or host, subtitle "Read later" or "Bookmark", deep link).
   - Search: the index is updated on every change and on restore, and the entry is removed on delete.
   - Trash: `BookmarkTrashContributor` lists, restores (fetching again when the bookmark was still `PENDING`) and purges (tags cascade). `TrashService` publishes the change events for restore and purge.
3. **`./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.*'`:** I could not run Gradle in this session. The newest results in `server/build/test-results/test/` (2026-10-21T20:43Z, after HEAD was created) show 0 failures and 0 errors in 109 bookmarks tests. By class: EdgeCase 28, MetadataWorker 13, Migration 2, Urls 8 (including the test-writer's uncommitted test), Web 17, PageFetcher 13, PageFetcherEdgeCase 12, PageFetcherSizeLimit 9, PageMetadataParser 7. These tests use Testcontainers Postgres and WireMock.
4. **OpenAPI:** the same run includes `OpenApiContractTest` (1 test, 0 failures). Since round 2, no DTO or endpoint has changed, so the contract and the generated client still match the controller.

Other checks, found correct:
- ETag/If-Match: `ETags.checkIfMatch` runs before any change on PATCH, DELETE and refresh-metadata. The 409, 412 and 428 responses are documented.
- Problem Details: errors go through `InvalidRequestException` and `NotFoundException`. Unknown PATCH fields and null values for fields that cannot be null are rejected with 400.
- Logging: logs carry only bookmark ids, fixed error messages and exception class names, never URLs or page content.
- Migration: in the module's folder, using the naming convention.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Notes (not findings)

- **Criterion evidence:** `python tasks.py verify bookmarks-api` still lists criteria 1-4 as unverified. Before `done`, the parent worker must run the bookmarks test command and `OpenApiContractTest` (without the flag) on the final code and record both. The test results quoted above were produced in this session, but not by a command I ran myself.
- **Stale hash in fixes.md:** round 2 of `fixes.md` describes its changes as "uncommitted on top of HEAD 13737c60". They are in commit 2f581586 (and d82afa2f for the parent's TW-2 part). Set `reviewed_revision` in `quality.json` from the actual HEAD.
- **Accepted behavior, not a defect:** the startup re-queue can still fetch a bookmark a second time when it was saved between the web server starting and `ApplicationReadyEvent`. Since CR-5 is fixed, the second result is ignored, so the only cost is one extra HTTP request.

## Review conclusion

No material issues found. CR-5, CR-6 and CR-7 from round 2 are resolved. The TW-2 body handling and the non-HTML body skip are correct and covered by tests.
