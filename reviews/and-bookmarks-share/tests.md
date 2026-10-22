# Test Report

Date: 2026-10-22

Task: and-bookmarks-share - Android: share URL to read later

Scope: `git diff 236a674ae0cd7d802b090b62351109d53d86e8d4..256fd8d16ddebe10b31b502af07d5f8a3bc79643 -- android/`. The parent gave the base and the task commits: c59e5e8b5 (implementation), 3d451d14c (round-1 fixes CR-1/CR-2), b055edf87 (tests and reports), c87b42d84 (URL length check after percent-encoding, unparseable 2xx answers count as sent), merged in 8358e093f, plus 256fd8d16 (fixer report only). Branch `main`, HEAD 256fd8d16ddebe10b31b502af07d5f8a3bc79643. The measured snapshot also includes this run's uncommitted change to one test file, listed below. This report replaces the earlier `tests.md`, which described revision 68b87bc99, a revision from before c87b42d84.

Requirements:
- tasks/and-bookmarks-share.md (acceptance criteria 1-2 and its three recorded assumptions)
- SPEC.md §8.4 (Android share offers "Inbox" or "Read later"), §9.3 (offline capture queue)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md
- reviews/and-bookmarks-share/fixes.md (round 2: CR-1 and CR-2 and the behavior they promise)
- The `CaptureQueue.readLater` / `isBookmarkUrl` KDoc contract: a URL the server always rejects is refused and nothing is queued. The server rules it mirrors are `server/.../bookmarks/domain/BookmarkUrls.kt` (`normalize`, including its final `URI(...)` parse at line 66) and `BookmarkService.normalizeUrl` (lines 270-275).

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/ReadLaterUploaderTest.kt` (4 tests added, 2 imports added; no existing test changed)

## Tests added or updated

Count: 4 (all in `ReadLaterUploaderTest`)
- `a character outside the basic plane counts with all four UTF-8 bytes` (passes): an emoji counts as 12 encoded characters, checked on both sides of 2048.
- `a percent sign that starts no escape counts as an encoded percent sign` (passes): a lone `%` counts as `%25`, a valid `%41` at the very end of the URL is kept, and an incomplete `%4` is encoded.
- `query, user info and port count in the encoded length too` (passes): the query (`?` kept), the user info (`é` encoded, an inner `@` written as `%40`) and the port all count, each checked on both sides of the limit.
- `an IPv6 literal the server cannot parse as a URI is refused and nothing is queued` (**fails**, TW-3)

These cover the parts of the c87b42d84 length computation that the committed regression test (`ReadLaterQueueTest > the length limit counts the URL as the server encodes it`) does not reach: supplementary code points, a `%` that starts no escape, query, user info, an inner `@`, and the port. Every boundary was computed by hand from the server's encoding rules, not by copying the production algorithm.

Status of the previous report's findings: TW-1 (control characters) and TW-2 (raw length only) are fixed in c87b42d84. Their tests `a URL with a control character is refused and nothing is queued` and `a URL whose encoded form is longer than the server accepts is refused` now pass.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: a shared URL shows the Inbox / Read later choice; no choice without a URL or with files | `ShareReadLaterTest` (`the sheet offers Inbox and Read later for a URL and switches its fields`, `the sheet shows no choice for a share without a URL`, `a browser share of a page carries its URL`, `text without an http URL offers no read-later choice`, ...); `ShareReadLaterEdgeTest` URL detection tests (subject only, subject without text, first of several, punctuation and brackets, mixed case, too long, file plus URL) | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*'` | Pass |
| AC1: Read later goes through the offline queue (stored first, scheduled, sent later; offline, 401, 429/5xx retry, 4xx rejection) | `ShareReadLaterActivityTest` (real activity, view model, queue and Room); `ReadLaterQueueTest`; `ReadLaterUploaderTest` (order, null title, capture failure stops the run, 401, 429/504, rejection, unexpected failure, status ordering, offline then online, failing scheduler, surrogate-safe shortening) | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.*'` | Pass (all of these tests) |
| AC2: choice routing covered by Robolectric / Compose UI tests | `ShareReadLaterTest`, `ShareReadLaterEdgeTest`, `ShareReadLaterActivityTest`, plus the existing `ShareViewModelTest`, `CaptureSheetTest`, `ShareActivityTest`, `ShareIntentParserTest`, `ShareManifestTest` | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*'` | Pass: 8 classes, 100 tests, 0 failures |
| CR-1 (round 2): a 2xx answer with an unparseable date-time or UUID counts as sent, so there is no duplicate | `ReadLaterQueueTest > a 2xx answer with a date-time or id the client cannot parse still counts as sent`, `... that cannot be read still counts as sent ...` | capture.* command above | Pass |
| CR-2 (round 2): the length limit applies to the percent-encoded, normalized URL | `ReadLaterQueueTest > the length limit counts the URL as the server encodes it`; `ReadLaterUploaderTest`: `a URL whose encoded form is longer ...`, `a character outside the basic plane ...`, `a percent sign that starts no escape ...`, `query, user info and port count ...` | capture.* command above | Pass |
| `readLater` contract: control characters are refused (former TW-1) | `a URL with a control character is refused and nothing is queued` | capture.* command above | Pass |
| `readLater` contract: authority rules (port, IDN, IPv6 shape) | `ReadLaterQueueTest` refused/accepted tests; `IPv6 hosts follow the server's port rules`; `a host label longer than DNS allows ...` | capture.* command above | Pass |
| `readLater` contract: an IPv6 literal the server's `URI(...)` parse rejects is refused | `an IPv6 literal the server cannot parse as a URI is refused and nothing is queued` | capture.* command above | **Fail** (TW-3) |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*'` (criterion 2), run 2026-10-22T11:22:17Z to 11:22:38Z, on the final production code. This run's new tests are in the `capture.queue` package, so this selection does not include them.

Result:

Pass: BUILD SUCCESSFUL, 8 classes, 100 tests, 0 failures, 0 errors (`ShareReadLaterTest` 10, `CaptureSheetTest` 14, `ShareManifestTest` 4, `ShareReadLaterEdgeTest` 17, `ShareReadLaterActivityTest` 5, `ShareActivityTest` 11, `ShareViewModelTest` 23, `ShareIntentParserTest` 16).

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.*'`, run 2026-10-22T11:23:58Z to 11:25:03Z, on the final snapshot.

