# Code Review

Date: 2026-10-21

Task: bookmarks-api - Bookmarks API (review round 2, on the final code after the fixes)

Scope: `git diff 427fced8..44bd80a8` for all task commits (server/, api/, generated web client). Round 2 looks closely at fix commit 3be41162: URL normalization (`BookmarkUrls`), `PENDING` recovery on startup, rejection, shutdown and restore, the `metadata_fetch` generation, and the surrogate-safe truncation. The generated web client under `web/src/app/api/generated/` was checked only for its contract: the fixes did not change it, and `metadataFetch` is not exposed. Uncommitted test files that the concurrent test-writer is changing are not part of this review.

Requirements:
- tasks/bookmarks-api.md: acceptance criteria 1-4 and recorded assumptions #1-#6
- SPEC.md §8.4 (bookmarks / read-later), §4.2 (ids, ETag/If-Match, Problem Details), §4.3 (resolver)
- AGENTS.md "Project notes" (module boundaries, migrations, logging, generated files)
- reviews/bookmarks-api/review.md (round 1, CR-1..CR-4) and reviews/bookmarks-api/fixes.md

Git range:
- Branch: main
- HEAD: 44bd80a852193c7313eeb6d62a89aec20b18b4c2
- Base/start: 427fced86f5f32ffdf8bc1fa06b22a691d26769a (`git merge-base HEAD origin/main`)
- Commits reviewed: 5 (6ee37686, 13faff64, 61127103, 3be41162, 44bd80a8)

Summary: 0 P0, 0 P1, 0 P2, 3 P3

## Round 1 findings: verification

- **CR-1 (URLs rejected): resolved.** `BookmarkUrls.normalize` (`domain/BookmarkUrls.kt:29`) splits the URL into its parts. It converts the host with `IDN.toASCII`, allows `_` in hosts and IPv6 literals, and percent-encodes characters a URI does not allow as UTF-8. Valid `%XX` escapes are kept, and a stray `%` becomes `%25`. Control characters are refused, and the result must parse as a `java.net.URI`. `BookmarkService.normalizeUrl` (`:265`) checks the length both before and after encoding. `update` compares the normalized form, so the same URL does not trigger a refetch. Covered by `BookmarkUrlsTests` (valid, encoded and refused cases) and the web test at `BookmarkWebTests.kt:291`. Hosts with `_` are saved, but their fetch ends `FAILED`. This is a JDK limitation and is recorded as assumption #6.
- **CR-2 (PENDING forever): resolved.** Each path now ends correctly:
  - A rejected request is stored as `FAILED` (`BookmarkMetadataWorker.kt:51-53`). `applyMetadata` uses `REQUIRES_NEW`, which is correct because it runs in the after-commit phase.
  - Active `PENDING` rows are queued again on `ApplicationReadyEvent` (`:58-69`, `BookmarkRepository.findPendingMetadata`).
  - A fetch interrupted by shutdown stores nothing (`:83`), so the next start picks it up.
  - Restoring a `PENDING` bookmark starts a new generation and publishes a request (`BookmarkTrashContributor.kt:44-48`). `TrashService` is `@Transactional`, so the `@TransactionalEventListener` fires after commit.

  Each path has a test (`BookmarkMetadataWorkerTests` :177, :210, :224, :235, :263, :275; `BookmarkEdgeCaseTests` restore, no-refetch and pending-list tests).
