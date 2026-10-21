# Fix Report

Date: 2026-10-21

Task: bookmarks-api - Bookmarks API

Scope: current task. Round 1: findings CR-1 to CR-4 from `reviews/bookmarks-api/review.md`, and the production bug the test-writer reported (`BookmarkEdgeCaseTests` "cutting a fetched title does not split a character in two" failed). Code commit: 2cf531b4 (rebased; on the branch it is 3be41162). Round 2: findings CR-5 to CR-7 (P3) from `reviews/bookmarks-api/review.md` and TW-2 (Medium) from `reviews/bookmarks-api/tests.md`, on top of HEAD 13737c60 (changes uncommitted, for the parent worker to commit).

## Round 1

## Fixed findings

### CR-1 - Valid real-world URLs are rejected with 400 (IDN hosts, underscores, unencoded characters)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/BookmarkUrls.kt` (new)
- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/BookmarkService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/Bookmark.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkUrlsTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkWebTests.kt`

Problem:
`normalizeUrl` only accepted URLs that `java.net.URI` parses with a non-null host. IDN hosts, hosts with `_`, and URLs with spaces, `|`, `[`/`]`, `{`/`}` or non-ASCII characters were rejected with 400.

Fix:
`BookmarkUrls.normalize` splits the URL into scheme, authority, path, query and fragment. It converts the host to ASCII with `IDN.toASCII`. A host must be LDH plus `_` (or an IPv6 literal), and a port must be digits. Characters a URI does not allow are percent-encoded as UTF-8; valid `%XX` escapes are kept and a stray `%` becomes `%25`. Control characters are still refused. The result must parse as a `URI`. A URL that is already a valid URI is stored unchanged, apart from trimming. The normalized form is stored and fetched, and the length limit applies to it. `displayTitle` shows an IDN host in Unicode. Limitation: the JDK HttpClient cannot request a host containing `_` (its `URI.host` is null). Such a bookmark is saved, but its fetch ends `FAILED` (`INVALID_URL`).

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.*'` - Pass. New: `BookmarkUrlsTests` (3 tests) and `BookmarkWebTests` "URLs as browsers copy them ... are stored in a safe form" (201 for an IDN host, `|` and a space in the query, `_` host, `[1]` in the path, `{y}` in the query)

### CR-2 - `metadataStatus` can stay `PENDING` forever

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/BookmarkMetadataWorker.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/BookmarkTrashContributor.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/BookmarkRepository.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/BookmarkFetchProperties.kt` (doc of `queueSize`)
- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/BookmarkService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkMetadataWorkerTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkEdgeCaseTests.kt`

Problem:
A request rejected by the executor (queue full or shutdown) was only logged. Requests lost on stop or restart were never fetched again. A bookmark deleted while its fetch ran and then restored was never fetched again.

Fix:
- Rejected requests are stored as `FAILED` right away. `applyMetadata` now uses `@Transactional(REQUIRES_NEW)`, because this call runs after the commit of the saving transaction and must not join that finished transaction.
- On `ApplicationReadyEvent`, the worker calls the new `BookmarkService.pendingMetadataRequests()` (repository `findPendingMetadata`: active bookmarks with status `PENDING`) and queues each one again (`overwrite = false`).
- A fetch that is interrupted because the executor is shutting down no longer writes `FAILED`. The bookmark stays `PENDING`, so the next start fetches it again.
- `BookmarkTrashContributor.restore` starts a new fetch (new fetch number) for a bookmark that is still `PENDING`, and publishes `BookmarkMetadataRequested`.

Test changes, made because the required behavior changed:
- In `BookmarkMetadataWorkerTests`, "requests beyond the queue size are dropped" and "after shutdown requests are dropped" now expect exactly one `applyMetadata(..., null)` call (stored as `FAILED`) instead of none. The tests were renamed to match.
- New tests: a rejected request whose store fails does not fail the caller; startup re-queue fetches and stores pending requests; a startup load failure does not stop the application; restoring a bookmark deleted while it was being fetched fetches again (WireMock, 2 requests, ends `FETCHED`); restoring a finished bookmark does not fetch again; `pendingMetadataRequests` lists only active `PENDING` bookmarks.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.*'` - Pass

### CR-3 - An older fetch that finishes later can overwrite `metadataStatus` of a newer request

