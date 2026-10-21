# Fix Report

Date: 2026-10-21

Task: bookmarks-api - Bookmarks API

Scope: current task; findings CR-1 to CR-4 from `reviews/bookmarks-api/review.md`, and the production bug the test-writer reported (`BookmarkEdgeCaseTests` "cutting a fetched title does not split a character in two" failed). Code commit: 2cf531b4.

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
- New column `metadata_fetch bigint not null default 0`. I edited the existing migration in place: `git cat-file -e origin/main:<migration>` shows it is not on `origin/main`, and the parent allowed this. `Bookmark.requestMetadata()` increments it and sets `PENDING` on create, on a URL change, on refresh and on restore of a pending bookmark.
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

## Final status

All validated findings fixed and validation passed.
