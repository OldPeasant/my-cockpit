# Fix Report

Date: 2026-10-22

Task: and-bookmarks-share - Android: share URL to read later

Scope: findings CR-1 and CR-2 from `reviews/and-bookmarks-share/review.md` (base 16c11c075, implementation commit 3e721b304)

## Fixed findings

### CR-1 — A 2xx bookmark answer whose body cannot be read is retried, so every run creates another bookmark

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureUploader.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/ReadLaterQueueTest.kt`

Problem:
`sendSafely(bookmark)` turned a body-decoding failure of a 2xx answer (e.g. an unknown `metadataStatus` from a newer server) into `Outcome.Failed`. The row stayed sendable, so every retry saved the same bookmark on the server again.

Fix:
Around `bookmarksApi.createBookmark(request)`, catch `kotlinx.serialization.SerializationException` and return `Outcome.Sent`. Retrofit only decodes the body of a 2xx answer, and the request serializer cannot throw this exception, so it only means "the server saved it, but its answer does not fit this app's model". I kept the generated `BookmarksApi` rather than adding a separate Retrofit interface that returns `Response<Unit>`. That keeps the uploader's constructor and DI unchanged, and the test files that mock `BookmarksApi` still work. Other unexpected exceptions still give `Failed`.

Regression test: `a 2xx answer that cannot be read still counts as sent, so the bookmark is not saved twice`. MockWebServer answers 201 with `metadataStatus: "SOMETHING_NEW"`. The test checks that the row is removed, the pending count is 0, and a second `sendAll()` sends no request. When the catch was temporarily disabled, the test failed ("10 tests completed, 1 failed"). With the fix it passes.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.queue.ReadLaterQueueTest'` without the fix — Fail (as expected); with the fix — Pass

### CR-2 — The client URL check accepts URLs the server always rejects

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueue.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/ReadLaterQueueTest.kt`

Problem:
`isBookmarkUrl` accepted any non-blank authority. The server's `BookmarkUrls.normalize` rejects some of them: a non-numeric or over-long port, an empty host after user info, a malformed IPv6 literal, and a host that fails `IDN.toASCII` or the host-name pattern.

Fix:
`isBookmarkUrl` now applies the same authority rules as the server. It takes the part after the last `@`. That part must be an IPv6 literal `[hex:.]` or a host whose `IDN.toASCII` form matches the server's `HOST_NAME` pattern (labels of `A-Za-z0-9_-`, optional trailing dot). It may be followed by `:` and a port of 0-5 digits. The regex constants are copies of the server's. The length limit and the whitespace-free http(s) shape are unchanged. `ShareIntentParser` uses the same check, so shares with such URLs no longer offer Read later.

Tests: the "refused and nothing is queued" test now also covers `https://example.org:abc/`, `https://example.org:123456/`, `https://user@/`, `https://[::1/`, `https://[::1]x/` and `https://exa$mple.org/`. A new test, `URLs with an authority the server accepts are queued`, checks that valid forms are still accepted: a plain host, a host with port, query and fragment, user info, an IPv6 literal with port, `_` with a trailing dot, and an IDN host.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.*' lintDebug` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.*'` — Pass (24 classes, 220 tests, 0 failures. ReadLaterQueueTest has 10 tests. The run includes the uncommitted test-writer files ReadLaterUploaderTest, ShareReadLaterActivityTest and ShareReadLaterEdgeTest, which all pass.)

Full test suite:
- `sh scripts/check.sh` (server, web, android lint + unit tests) — Pass ("all checks passed")

Lint/type-check/build:
- `cd android && ./gradlew lintDebug` — Pass
- `cd android && ./gradlew ktlintCheck` — Pass

`.taskcheck`:
- Pass (`sh scripts/check.sh`, exit 0)

Remaining unresolved findings:
0

# Round 2

Date: 2026-10-22

Scope: findings CR-1 and CR-2 (both P3) from the second `reviews/and-bookmarks-share/review.md` (reviewed range d7f0f1cc9..68b87bc99). The parent worker had already committed a fix attempt for both in c87b42d84 (merged in 8358e093f), without the fixer. This round checks that commit against the server (`BookmarkUrls.normalize`, `BookmarkService.normalizeUrl`) and `core/network/TokenRefresher.kt`. Both findings are resolved by c87b42d84, and both have regression tests, so this round changes no code.

