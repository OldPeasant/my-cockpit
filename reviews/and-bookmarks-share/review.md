# Code Review

Date: 2026-10-22

Task: and-bookmarks-share - Android: share URL to read later

Scope: `git diff f3c2102802292ecc2e011934b7c70f3128d02bb9..74e8c90a13986a3874ade4f06e201e406b6b16f8 -- android/` (committed changes only), the final code revision of the task. HEAD 4634ca954 adds only `reviews/and-bookmarks-share/tests.md` on top of 74e8c90a1, so the code at HEAD is the same as at 74e8c90a1. This round looks most closely at the round-3 fix commit 60b9ae665 (TW-3: IPv6 hosts checked with `java.net.URI`; FLAKE-1: no coroutine in `ShareViewModel.load` for shares without files) and the boundary test in 74e8c90a1. I also re-read the whole Android diff of the task. The working tree was clean when this review ran.

Requirements:
- tasks/and-bookmarks-share.md: acceptance criteria 1-2 and the three recorded assumptions (read later uses the capture queue with a new `pending_bookmark` table and auto-migration 1->2; `POST /bookmarks` has no client id, so duplicates are accepted; the choice is offered only for http(s) URLs in shares without files)
- SPEC.md §8.4 (sharing a URL on Android offers "Inbox" or "Read later"), §9.3 (offline capture queue)
- docs/ARCHITECTURE.md and the conventions in AGENTS.md
- Server contract: `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/BookmarkUrls.kt` (`normalize`, `asciiHost`, final `parses` via `URI(...)`), `BookmarkService.normalizeUrl` (raw and normalized length <= 2048), `api/BookmarkDtos.kt` `CreateBookmarkRequest` (title max 500, `readLater`)
- Earlier reports in reviews/and-bookmarks-share/ (round-2 review, fixes.md rounds 1-3, round-3 tests.md)

Git range:
- Branch: main
- HEAD: 4634ca954948235e71510b0fd29dd9756a0cf2d9 (code revision 74e8c90a13986a3874ade4f06e201e406b6b16f8)
- Base/start: f3c2102802292ecc2e011934b7c70f3128d02bb9
- Commits reviewed: 9 (086118d57, 26933806b, 94ef91ecc, ea074c326, 957ddfa4e, c25e71fc7, 60b9ae665, 74e8c90a1, 4634ca954; 957ddfa4e and 4634ca954 contain reports only)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Commands run and results

- `git log --oneline f3c210280..HEAD`, `git diff --stat f3c210280..HEAD`: 21 files. The code changes are in `capture/share` (CaptureSheet, ShareActivity, ShareViewModel, SharedContent, strings_share.xml) and `capture/queue` (CaptureQueue, CaptureQueueDatabase, CaptureQueueModule, CaptureUploader, Room schema `2.json`), plus tests and reports.
- `git diff --stat 74e8c90a1..HEAD`: only `reviews/and-bookmarks-share/tests.md` changed.
- `git show 60b9ae665 74e8c90a1 -- android`: read in full, see below.
- I did not run Gradle in this session; earlier rounds' review sessions were not allowed to run it. Instead I read the JUnit result files in `android/app/build/test-results/testDebugUnitTest/`. `ReadLaterUploaderTest` has 22 tests, 0 failures, 0 errors, written at 2026-10-22T11:47:17Z. That is after 74e8c90a1 was committed (13:44 +0200 = 11:44Z), and the file contains both IPv6 test cases. `ShareReadLaterTest` has 10 tests, 0 failures, written at 11:47:21Z. `grep -l '<failure\|<error'` over all `*capture*` result files (19 files) found nothing. These results match the runs recorded in the round-3 tests.md. The parent must still record its own criterion-2 run as evidence.

## Verification of the round-3 changes

- TW-3 (`CaptureQueue.kt:230-253`). `asciiHost` now accepts a bracketed host only if it matches the server's `IPV6` pattern and `URI("http://$host/")` parses. I checked the claim that the host is the only part the server's final `URI(normalized)` parse can reject:
  - The server percent-encodes user info, path, query and fragment into the character sets of the URI grammar. Valid `%XX` escapes stay, and every other `%` becomes `%25`.
  - The port is `\d{0,5}`, which `URI` parses without a range check.
  - For a host name (`HOST_NAME`, ASCII after IDN), `URI` falls back to a registry-based authority when server-based parsing fails (for example `_`, an all-digit last label, a leading `-`). Those characters, `:`, `@` and escapes are all registry-name characters, so the parse cannot throw.
  - A `[` is not a registry-name character, so a bracketed host gets no fallback. An invalid IPv6 literal is therefore the only case left, and the new check covers exactly that case.
  - Checking the host alone (no user info, no port) gives the same result as the server's check of the full URL, because the port and the encoded user info cannot make the parse fail.
  - minSdk is 29, so the device uses libcore's OpenJDK-based `java.net.URI`, the same parser as the server and the JVM tests.