- **CR-3 (older fetch overwrites status): resolved.** `Bookmark.requestMetadata()` increments `metadata_fetch` on create, URL change, refresh and pending restore. `applyMetadata` stores a result only while `metadataFetch == fetch` (`BookmarkService.kt:171`). Optimistic-lock retries re-check the generation. The setters are `protected`, so only the entity methods change the status. The migration was edited in place, and `git ls-tree origin/main` confirms it is not on main. The column is not in the DTOs, so `api/openapi.yaml` is unchanged.
- **CR-4 (mutable fetcher seam): resolved.** `fetcher` is now a `private val` constructor property with a default value (`BookmarkMetadataWorker.kt:34`).
- **TW-1 (surrogate split): resolved.** `takeCodePointSafe` (`BookmarkService.kt:376`) drops a trailing high surrogate at the cut. It is used for the fetched title and description.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-5 | The startup re-queue can fetch a bookmark twice under the same generation, and the second result can flip FETCHED to FAILED**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/BookmarkMetadataWorker.kt:58` (with `BookmarkService.kt:171`)
    - Category: Correctness
    - Problem: Spring Boot starts the embedded web server before `ApplicationReadyEvent`. A bookmark created or refreshed in that window gets its own `BookmarkMetadataRequested`. `requeuePending` then also finds it `PENDING` and queues a second request with the same `metadataFetch`. Both results pass the generation check, because `applyMetadata` does not mark a generation as finished. If the first fetch succeeds (`FETCHED`) and the second fails (for example a timeout), the status ends up `FAILED` even though metadata was stored. Every second apply also bumps the version and publishes another UPDATED event.
    - Why it matters: The window is small (startup only) and the stored fields stay correct, but the status shown to clients can be wrong. This is the same kind of defect as CR-3, reintroduced by the CR-2 recovery path.
    - Suggested fix: Store a result only while the bookmark is still `PENDING`, for example `takeIf { it.metadataFetch == fetch && it.metadataStatus == MetadataStatus.PENDING }`, or enforce the same rule in `Bookmark.metadataFetched`. Then the first result for a generation wins.

- [ ] **CR-6 | A request rejected while the application stops is stored as FAILED, although stopping is meant to leave bookmarks PENDING for the next start**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/BookmarkMetadataWorker.kt:51-53` (compare `:82-83`)
    - Category: Correctness
    - Problem: An interrupted running fetch deliberately stores nothing, and `shutdownNow()` drops queued requests, so those bookmarks stay `PENDING` and are fetched again at the next start. A bookmark saved during the shutdown window (after `destroy()`, while request threads are still finishing) is handled differently: its request is rejected and stored `FAILED` at once, so the next start never fetches it. The test at `BookmarkMetadataWorkerTests.kt:210` locks in this behavior.
    - Why it matters: After a deploy, the user loses the automatic metadata for those bookmarks and has to run refresh-metadata by hand. The two shutdown paths are also inconsistent.
    - Suggested fix: In the `RejectedExecutionException` handler, store `FAILED` only when `!executor.isShutdown` (queue full). When the executor is shut down, leave the row `PENDING` for `requeuePending`. Adjust the test name and expectation to match.

- [ ] **CR-7 | Normalized URLs are indexed in punycode and percent-encoded form, so non-ASCII words in a URL are not searchable**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/BookmarkService.kt:34` (`indexBookmark` body uses `bookmark.url`)
    - Category: Correctness
    - Problem: Since CR-1, `https://bücher.example/Straße` is stored as `https://xn--bcher-kva.example/Stra%C3%9Fe`, and that form goes into the search document. A search for `bücher` or `Straße` no longer matches the URL. `displayTitle` decodes the host only when there is no title.
    - Why it matters: SPEC.md §8.4 requires bookmarks to be searchable. This only affects searches on the URL text of non-ASCII URLs, so the impact is small.
    - Suggested fix: Index a readable form of the URL: Unicode host via `IDN.toUnicode` and percent-decoded path and query, or both forms. Keep the stored and fetched URL unchanged.

## Notes (not findings)

- **Test runs:** I could not run Gradle in this session (permission denied). The latest results in `server/build/test-results/test/` are from 21:01-21:06 on 2026-10-21, just before fix commit 3be41162 (21:06:28). They show 86 bookmarks tests with 0 failures and 0 errors: EdgeCase 22, MetadataWorker 11, Migration 2, Urls 3, Web 17, PageFetcherEdgeCase 11, PageFetcher 13, PageMetadataParser 7. This matches fixes.md.
- **Criterion evidence:** `tasks.py verify bookmarks-api` still shows criteria 1-4 unverified. Criteria 3 and 4 need the actual `./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.*'` and `OpenApiContractTest` runs on the final code, recorded before `done`.
- **Commit hash in fixes.md:** fixes.md names code commit `2cf531b4`. That commit exists but is on no branch, because it was rebased. The code on the branch is 3be41162. Set `reviewed_revision` in `quality.json` from the actual HEAD.
- **Checked and found correct:**
  - The SSRF guard still applies to normalized URLs: `PageFetcher.check` re-parses the URL and checks scheme, userinfo, port and resolved addresses.
  - Logs still carry only ids and class names.
  - `requeuePending` survives a failing query.
  - The `check()` in `metadataFetched` cannot fire, because `applyMetadata` filters first.

## Review conclusion

No material issues found. The round 1 findings CR-1 to CR-4 are resolved. CR-5 to CR-7 are small, optional improvements.