Files changed:
- `server/src/main/resources/db/migration/bookmarks/V202610211800__bookmarks_model.sql`
- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/Bookmark.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/BookmarkCommands.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/BookmarkService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkEdgeCaseTests.kt`

Problem:
`applyMetadata` dropped only results for a URL that no longer matched, so two fetches of the same URL could overwrite each other's status.

Fix:
- New column `metadata_fetch bigint not null default 0`. I edited the existing migration in place: `git cat-file -e` on the migration path at `origin/main` shows it is not on `origin/main`, and the parent allowed this. `Bookmark.requestMetadata()` increments it and sets `PENDING` on create, on a URL change, on refresh and on restore of a pending bookmark.
- `BookmarkMetadataRequested` carries the number (`fetch`). `applyMetadata(id, fetch, overwrite, metadata)` stores a result only while `metadataFetch == fetch`. This also covers URL changes, so the URL argument was replaced by the fetch number. `metadataStatus` and `metadataFetch` have protected setters, so only the entity methods change them.
- The column is internal. It is not in the API DTOs, so the OpenAPI contract is unchanged.

Test changes:
`BookmarkEdgeCaseTests` calls `applyMetadata` with `bookmark.metadataFetch` instead of `bookmark.url`. The "since re-pointed" case uses `metadataFetch + 1`. New test: "the result of an older fetch is ignored once a newer one was requested".

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.*'` - Pass

### CR-4 - Mutable test seam `fetcher` is unused and not thread-safe

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/BookmarkMetadataWorker.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkMetadataWorkerTests.kt`

Problem:
`internal var fetcher` was a non-volatile, mutable field. The test-writer's queue test assigns it after construction.

Fix:
`private val fetcher: PageFetcher = PageFetcher(properties)` is now a constructor property. Spring uses the Kotlin default value, so no bean is needed. The test passes its blocking `PageFetcher` to the constructor instead of assigning the field.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.*'` - Pass (Spring contexts start with the default)

### TW-1 - Cutting a fetched title splits a surrogate pair (test-writer production bug)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/BookmarkService.kt`

Problem:
`String.clean(maxLength)` used `take(maxLength)`. Cutting 499 characters plus an emoji at 500 left a lone high surrogate.

Fix:
New `takeCodePointSafe(maxLength)` drops a trailing high surrogate at the cut. `clean` uses it for both the fetched title (500) and the fetched description (2000). I found no other truncation of text in the module (`PageMetadataParser` cuts nothing; user input is validated, not cut). The test is unchanged.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.*'` - Pass ("cutting a fetched title does not split a character in two" now passes)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.*'` - Pass: 86 tests, 0 failures, 0 skipped (EdgeCase 22, MetadataWorker 11, Migration 2, Urls 3, Web 17, PageFetcherEdgeCase 11, PageFetcher 13, PageMetadataParser 7)

Full test suite:
- `cd server && ./gradlew check` (ktlint and all server tests, including `OpenApiContractTest` without the update flag) - Pass

Lint/type-check/build:
- `./gradlew ktlintFormat` was run. Besides the changed files, it reformatted one chained call in `BookmarkMigrationTests.kt` (formatting only, needed for `ktlintCheck`). `ktlintCheck` passes as part of `check`.
- API contract: no change (the new column is not exposed), so `api/openapi.yaml` and the web client were not regenerated. `OpenApiContractTest` passes.

`.taskcheck`:
- Pass (`sh scripts/check.sh` -> "all checks passed")

Remaining unresolved findings:
0

## Round 2

Base commit a1266a77, HEAD 13737c60, changes uncommitted.

## Fixed findings

### CR-5 - The startup re-queue can fetch a bookmark twice under the same fetch number, and the second result can flip FETCHED to FAILED

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/BookmarkService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/Bookmark.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkEdgeCaseTests.kt`

Problem:
`applyMetadata` checked only the fetch number. A second result for the same fetch (one request from the save, one from `requeuePending`) was stored too: it could turn `FETCHED` into `FAILED`, and it bumped the version and published another UPDATED event.

Fix:
`applyMetadata` stores a result only while the bookmark is `PENDING` and `metadataFetch == fetch`, so the first result of a fetch wins. Later results return `false` without saving or publishing. `Bookmark.metadataFetched` checks the same invariant. KDoc updated.

