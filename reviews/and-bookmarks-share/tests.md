# Test Report

Date: 2026-10-22

Task: and-bookmarks-share - Android: share URL to read later

Scope: `git diff d7f0f1cc99024751eb2bb8efcf3b7360b6c28645..68b87bc997230ce22b2b1840f23a5a1b8a14fb16` (base = `git merge-base HEAD origin/main`; task commits 5ffa92b8c implementation, 68b87bc99 fixes CR-1/CR-2), plus the uncommitted test files listed below. Branch `main`, HEAD 68b87bc997230ce22b2b1840f23a5a1b8a14fb16.

Requirements:
- tasks/and-bookmarks-share.md (acceptance criteria 1-2 and its three recorded assumptions)
- SPEC.md §8.4 (read later), §9.3 (offline capture)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md
- reviews/and-bookmarks-share/review.md and fixes.md (CR-1, CR-2 and their intended behavior)
- Server contract: `server/.../bookmarks/domain/BookmarkUrls.kt` (`normalize`) and `BookmarkService.normalizeUrl`, the rules that `CaptureQueue.isBookmarkUrl` mirrors according to its KDoc

## Test files changed

All three are new and uncommitted. An earlier, interrupted test-writer run started them. This run reviewed them, kept them, and extended them.
- `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/ReadLaterUploaderTest.kt` (13 inherited tests, plus 4 added in this run)
- `android/app/src/test/java/ch/sonensei/cockpit/capture/share/ShareReadLaterActivityTest.kt` (5 inherited tests, unchanged)
- `android/app/src/test/java/ch/sonensei/cockpit/capture/share/ShareReadLaterEdgeTest.kt` (16 inherited tests, plus 1 added in this run)

I reviewed the inherited tests and kept all of them. They follow the existing patterns (`QueueHarness` and MockWebServer, Hilt and Robolectric `ActivityScenario` as in `ShareActivityTest`, Compose rules as in `ShareReadLaterTest`). They cover cases that the committed `ShareReadLaterTest` and `ReadLaterQueueTest` do not, so none is redundant. No existing test was changed.

## Tests added or updated

Count: 39 new tests in the three files (34 inherited and reviewed, 5 written in this run):
- `IPv6 hosts follow the server's port rules` (passes)
- `a host label longer than DNS allows is refused like the server's IDN check does` (passes)
- `a URL with a control character is refused and nothing is queued` (**fails**, TW-1)
- `a URL whose encoded form is longer than the server accepts is refused` (**fails**, TW-2)
- `a share with only a URL subject and no text is offered for read later` (passes)

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: a shared URL shows the Inbox / Read later choice; no choice without a URL or with files | `ShareReadLaterTest`: `the sheet offers Inbox and Read later for a URL and switches its fields`, `the sheet shows no choice for a share without a URL`, `a browser share of a page carries its URL`, `text without an http URL offers no read-later choice`; `ShareReadLaterEdgeTest`: URL detection tests (subject only, subject without text, first of several, punctuation and brackets, mixed case, too long, file plus URL) | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*'` | Pass |
| AC1: Read later goes through the offline queue (stored first, scheduled, sent later, survives offline, 401, 5xx, rejection) | `ShareReadLaterActivityTest` (real activity, view model, queue and Room: bookmark row, no capture, scheduler called, toast); `ReadLaterQueueTest` (committed); `ReadLaterUploaderTest`: order, null title, capture failure stops the run, 401, 429/504 retry, 4xx rejection, rejected row does not stop the next, unexpected failure, status error ordering, offline then online, failing scheduler, surrogate-safe shortening | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.*'` | Pass (all of these tests) |
| AC2: choice routing covered by Robolectric / Compose UI tests | `ShareReadLaterTest` (VM routing and Compose sheet), `ShareReadLaterEdgeTest` (routing while saving, after saving, after a failure, notes never sent, blank title, return to Inbox; sheet hint, error and disabled state), `ShareReadLaterActivityTest` (end-to-end Robolectric and Compose: Read later, Inbox after switching back, edited title, bare link, configuration change) | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*'` | Pass: 8 classes, 100 tests, 0 failures |
| CR-1: an unreadable 2xx answer counts as sent | `ReadLaterQueueTest`: `a 2xx answer that cannot be read still counts as sent, ...` (committed) | capture.* command above | Pass |
| CR-2 / `readLater` KDoc: a URL the server always rejects is refused, nothing is queued | `ReadLaterQueueTest` refused/accepted tests (committed); `ReadLaterUploaderTest`: `IPv6 hosts follow the server's port rules`, `a host label longer than DNS allows ...`, `URLs at the server's limits ...` | capture.* command above | Pass |
| Same: URL with an ISO control character | `a URL with a control character is refused and nothing is queued` | capture.* command above | **Fail** (TW-1) |
| Same: URL whose percent-encoded form is over 2048 characters | `a URL whose encoded form is longer than the server accepts is refused` | capture.* command above | **Fail** (TW-2) |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*'` (criterion 2), run on the final test snapshot. The last test suite finished at 2026-10-22T10:25:44Z.