## Fixed findings (round 2)

### CR-1 — Only `SerializationException` is caught after a 2xx, so an unreadable date-time or UUID still causes retries and duplicates

Files changed (in c87b42d84):
- `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureUploader.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/ReadLaterQueueTest.kt`

Problem:
The generated adapters throw `DateTimeParseException` (`OffsetDateTimeAdapter`) and plain `IllegalArgumentException` (`UUIDAdapter`), and kotlinx.serialization does not wrap either one. Those exceptions reached the outer `catch (_: Exception)` and became `Outcome.Failed`, so each retry saved the bookmark again.

Fix (verified):
`sendSafely(bookmark)` now also catches `DateTimeException` and `IllegalArgumentException` around `bookmarksApi.createBookmark(request)` and returns `Outcome.Sent`. These are the same exceptions `AuthApiTokenRefresher` catches. I checked whether the call could throw them before an answer arrives, which would drop a bookmark the server never saved. It can't. The request is built outside this inner try. `CreateBookmarkRequest` has only strings and a boolean. OkHttp passes a throwable from the async interceptor chain to Retrofit as an `IOException` ("canceled due to ..."), and that is still treated as `Retry(OFFLINE)`.

Regression test: `a 2xx answer with a date-time or id the client cannot parse still counts as sent`. It sends a 201 with `createdAt: "yesterday"` and a 201 with `id: "not-a-uuid"`. For each, the test checks that the row is removed, the pending count is 0, and a second `sendAll()` sends nothing. Mutation check: I swapped each new catch, one at a time, for an unrelated exception type, and `ReadLaterQueueTest` failed each time ("12 tests completed, 1 failed"). After I restored the code it passes, and `git status` is clean.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.queue.ReadLaterQueueTest'`: Fail with either catch disabled (as expected), Pass with the committed code

### CR-2 — The client URL check compares the raw length, but the server limits the percent-encoded, normalized length

Files changed (in c87b42d84):
- `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueue.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/ReadLaterQueueTest.kt`

Problem:
`isBookmarkUrl` compared `url.length` with 2048. The server percent-encodes the URL first and then rejects a normalized length over 2048. A short URL with many non-ASCII characters passed the client check and was then rejected for good.

Fix (verified):
`isBookmarkUrl` now computes the normalized length the way `BookmarkUrls.normalize` builds the URL, and compares that with 2048. I compared the two side by side:
- The same scheme/authority/path/query/fragment split.
- The same `USER_INFO`, `PATH` and `QUERY` character sets.
- The same encoding loop: valid `%XX` kept, every other disallowed code point counted as 3 x its UTF-8 byte count, with a lone surrogate as 1 byte, as `toByteArray` does on the server.
- The same IDN-to-ASCII host form and the same port handling.

The raw-length check before encoding is still there, as on the server, which checks `trim().length` first. The client also trims before the check. Two differences remain. The client refuses whitespace, and it does not run the server's final `URI(...)` parse. Both are outside CR-2: the first only makes the client stricter, and the second belongs to the authority rules that round 1 covered.

Regression test: `the length limit counts the URL as the server encodes it`. `https://example.org/` + 338 x `é` is 358 characters raw and 2048 once encoded, and it is accepted. One more `a` (2049 encoded) is refused. The test also covers: already encoded `%C3%A9` escapes kept as they are, an IDN host counted in its punycode form (on both sides of the limit), and a second `#` in the fragment counted as `%23`.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.*'`: Pass

## Not fixed (round 2)

None.

## Validation summary (round 2)

Focused tests:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.*'`: Pass
- Mutation check of CR-1 (each new catch disabled in turn, `ReadLaterQueueTest`): Fail as expected, then reverted

Full test suite:
- `sh scripts/check.sh` (server, web, android lint + unit tests): Pass ("all checks passed")