- The boundary test (`ReadLaterUploaderTest.kt:399-426`) first checks its expectations against `java.net.URI` itself, so it does not just copy the production code. It then checks both sides: valid forms (unspecified, full, upper case, IPv4-mapped, IPv4-compatible, port + query + fragment) are queued in order, and malformed literals that pass the pattern are refused with `IllegalArgumentException` and nothing is queued. Together with the round-2 regression test (`[1]`, `[:]`, `[1.2.3.4]`, nine groups), the fix is tested both ways.
- FLAKE-1 (`ShareViewModel.kt:79-80`). `load` now returns before `viewModelScope.launch` when `content.uris` is empty. I confirmed that the old coroutine changed nothing in that case:
  - `files` stays empty.
  - `loadingFiles` was already `false`, because it is set to `uris.isNotEmpty()`.
  - `text.ifBlank { defaultTitle(emptyList()) }` would turn a blank title into `""`. `ShareIntentParser.parse` always trims the title (`subject.singleLine()` trims, and the text branch calls `.trim()`), so a blank title already is `""`.
  - `loaded` is still set before the return, so a recreated activity keeps the user's edits.
  - Shares with files still take the old path.
  - So users see no change, and the early return removes the Main-dispatcher resume that raced with `resetMain()` in tests.

## Review notes on the whole diff (no findings)

- Criterion 1 (shared URLs offer Inbox / Read later; read later goes through the offline queue). Met:
  - `ShareIntentParser.parse` sets `url` only for shares without files whose first http(s) URL passes `CaptureQueue.isBookmarkUrl`. Trailing punctuation is stripped, and a `)` the URL opened stays.
  - `CaptureSheet` shows `DestinationChoice` (a segmented button row, disabled while saving) only when `state.url != null`. Inbox is the default.
  - With Read later chosen, the notes field is hidden, the URL is shown as plain text, the title hint says the title is optional, and the save label and the toast change.
  - `ShareViewModel.saveReadLater` calls `CaptureQueue.readLater`. That method writes a `pending_bookmark` row under `NonCancellable`, then schedules the job and ignores a failed schedule, as for captures.
  - `CaptureUploader.sendAll` sends bookmarks after the captures, with the same handling for Sent, Rejected, Retry (stop the run), Failed (go on, retry later) and logged out.
  - The pending count and the last error in `observeStatus` include bookmarks.
  - `AutoMigration(1, 2)` only adds a table, and `2.json` is exported.
- Criterion 2. The focused Robolectric/Compose tests (`ShareReadLaterTest`, `ShareReadLaterEdgeTest`, `ShareReadLaterActivityTest`, and the adapted `ShareIntentParserTest`) cover:
  - parser detection;
  - view-model routing in both directions;
  - the sheet switching its fields;
  - locking the destination while saving and after saving;
  - retry after a failure;
  - the real activity with Hilt, Room and a configuration change.

  The result files show them passing (see Commands).
- Contract with the server:
  - The body `{url, title?, readLater: true}` matches `CreateBookmarkRequest`.
  - The title is trimmed. It is sent as `null` when it is blank or equal to the URL, and it is shortened to 500 UTF-16 units without splitting a surrogate pair.
  - `isBookmarkUrl` copies the server's `normalize` rules:
    - the same split into scheme, authority, path, query and fragment;
    - the same character sets per part, and the same encoded-length rules;
    - IDN-to-ASCII host conversion with the same `HOST_NAME`, `IPV6` and `PORT` patterns;
    - the raw-length check and the normalized-length check;
    - now also the final URI parse.
  - The client is stricter only for whitespace inside a URL, which it never extracts from shared text anyway.
- State and concurrency:
  - `save()` checks `canSave` on the current state and sets `saving` before it launches, so a double submit cannot queue the bookmark twice.
  - `onDestinationChange` is ignored while saving and after saving.
  - Every failure resets `saving` and shows `ShareError.FAILED`.
- A 2xx answer the app cannot decode (`SerializationException`, `DateTimeException`, `IllegalArgumentException` around the call only) counts as sent. The bookmark is on the server then, so sending it again would create a duplicate. The tests use the same generated `Serializer` converter as production `NetworkModule`.
- Architecture:
  - Read later reuses the existing queue and worker. There is no second offline mechanism and no new library.
  - The changes in `capture/queue`, outside the task's `touches`, are recorded as an assumption.
- Security and privacy:
  - Only `content:` URIs are still accepted.
  - The URL is shown as plain Compose text.
  - No URLs or titles are logged.
  - The test data is fictitious (`example.org`, documentation IP ranges).
- Existing tests were adapted to the new field and the new constructor parameter. No assertions were removed or weakened.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