Result:

Pass: BUILD SUCCESSFUL, 8 classes, 100 tests, 0 failures, 0 errors (`ShareReadLaterActivityTest` 5, `ShareReadLaterEdgeTest` 17, `ShareReadLaterTest` 10, `CaptureSheetTest` 14, `ShareViewModelTest` 23, `ShareIntentParserTest` 16, `ShareManifestTest` 4, `ShareActivityTest` 11).

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.*'`

Result:

- Before this run's additions (inherited files only): Pass, 24 classes, 220 tests, 0 failures, ktlint OK.
- On the final snapshot: **Fail**, "225 tests completed, 2 failed" (TW-1 at `ReadLaterUploaderTest.kt:326`, TW-2 at `ReadLaterUploaderTest.kt:339`). ktlint OK (`./gradlew ktlintTestSourceSetCheck --rerun`: BUILD SUCCESSFUL).

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (all Android unit tests with coverage)

Result:

- Before this run's additions: Pass, 155 classes, 1556 tests, 0 failures.
- On the final snapshot: **Fail**, "1561 tests completed, 2 failed" (only TW-1 and TW-2).

## .taskcheck

Result:

**Fail** (`sh scripts/check.sh`, final snapshot). tfp gate tests: OK (20). server: ktlint and tests passed. web: 166 files, 3453 tests passed. android: "1561 tests completed, 3 failed". The failures are TW-1, TW-2 and one more, `ch.sonensei.cockpit.auth.SessionCacheTest > the cache directory is emptied, including subfolders, but kept itself` (`expected:<[]> but was:<[capture_queue.db.lck]>`). This third failure is not caused by this task. It passed in the two other full runs above. The test and the `CockpitApp` start-up that opens the capture database in the background (`CaptureWidgetUpdater.start()`, from and-capture-entrypoints) both predate this task's base. Room's lock file in `cacheDir` sometimes appears after the test has cleared the folder. I left it to the parent as a pre-existing, timing-dependent failure. It is not reported as a task defect.

## Bugs found

### A URL with a control character is accepted, queued and confirmed, but the server always rejects it

Finding ID:

`TW-1`

Test:

`ch.sonensei.cockpit.capture.queue.ReadLaterUploaderTest > a URL with a control character is refused and nothing is queued`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueue.kt:60` (`HTTP_URL` uses `\s` / `\S`, and `isBookmarkUrl` has no control-character check at lines 70-74)

Requirement:

`CaptureQueue.readLater` KDoc and the CR-2 fix: a URL the server always rejects throws `IllegalArgumentException` and nothing is queued, so the sheet does not show Read later or "Saved to read later" for it. The server's `BookmarkUrls.normalize` (`BookmarkUrls.kt:34`) returns null for any `isISOControl()` character, so `POST /bookmarks` answers 400.

Expected behavior:

`isBookmarkUrl("https://example.org/a\u0001b")` (and the same with `\u007F` or `\u0085`) is false, and `readLater` throws `IllegalArgumentException`.

Actual behavior:

Java's `\s` covers only space, `\t\n\x0B\f\r`, so such a URL is accepted. `ShareIntentParser` offers Read later and the share is queued and confirmed. The upload then gets 400 and the row stays `rejected = true` in `pending_bookmark` for good, counted in the pending count. This is the same effect as CR-2.

Severity:

Low (rare input, nothing lost, but the user is told it was saved and the pending count never returns to 0)

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

### The URL length check ignores the server's limit on the encoded form

Finding ID:

`TW-2`

Test:

`ch.sonensei.cockpit.capture.queue.ReadLaterUploaderTest > a URL whose encoded form is longer than the server accepts is refused`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueue.kt:71` (`if (url.length > MAX_URL_LENGTH) return false` checks only the raw length)

