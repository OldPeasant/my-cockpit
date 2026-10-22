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

## Final status

All validated findings fixed and validation passed.