Test changes, made because the required behavior changed: the `applyMetadata` tests in `BookmarkEdgeCaseTests` start from a bookmark whose own background fetch already finished (`FAILED`), and some call `applyMetadata` more than once for the same fetch. With "first result wins", every such call would now be ignored. The new helper `pendingAgain(bookmark)` sets the status back to `PENDING` with jdbc and keeps the fetch number. It runs before each such call, so the tests still check filling, overwrite, cleaning, cutting and favicon limits. The assertions are unchanged. In "metadata for an unknown, trashed or since re-pointed bookmark is ignored", the bookmark is made `PENDING` before the stale and trashed calls, so these calls are refused only for the reason the test names. New test: "only the first result of a fetch is stored, a second one of the same fetch is ignored". A failed result and a second metadata result for the same fetch both return `false`. Status stays `FETCHED`, title, version and change events are unchanged.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.bookmarks.*'` - Pass

### CR-6 - A request rejected while the application stops is stored as FAILED instead of staying PENDING

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/BookmarkMetadataWorker.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkMetadataWorkerTests.kt`

Problem:
`onRequested` stored every `RejectedExecutionException` as `FAILED`, also when the executor was shut down. Those bookmarks were never fetched again at the next start. Interrupted and dropped requests stay `PENDING`, so the shutdown paths did not match.

Fix:
When the executor is shut down, the rejected request stores nothing: it logs the bookmark id at INFO and returns, and `requeuePending` fetches the bookmark at the next start. A full queue still stores `FAILED` at once. Class KDoc updated.

Test changes, made because the required behavior changed:
- "after shutdown requests are stored as failed without failing the caller" is renamed to "after shutdown requests store nothing and do not fail the caller, so the bookmark stays pending". It now expects no `applyMetadata` call and no HTTP request.
- "a rejected request that cannot be stored does not fail the caller" used shutdown to get a rejection. After this fix, shutdown stores nothing, so the test would no longer test its subject. It now fills the queue instead (1 thread, queue size 1, blocking resolver), so the third request is rejected. `applyMetadata` for that request throws, and the test checks that it was called exactly once and that `onRequested` returned normally.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.bookmarks.*'` - Pass (BookmarkMetadataWorkerTests 13/13)

### CR-7 - Normalized URLs are indexed only in punycode and percent-encoded form

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/BookmarkUrls.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/BookmarkService.kt` (`indexBookmark`)
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkUrlsTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkEdgeCaseTests.kt`

Problem:
The search body held only the stored URL, for example `https://xn--bcher-kva.example/Stra%C3%9Fe`, so the words of a non-ASCII URL were not searchable.

Fix:
New `BookmarkUrls.readable(url)` gives the URL as a person reads it: the host in Unicode (`IDN.toUnicode`), and path, query and fragment percent-decoded as UTF-8. Userinfo, port and IPv6 hosts stay unchanged. Some runs of escapes stay encoded: runs that are not valid UTF-8, and runs that decode to a control character. This keeps NUL and newlines out of the search text. The result is `null` when it equals the URL. `indexBookmark` writes the stored URL and then, when different, the readable form into the body. The stored and fetched URL are unchanged.

Tests: `BookmarkUrlsTests` gets three new tests: Unicode host and decoded parts, invalid or control escapes kept, no readable form for a URL that already reads as it is. `BookmarkEdgeCaseTests` gets "a URL with a Unicode host and non-ASCII path is found by its words as typed". It checks the exact search body (stored form, then readable form) and that `/api/v1/search` finds the bookmark by `bücher` and by `Gärten`. The bookmark has its own title, so the host is not in the search title. I checked that the test catches the bug: with the readable form taken out of `indexBookmark`, it fails, and it passes again with the fix.