Result:

**Fail**: "231 tests completed, 1 failed" (24 classes). The only failure is TW-3 at `ReadLaterUploaderTest.kt:385`. ktlint passes: `./gradlew ktlintCheck` and `./gradlew ktlintTestSourceSetCheck ktlintMainSourceSetCheck --rerun` both ended in BUILD SUCCESSFUL.

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (all Android debug unit tests, with coverage), run 2026-10-22T11:25:13Z to 11:27:23Z.

Result:

**Fail**: "1613 tests completed, 1 failed". The only failure is TW-3.

## .taskcheck

Result:

**Fail**: `sh scripts/check.sh`, run 2026-10-22T11:28:08Z to 11:31:47Z, exit 1.
- tfp gate tests: OK (20 tests).
- Server: ktlint and tests passed.
- Web: 166 files and 3453 tests passed, build OK.
- Android: "1613 tests completed, 1 failed". The only failing test is TW-3: `ReadLaterUploaderTest` is the only test-result XML file with a failure entry.

The timing-dependent `SessionCacheTest` failure noted in the previous report did not occur in this run.

## Bugs found

### An IPv6 literal that the server's URI parse rejects is accepted, queued and confirmed

Finding ID:

`TW-3`

Test:

`ch.sonensei.cockpit.capture.queue.ReadLaterUploaderTest > an IPv6 literal the server cannot parse as a URI is refused and nothing is queued`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueue.kt:178` (`IPV6` pattern) and `:229` (`asciiHost` accepts any host that matches `IPV6`). `isBookmarkUrl` (lines 194-206) has no equivalent of the server's final `URI(...)` check.

Requirement:

The `CaptureQueue.readLater` / `isBookmarkUrl` KDoc promises: "Throws IllegalArgumentException for a URL the server always rejects", and the authority must be one "the server accepts (its `BookmarkUrls.normalize` and `BookmarkService.normalizeUrl`)". This is the same contract as CR-2 and the former TW-1/TW-2. On the server, `BookmarkUrls.normalize` returns `normalized.takeIf(::parses)` (`BookmarkUrls.kt:66`, `parses` = `java.net.URI(url)` at lines 162-168). A null result makes `normalizeUrl` throw `InvalidRequestException`, so `POST /bookmarks` answers 400.

Expected behavior:

`isBookmarkUrl` is false for `https://[1]/a`, `https://[:]/a`, `https://[1.2.3.4]/a` and `https://[1:2:3:4:5:6:7:8:9]/a`, and `readLater` throws `IllegalArgumentException` with nothing queued. The test first checks that `java.net.URI` throws `URISyntaxException` for all four inputs, and that check passes. Their normalized form equals the input, because every character is allowed, so the server answers 400 for each of them.