Lint/type-check/build:
- `ktlintCheck`: Pass (part of the focused run). `lintDebug`: run as part of `scripts/check.sh`, Pass

`.taskcheck`:
- Pass (`sh scripts/check.sh`)

Remaining unresolved findings (both rounds):
0

# Round 3

Date: 2026-10-22

Scope: finding TW-3 from the round-2 `reviews/and-bookmarks-share/tests.md`. Its failing regression test (`ReadLaterUploaderTest`, "an IPv6 literal the server cannot parse as a URI is refused and nothing is queued") was committed in d6dd072f2 and is left unchanged. Also FLAKE-1: the parent worker put the `ShareReadLaterTest` flake found while validating TW-3 in scope.

## Fixed findings (round 3)

### TW-3 — The client accepts IPv6 literals that the server's final URI parse rejects

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueue.kt`

Problem:
`CaptureQueue.isBookmarkUrl` only checked a bracketed host against the server's loose `IPV6` pattern (`\[[0-9A-Fa-f:.]+]`). `BookmarkUrls.normalize` on the server also requires the normalized URL to parse with `java.net.URI(...)`. So `https://[1]/a`, `https://[:]/a`, `https://[1.2.3.4]/a` and `https://[1:2:3:4:5:6:7:8:9]/a` were queued even though `POST /bookmarks` always answers 400 for them.

Fix:
`asciiHost` now also requires an IPv6 literal to parse as a URI host (`URI("http://$host/")`, in the new private helper `parsesAsUriHost`). This copies the server's final check for the only part that check can reject: the server percent-encodes user info, path, query and fragment, and the port must be digits. Host names stay as before, because `URI` accepts names with `_` as a registry-based authority.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.*'`: Pass (with `--rerun`; the TW-3 test is in the results and passes, and the other IPv6 cases in "URLs at the server's limits are accepted" still pass)

### FLAKE-1 — `ShareReadLaterTest` fails now and then in `tearDown` because `Dispatchers.Main` is set while in use

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/capture/share/ShareViewModel.kt`

Problem:
The first TW-3 focused run had 1 failure out of 231 tests, in `ShareReadLaterTest` "a failing read-later store reports an error and keeps the sheet open": `IllegalStateException: Dispatchers.Main is used concurrently with setting it` from `Dispatchers.resetMain()` in `tearDown`. The parent worker found the cause. `ShareViewModel.load` always launched a coroutine that switches to `Dispatchers.IO` and then resumes on Main, also when the share has no files. With `UnconfinedTestDispatcher` as Main, that resume can still be running when `tearDown` resets Main.

Fix:
`load` now returns before the launch when `content.uris` is empty. The state is already complete then: `files` is empty, `loadingFiles` is `false` (set from `uris.isNotEmpty()`), and `text.ifBlank { defaultTitle(emptyList()) }` would only turn a blank title into `""`. `SharedContent.parse` trims the title, so a blank title already is `""`. User behavior is unchanged. Shares with files still load as before. The tests that share files (`ShareViewModelTest`) wait for loading with `awaitFiles()` or assert on `loadingFiles` on purpose. They did not fail in any run, so I left them unchanged, as instructed.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.*' --rerun`, 3 runs: Pass, Pass, Pass (231 tests, 0 failures, 0 errors)

## Not fixed (round 3)

None.

## Validation summary (round 3)

Focused tests:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.*'` after the TW-3 fix: Fail once (FLAKE-1), then Pass twice with `--rerun`
- The same command with `--rerun` after the FLAKE-1 fix, 3 runs: Pass all 3 (231 tests, 0 failures)

Full test suite:
- Not run by the fixer (left to the parent worker's `.taskcheck` run)

Lint/type-check/build:
- `ktlintCheck`: Pass

`.taskcheck`:
- Not run

Remaining unresolved findings (all rounds):
0

## Final status

All validated findings fixed and validation passed. Round 1 fixed CR-1 and CR-2 of the first review. Round 2 confirmed that commit c87b42d84 resolves CR-1 and CR-2 of the second review, with regression tests, and changed no code. Round 3 fixed TW-3 in `CaptureQueue.kt` and FLAKE-1 in `ShareViewModel.kt`.