Requirement:

As for TW-1. The server's `BookmarkService.normalizeUrl` (`BookmarkService.kt:267-273`) rejects a URL whose normalized, percent-encoded form is longer than 2048 characters. Its own test `BookmarkEdgeCaseTests > the URL length limit applies to the encoded form` checks this with 400 × "é", the same input as this test.

Expected behavior:

`isBookmarkUrl("https://example.org/" + "é".repeat(400))` (420 characters as typed, 2420 once encoded) is false.

Actual behavior:

It returns true. The share is queued and confirmed, then rejected by the server (400) and kept forever as a rejected row.

Severity:

Low (needs long non-ASCII or other encodable URLs, for example unencoded non-Latin article URLs; nothing lost, same effect as CR-2)

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial. The figures are a verified, fresh, full-suite run on HEAD 68b87bc99 with the three inherited test files. The run on the final snapshot (with this run's 5 tests) failed because of TW-1 and TW-2, and the JaCoCo report task did not run, so no report exists for the final snapshot. Re-run after the fix.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-bookmarks-share`. Base d7f0f1cc99024751eb2bb8efcf3b7360b6c28645 (`git merge-base HEAD origin/main`, given by the parent). HEAD 68b87bc997230ce22b2b1840f23a5a1b8a14fb16. Task commits 5ffa92b8c and 68b87bc99 (given by the parent, matching `git log d7f0f1cc9..HEAD`). Scope: `git diff --name-only d7f0f1cc9..HEAD -- android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): measured snapshot = HEAD 68b87bc99 + untracked `ReadLaterUploaderTest.kt` sha256 f162035a…3318, `ShareReadLaterActivityTest.kt` acc16ef1…b239, `ShareReadLaterEdgeTest.kt` 5ccd8443…49d1. Final (unmeasured) snapshot: `ReadLaterUploaderTest.kt` c21ecdf3…2fb1, `ShareReadLaterActivityTest.kt` acc16ef1…b239 (unchanged), `ShareReadLaterEdgeTest.kt` f63f507e…e4b. Production code was unchanged in both.
- Exact included files; renamed/deleted/excluded files and reasons: included are the 8 Kotlin files in the table. Excluded: `android/app/schemas/.../CaptureQueueDatabase/2.json` (generated Room schema), `android/app/src/main/res/values/strings_share.xml` (resources, not instrumented). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo through AGP `enableUnitTestCoverage` (version not pinned in the build files). `./gradlew createDebugUnitTestCoverageReport` in `android/`, all debug unit tests.
- Run start/completion time and exit status: measured run 2026-10-22T10:21:37Z to 10:23:20Z, BUILD SUCCESSFUL (1556 tests, 0 failed). Final-snapshot run 10:26:18Z to 10:27:53Z, BUILD FAILED (1561 tests, 2 failed), no report written.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, mtime 2026-10-22 12:23:20 +0200, sha256 d7d13b2a02d8bf74771bee01e16b359293a7b1ea1fbccbec5bf3f8b15b65fe4c, JaCoCo session `dell26-a0239528`. After the failed final run the report still had the same mtime and hash.
- Evidence outputs are fresh and inputs stayed unchanged: yes for the measured snapshot. The report mtime matches the run's completion, and the test inputs were hashed before the run and unchanged until this run's edits afterwards.
- Final run after latest test additions and production fixes: no. The final-snapshot coverage run fails on TW-1 and TW-2, and production code may not change here. Re-run after the fixer resolves them.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" are JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueue.kt` | 1279/1360 (94.0%) | 93/100 (93.0%) | 33/33 (100%) | 139/144 (96.5%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueueDatabase.kt` | 325/325 (100%) | N/A | 36/36 (100%) | 41/41 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueueModule.kt` | 10/37 (27.0%) | N/A | 2/6 (33.3%) | 2/6 (33.3%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureUploader.kt` | 1021/1039 (98.3%) | 58/60 (96.7%) | 15/16 (93.8%) | 100/103 (97.1%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/share/CaptureSheet.kt` | 1867/1886 (99.0%) | 49/51 (96.1%) | 30/32 (93.8%) | 166/167 (99.4%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/share/ShareActivity.kt` | 320/320 (100%) | 6/6 (100%) | 7/7 (100%) | 29/29 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/share/ShareViewModel.kt` | 941/954 (98.6%) | 78/79 (98.7%) | 33/33 (100%) | 94/98 (95.9%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/share/SharedContent.kt` | 439/440 (99.8%) | 74/76 (97.4%) | 12/12 (100%) | 40/40 (100%) |
| **Aggregate (summed counters, 8 measured files; none missing; schema JSON and strings XML excluded)** | 6202/6361 (97.5%) | 358/372 (96.2%) | 168/175 (96.0%) | 611/628 (97.3%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CaptureQueue.kt:199,201` (IPv6 branches, 5 of 12 missed) | CR-2: IPv6 hosts with no port, an empty port, or a bad port | Added `IPv6 hosts follow the server's port rules` (passes; not yet in a coverage report, see Status) |
| `CaptureQueue.kt:212-213` (`IDN.toASCII` throws) | CR-2: a host the server's IDN check rejects | Added `a host label longer than DNS allows ...` (passes; not yet in a report) |
| `CaptureQueue.kt:60,71` (covered lines, missing rule) | `readLater` contract: refuse URLs the server always rejects | TW-1, TW-2 (failing tests) |
| `SharedContent.kt:52` (`text` null, 2 of 4 branches missed) | AC1: a URL only in the subject, with no text | Added `a share with only a URL subject and no text ...` (passes; not yet in a report) |
| `CaptureQueue.kt:160-161`, `CaptureUploader.kt:118-119`, `ShareViewModel.kt:146-147` | Re-throwing `CancellationException` | Defensive coroutine plumbing; not a task behavior, left uncovered |
| `CaptureSheet.kt:144` (`url.orEmpty()` null branch) | none: `readLater` implies `url != null` | Not reachable, left uncovered |
| `CaptureQueueModule.kt:17,37,40,43`, `CaptureUploader.kt:57` | Hilt providers and `@Inject` constructor | DI glue; tests replace these bindings, and `ShareReadLaterActivityTest` covers the real Hilt graph for the queue and DAO |
| `CaptureQueue.kt:129,256`, `CaptureSheet.kt:288,295`, `ShareViewModel.kt:120-121,123` | Capture (Inbox) paths from before this task | Outside this task's changed behavior |

## Unable to test

- Real device behavior of the Android share sheet (system chooser, actual browsers sharing). Robolectric covers the activity with real intents. A device or emulator run is not part of the criteria and was not available. No follow-up is required by the criteria.
- Upload against the real server: the uploader tests use MockWebServer with the generated client. A live server run is outside this task's criteria.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion.

Criterion 2's command passes (100 tests). TW-1 and TW-2 (Low) make `ReadLaterUploaderTest`, the capture.* command and `.taskcheck` fail until `CaptureQueue.isBookmarkUrl` also rejects ISO control characters and checks the length of the percent-encoded form, as the server does. After the fix, the test-writer and code-reviewer must run again and a fresh coverage run is needed. The `SessionCacheTest` failure seen once in `.taskcheck` is pre-existing and timing-dependent, not caused by this task.