Actual behavior:

`isBookmarkUrl("https://[1]/a")` returns true, because `[1]` matches `\[[0-9A-Fa-f:.]+]`. `ShareIntentParser` therefore offers Read later, and the share is queued and confirmed as "Saved to read later". The upload then gets 400, and the row stays `rejected = true` in `pending_bookmark`, counted in the pending count for good.

Severity:

Low. Malformed IPv6 literals are rare in shared links, and nothing the user wrote is lost. It has the same user-visible effect as CR-2: the app shows a false "saved" confirmation and the pending count never returns to 0.

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial-failed. The figures come from a fresh JaCoCo execution-data file written by the full-suite run on the final snapshot. That run failed on TW-3 alone, so Gradle skipped the report task. I then rendered the report from that fresh `.exec` with `./gradlew createDebugUnitTestCoverageReport -x testDebugUnitTest`. The failing test still executed `isBookmarkUrl` before its assertion failed. The numbers are therefore accurate for this snapshot, but they come from a failed test run and are diagnostic only. A fresh passing run is needed after TW-3 is fixed.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (the existing tooling has no changed-line support).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: task `and-bookmarks-share`. Base 236a674ae0cd7d802b090b62351109d53d86e8d4 and the task commits c59e5e8b5, 3d451d14c, b055edf87, c87b42d84 (merge 8358e093f) and 256fd8d16 were given by the parent. Measured HEAD is 256fd8d16ddebe10b31b502af07d5f8a3bc79643. The file scope is `git diff --name-only 236a674ae..HEAD -- android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 256fd8d16 plus the modified `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/ReadLaterUploaderTest.kt`, sha256 ecddd6b5d4932e792de36fefb7c09d0c038b8a834ddedd87a768f8ead440af93. Production code is unchanged against HEAD (`git diff --quiet 256fd8d16 -- android/app/src/main`). `reviews/and-bookmarks-share/review.md` was also modified in the working tree during this run, by another helper, not by this run. It has no effect on the tests.
- Exact included files; renamed/deleted/excluded files and reasons: the 8 Kotlin files in the table below are included. Excluded are `android/app/schemas/.../CaptureQueueDatabase/2.json` (generated Room schema, outside `src/main`) and `android/app/src/main/res/values/strings_share.xml` (resources, not instrumented). No files were renamed or deleted, and no scoped Kotlin file is missing from the report.
- Provider/version, command, working directory, and test selection: JaCoCo through AGP `enableUnitTestCoverage` and the Gradle `jacoco` plugin (version not pinned in the build files), on OpenJDK 21.0.12.1. The commands were `./gradlew createDebugUnitTestCoverageReport` (all debug unit tests), then `./gradlew createDebugUnitTestCoverageReport -x testDebugUnitTest` (report only, from the fresh exec), both run in `android/`.
- Run start/completion time and exit status: the test run went from 2026-10-22T11:25:13Z to 11:27:23Z and ended in BUILD FAILED (1613 tests, 1 failed: TW-3). The report rendering went from 11:27:30Z to 11:27:32Z and ended in BUILD SUCCESSFUL.
- Generated report paths and report hashes/run identifier: `android/app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec` (I deleted it before the run; recreated 13:27:22 +0200; sha256 3a54146b3f5ec7b80ff5a2aa3473e300e8282d1a5ace7ba0fbfa439bcec20ea2), and `android/app/build/reports/coverage/test/debug/report.xml` (mtime 13:27:32 +0200, sha256 16a9c29f4eed385750ecea6fd5b36b75129eaf9671836b9cacef474849028ae8, JaCoCo session `dell26-25152422`). The older report.xml from the previous test-writer run (mtime 12:23:20) was replaced, and its numbers are not used here.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The exec file was deleted before the run and rewritten by it. After the run and after `.taskcheck`, the test-file hash and the clean production diff were the same as before the run.
- Final run after latest test additions and production fixes: yes for the latest test additions and for c87b42d84. A run after a TW-3 fix is still needed.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" are JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueue.kt` | 1581/1663 (95.1%) | 144/152 (94.7%) | 36/36 (100%) | 164/167 (98.2%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueueDatabase.kt` | 325/325 (100%) | N/A | 36/36 (100%) | 41/41 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueueModule.kt` | 10/37 (27.0%) | N/A | 2/6 (33.3%) | 2/6 (33.3%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureUploader.kt` | 1027/1045 (98.3%) | 58/60 (96.7%) | 15/16 (93.8%) | 104/107 (97.2%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/share/CaptureSheet.kt` | 1867/1886 (99.0%) | 49/51 (96.1%) | 30/32 (93.8%) | 166/167 (99.4%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/share/ShareActivity.kt` | 320/320 (100%) | 6/6 (100%) | 7/7 (100%) | 29/29 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/share/ShareViewModel.kt` | 941/954 (98.6%) | 78/79 (98.7%) | 33/33 (100%) | 94/98 (95.9%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/share/SharedContent.kt` | 439/440 (99.8%) | 74/76 (97.4%) | 12/12 (100%) | 40/40 (100%) |
| **Aggregate (summed counters over the 8 measured files; none missing; schema JSON and strings XML excluded)** | 6510/6670 (97.6%) | 409/424 (96.5%) | 171/178 (96.1%) | 640/655 (97.7%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CaptureQueue.kt:194-263` (c87b42d84 length computation: user info, query, port, supplementary code points, lone `%`) | CR-2: the limit applies to the encoded URL | Covered now by the 3 passing tests added in this run; all lines of `isBookmarkUrl`/`encodedLength` are covered |
| `CaptureQueue.kt:178,229` (covered lines, missing rule) | `readLater` contract: refuse IPv6 literals the server's `URI` parse rejects | TW-3 (failing test) |
| `CaptureQueue.kt:252` (1 of 8 branches: `%` + hex + non-hex, e.g. `%4z`) | Escape detection in `encodedLength` | Not material: the same path as a lone `%` (`%%`), which is tested; left uncovered |
| `CaptureQueue.kt:265` (5 of 18 branches of `isHexDigit`) | Short-circuit range checks | Not material; the escape behavior is tested with digits and letters |
| `CaptureQueue.kt:128,160-161`, `CaptureUploader.kt:128-129`, `ShareViewModel.kt:146-147` | Rethrowing `CancellationException` | Defensive coroutine plumbing, not task behavior; left uncovered |
| `CaptureUploader.kt:127` (1 of 6 branches: code below 200) | Retrofit never hands a 1xx to the caller | Not reachable in practice; left uncovered |
| `CaptureUploader.kt:178` (capture path, 1 of 8 branches) | Capture (Inbox) sending from before this task | Outside this task's changed behavior |
| `SharedContent.kt:52` (2 of 4 branches: `text` null short-circuit, `url` emptied completely) | A URL in the subject only is tested (`ShareReadLaterEdgeTest`); a regex match that starts with `http://` never loses every character to trimming | Not reachable for the empty case; left uncovered |
| `CaptureSheet.kt:144` (`url.orEmpty()` null branch), `:288,295` | `readLater` implies `url != null`; 288/295 are Inbox paths from before this task | Not reachable / outside the task; left uncovered |
| `CaptureQueueModule.kt:17,37,40,43`, `CaptureUploader.kt:58` | Hilt providers and the `@Inject` constructor | DI glue: tests replace these bindings, and `ShareReadLaterActivityTest` uses the real Hilt graph for the queue and DAO |
| `CaptureQueue.kt:305-306,320` (`AttachmentStore`) | Attachment copy errors from before this task | Outside this task's changed behavior |

## Unable to test

- Real device behavior of the Android share sheet (system chooser, real browsers sharing). Robolectric covers the activity with real intents. A device or emulator run is not part of the criteria and was not available. No follow-up is required by the criteria.
- Upload against the real server: the uploader tests use MockWebServer with the generated client. I compared the TW-3 inputs with the server's rules by reading `BookmarkUrls.kt` and by running the same `java.net.URI` check in the test (same JDK class the server uses). A live server run is outside this task's criteria.
- `IDN.toASCII` on a real device uses Android's ICU-backed implementation. Robolectric uses the JVM's, which is the same as the server's. Any difference on a device cannot be tested here.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion.

Criterion 2's command (`capture.share.*`, 100 tests) passes on the final code. c87b42d84 fixed TW-1 and TW-2 from the previous report, and their tests pass. Three new tests confirm the encoded-length rules of CR-2 for user info, query, port, supplementary code points and lone `%`, and all three pass. One new test fails: TW-3 (Low). `isBookmarkUrl` accepts IPv6 literals such as `[1]` that the server's final `URI(...)` parse rejects. The `capture.*` command, the full Android suite and `.taskcheck` fail on this test alone until the fix lands. After the fix, run the test-writer and code-reviewer again, and do a fresh, passing coverage run.