Limitation (not changed): PostgreSQL's text-search parser reads a URL as one `url`/`host`/`url_path` token. A word that is glued to the path by `/` (for example `Straße` in `/Straße`) is not a separate token, for ASCII URLs as well. Words separated by decoded spaces and the host (prefix match) are found.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.bookmarks.*'` - Pass (BookmarkUrlsTests 7/7, BookmarkEdgeCaseTests 28/28)

### TW-2 - A page larger than max-size intermittently fails as UNREACHABLE over HTTP/2

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/PageFetcher.kt` (the parent worker's fix in 13737c60, reviewed, plus one improvement below)
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/PageFetcherSizeLimitTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkEdgeCaseTests.kt` (test race, see below)

Problem:
`TruncatedBody.onNext` cancelled the subscription as soon as it reached max-size. Over HTTP/2 this raced with the client failing the whole exchange ("Stream N cancelled"), and `send` then reported `UNREACHABLE`.

Fix (13737c60, reviewed):
`TruncatedBody` requests one item at a time. At the limit it completes its body and stops requesting, without cancelling. `send` registers every body subscriber it creates and cancels them in a `finally` block after `future.get` returns or fails. I checked these paths:
- HTTP/2: the response future of `sendAsync` completes when the body future completes. A stream reset after that cannot fail the response that was already returned. The 50 fetches over one reused h2c connection pass. Stopping the requests limits buffering to the stream's flow-control window, and only until the cancel right after `get`.
- HTTP/1.1: the connection stays unread until the cancel. The cancel closes it, so it does not go back to the pool with half a body. The new test "over HTTP 1_1 a page larger than max-size is cut off and its connection is closed, not left open" uses a raw socket server that announces 100 MB and keeps writing. It gets exactly max-size bytes and checks that the server sees the client close the connection within 5 s.
- Timeout and interrupt: `future.cancel(true)` aborts the exchange, and the `finally` block also cancels the subscription, so neither path leaks a running download. On an `ExecutionException` (for example "a connection closed in the middle of the body") the subscription has already ended, and cancelling it again does nothing.
- Retries: if the JDK client retries a GET, the body handler runs again. Every subscriber goes into the queue, and all of them are cancelled.

Improvement found while reviewing:
Answers that are not 2xx HTML (redirects, errors, other content types) used `BodySubscribers.replacing`, and that subscriber downloads the whole body before the response completes. A large non-HTML answer was therefore read up to the timeout and reported as `TIMEOUT` instead of `NOT_HTML`. This contradicts the KDoc ("the rest is not downloaded") and wastes bandwidth. I confirmed it with the new test "a large answer that is not HTML is not downloaded, it fails as not HTML at once" (100 KB `video/mp4` sent over 8 s, fetch timeout 5 s). Before the change it failed after 5.6 s: expected NOT_HTML, but was TIMEOUT. Such bodies now get a `TruncatedBody(0)`. It completes empty with the first data or at the end of the body, and `send` cancels it like the others. A drawback: a redirect over HTTP/1.1 now closes its connection instead of draining it for reuse. That costs one extra connect per redirect. The KDoc of `PageFetcher` and `body` is updated.

Test change (test only): "a 51st tag on PATCH is rejected and the bookmark stays unchanged" failed once in this round with 412 instead of 400. It read the bookmark's version right after create, and the background fetch of the 404 page then stored `FAILED` and bumped the version before the PATCH. This is a race in the test, and the faster 404 handling makes it more likely. The test now waits for the fetch (`settled`) before it reads the version, as the other PATCH tests in that class already do. The assertions are unchanged.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.PageFetcherSizeLimitTests' --tests 'ch.sonensei.cockpit.bookmarks.PageFetcherTests' --tests 'ch.sonensei.cockpit.bookmarks.PageFetcherEdgeCaseTests' --rerun` - Pass, 3 runs in a row (SizeLimit 7, PageFetcher 13, PageFetcherEdgeCase 12)
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.bookmarks.*'` - Pass

## Not fixed (round 2)

None.

## Validation summary (round 2)

Focused tests:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.bookmarks.*'` (Testcontainers Postgres, WireMock) - BUILD SUCCESSFUL: 106 tests, 0 failures, 0 errors, 0 skipped (EdgeCase 28, MetadataWorker 13, Migration 2, Urls 7, Web 17, PageFetcherEdgeCase 12, PageFetcher 13, PageFetcherSizeLimit 7, PageMetadataParser 7)
- The three PageFetcher classes with `--rerun`, 3 runs in a row - Pass

Full test suite:
- Not run in round 2. The parent worker runs `./gradlew check` and `.taskcheck`.

Lint/type-check/build:
- `cd server && ./gradlew ktlintCheck` - Pass (part of the command above)
- API contract: unchanged (no DTO or endpoint change), so `api/openapi.yaml` was not regenerated

`.taskcheck`:
- Not run in round 2. The parent worker runs it before `done`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed: round 1 (CR-1 to CR-4, TW-1) and round 2 (CR-5, CR-6, CR-7, TW-2). In round 2, ktlint and the bookmarks test suite passed. The parent worker still has to run `.taskcheck`.
