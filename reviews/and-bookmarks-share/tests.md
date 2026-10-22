# Test Report

Date: 2026-10-22

Task: and-bookmarks-share - Android: share URL to read later

Scope: round 3 (final code). `git diff f3c2102802292ecc2e011934b7c70f3128d02bb9..74e8c90a13986a3874ade4f06e201e406b6b16f8 -- android/`, on branch `main`, HEAD 74e8c90a13986a3874ade4f06e201e406b6b16f8. The parent gave the base and the task commits (`git log f3c210280..HEAD`): 086118d57 (implementation), 26933806b (round-1 fixes CR-1/CR-2), 94ef91ecc (tests and reports), ea074c326 (URL length after server normalization, unparseable 2xx answers count as sent), 957ddfa4e (fixer report), c25e71fc7 (round-2 reports, TW-3 regression test), 60b9ae665 (TW-3 and FLAKE-1 fixes), 74e8c90a1 (IPv6 boundary test). The working tree was clean before and after every run, and this round added no tests. The base differs from the round-2 report (236a674ae) because the task was rebased onto a newer main. The task's file list is the same.

Requirements:
- tasks/and-bookmarks-share.md (acceptance criteria 1-2 and its three recorded assumptions)
- SPEC.md §8.4 (Android share offers "Inbox" or "Read later"), §9.3 (offline capture queue)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md
- reviews/and-bookmarks-share/fixes.md, round 3 (TW-3 and FLAKE-1)
- The `CaptureQueue.readLater` / `isBookmarkUrl` KDoc contract: a URL the server always rejects is refused and nothing is queued. The server rule it mirrors is `BookmarkUrls.normalize` and its final `URI(...)` parse.

## Test files changed

None in this round. The tests for the round-3 fixes were committed by the parent: the TW-3 regression test in c25e71fc7 and the IPv6 boundary test in 74e8c90a1, both in `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/ReadLaterUploaderTest.kt`.

## Tests added or updated

Count: 0

Status of the round-3 fixes:
- TW-3 (`CaptureQueue.asciiHost` / `parsesAsUriHost`) is covered. `an IPv6 literal the server cannot parse as a URI is refused and nothing is queued` failed in round 2 on the code before the fix, and it passes now. It covers `[1]`, `[:]`, `[1.2.3.4]` and `[1:2:3:4:5:6:7:8:9]`. The boundary test `every IPv6 form the server's URI parse accepts is still queued, other malformed literals are refused` checks both sides: valid RFC 4291 forms are still queued (unspecified, full, upper case, IPv4-mapped, IPv4-compatible, with a port, query and fragment), and four more malformed literals are refused. Both tests first check `java.net.URI` itself, so the expected values do not copy the production code. JaCoCo shows that every line of `parsesAsUriHost` runs, including the `URISyntaxException` catch, and so do both branches of the new `IPV6.matches(it) && parsesAsUriHost(it)` condition. Earlier tests (`IPv6 hosts follow the server's port rules`, the ReadLaterQueueTest accepted/refused lists) still pass. No gap remains.
- FLAKE-1 (`ShareViewModel.load` returns before the launch when the share has no files) is a timing fix, and users see no change. Existing tests cover the behavior: `ShareViewModelTest > a share without files is ready at once` (no loading, ready to save, read synchronously), `a second load keeps the user's edits`, and every no-file share test in `ShareReadLaterTest`, `ShareReadLaterEdgeTest` and `ShareReadLaterActivityTest`. Shares with files still go through the coroutine (`load fills the sheet ...`, `a title typed while the file names load is kept ...`). A test that would fail without the fix would need to observe that no coroutine was launched. The old code also gave a complete state at once, so such a test could only observe internal scheduling, and I added none. ShareReadLaterTest ran without failures in each of this round's 4 runs (criterion-2 run, capture.* run, coverage run, `.taskcheck`). That does not prove the flake is gone, but it is consistent with the fixer's 3 clean runs.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: a shared URL shows the Inbox / Read later choice; no choice without a URL or with files | `ShareReadLaterTest` (`the sheet offers Inbox and Read later for a URL and switches its fields`, `the sheet shows no choice for a share without a URL`, `a browser share of a page carries its URL`, `text without an http URL offers no read-later choice`, ...); `ShareReadLaterEdgeTest` URL detection tests | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*' --rerun` | Pass |
| AC1: Read later goes through the offline queue (stored first, scheduled, sent later; offline, 401, 429/5xx retry, 4xx rejection) | `ShareReadLaterActivityTest` (real activity, view model, queue and Room); `ReadLaterQueueTest`; `ReadLaterUploaderTest` | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.*' --rerun` | Pass |
| AC2: choice routing covered by Robolectric / Compose UI tests | `ShareReadLaterTest` (10), `ShareReadLaterEdgeTest` (17), `ShareReadLaterActivityTest` (5), `ShareViewModelTest` (23), `CaptureSheetTest` (14), `ShareActivityTest` (11), `ShareIntentParserTest` (16), `ShareManifestTest` (4) | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*' --rerun` | Pass: 8 classes, 100 tests, 0 failures, 0 errors |
| CR-1 (round 2): a 2xx answer that cannot be read counts as sent, so there is no duplicate | `ReadLaterQueueTest > a 2xx answer with a date-time or id the client cannot parse still counts as sent`, `... that cannot be read still counts as sent ...` | capture.* command above | Pass |
| CR-2 (round 2): the length limit applies to the percent-encoded, normalized URL | `ReadLaterQueueTest > the length limit counts the URL as the server encodes it`; `ReadLaterUploaderTest` encoded-length tests | capture.* command above | Pass |
| TW-3: an IPv6 literal the server's `URI(...)` parse rejects is refused; valid forms are still queued | `ReadLaterUploaderTest > an IPv6 literal the server cannot parse as a URI is refused and nothing is queued`, `every IPv6 form the server's URI parse accepts is still queued, other malformed literals are refused`, `IPv6 hosts follow the server's port rules` | capture.* command above | Pass |
| FLAKE-1: a share without files is ready at once, with no change for users | `ShareViewModelTest > a share without files is ready at once`, `a second load keeps the user's edits`; the no-file share tests in `ShareReadLater*Test` | share.* command above | Pass (no failure in 4 runs; flakiness itself cannot be proven absent) |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*' --rerun` (criterion 2), run 2026-10-22T11:44:47Z to 11:45:25Z.

Result:

Pass: BUILD SUCCESSFUL. The result XMLs show 8 classes, 100 tests, 0 failures, 0 errors, 0 skipped.

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.*' --rerun`, run 2026-10-22T11:45:37Z to 11:46:08Z.

Result:

Pass: BUILD SUCCESSFUL, 24 classes, 232 tests, 0 failures, 0 errors. `ReadLaterUploaderTest` has 22 tests and 0 failures, and both IPv6 tests are in its results. ktlintCheck passes. It was up to date, because no Kotlin source changed since its last successful run.

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (all Android debug unit tests, with coverage), run 2026-10-22T11:46:37Z to 11:48:39Z.

Result:

Pass: BUILD SUCCESSFUL, 166 classes, 1680 tests, 0 failures, 0 errors, 0 skipped.

## .taskcheck

Result:

Pass. `sh scripts/check.sh`, run 2026-10-22T11:49:12Z to 11:51:26Z (2 min 14 s), exit 0, "all checks passed". It ran the tfp gate tests (20 OK), the server ktlint and tests, the web suite (166 files, 3453 tests) and build, and the Android ktlint, unit tests and lint.

## Bugs found

None. TW-3 from round 2 is fixed, and its regression test passes.

## Coverage

Status: verified. The numbers come from a fresh, passing full-suite run on HEAD 74e8c90a1 with a clean working tree.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (the existing tooling has no changed-line support). I checked the round-3 changed lines by hand in the JaCoCo XML: `CaptureQueue.kt` `asciiHost`/`parsesAsUriHost` (lines 231-254) and the `ShareViewModel.kt` early return (line 80) have no missed lines or branches.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: task `and-bookmarks-share`. The parent gave base f3c2102802292ecc2e011934b7c70f3128d02bb9 and the task commits `git log f3c210280..HEAD` (8 commits, listed in Scope). Measured HEAD is 74e8c90a13986a3874ade4f06e201e406b6b16f8. The file scope is `git diff --name-only f3c210280..HEAD -- android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 74e8c90a1. `git status --porcelain` was empty before the runs and after `.taskcheck`, so there are no uncommitted inputs.
- Exact included files; renamed/deleted/excluded files and reasons: the 8 Kotlin files in the table below are included. Excluded are `android/app/src/main/res/values/strings_share.xml` (resources, not instrumented) and `android/app/schemas/.../CaptureQueueDatabase/2.json` (generated Room schema, outside `src/main`). No files were renamed or deleted, and no scoped Kotlin file is missing from the report.
- Provider/version, command, working directory, and test selection: JaCoCo through AGP `enableUnitTestCoverage` and the Gradle `jacoco` plugin (version not pinned in the build files), on OpenJDK 21.0.12.1. The command was `./gradlew createDebugUnitTestCoverageReport` in `android/`, with all debug unit tests.
- Run start/completion time and exit status: 2026-10-22T11:46:37Z to 11:48:39Z, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec` (rewritten 13:48:36 +0200, during the run; the earlier file was from the capture.* run at 13:46:08; sha256 ae12f78a56cccb8e3415c7927078ea3fa0587a96d814c9ba88343598633e1a31) and `android/app/build/reports/coverage/test/debug/report.xml` (rewritten 13:48:38 +0200; the earlier file was from 13:27:32, the round-2 run; sha256 f2873830c9d7b445f766d34cfa5d30da425cfd9f7c1ec82f30e4833922e34709; JaCoCo session `dell26-d9ed9716`).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Both files were rewritten by this run, and the session differs from round 2 (`dell26-25152422`). HEAD and the clean working tree were the same before and after.
- Final run after latest test additions and production fixes: yes. It ran on HEAD 74e8c90a1, which contains the TW-3/FLAKE-1 fixes and the latest test commit.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" are JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueue.kt` | 1604/1686 (95.1%) | 148/156 (94.9%) | 37/37 (100%) | 170/173 (98.3%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueueDatabase.kt` | 325/325 (100%) | N/A | 36/36 (100%) | 41/41 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureQueueModule.kt` | 10/37 (27.0%) | N/A | 2/6 (33.3%) | 2/6 (33.3%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/CaptureUploader.kt` | 1027/1045 (98.3%) | 58/60 (96.7%) | 15/16 (93.8%) | 104/107 (97.2%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/share/CaptureSheet.kt` | 1867/1886 (99.0%) | 49/51 (96.1%) | 30/32 (93.8%) | 166/167 (99.4%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/share/ShareActivity.kt` | 320/320 (100%) | 6/6 (100%) | 7/7 (100%) | 29/29 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/share/ShareViewModel.kt` | 944/959 (98.4%) | 79/81 (97.5%) | 33/33 (100%) | 94/99 (94.9%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/share/SharedContent.kt` | 439/440 (99.8%) | 74/76 (97.4%) | 12/12 (100%) | 40/40 (100%) |
| **Aggregate (summed counters over the 8 measured files; none missing; strings XML and schema JSON excluded)** | 6536/6698 (97.6%) | 414/430 (96.3%) | 172/179 (96.1%) | 646/662 (97.6%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CaptureQueue.kt:231-254` (TW-3 change) | Refuse IPv6 literals the server's `URI` parse rejects | Fully covered by the two IPv6 tests; no gap |
| `ShareViewModel.kt:158-159` (`defaultTitle` branch `0 -> ""`) | Since FLAKE-1, `defaultTitle` is only called after files load, so it is never called with an empty list | Dead branch, unreachable now; no test possible. Not a defect. The parent or fixer may drop the branch, or keep it for safety |
| `ShareViewModel.kt:125` (1 of 2 branches: blank text when `capture` throws `IllegalArgumentException`) | `canSave` stops a blank Inbox text before `capture` is called | Not reachable through the view model; Inbox path from before this task |
| `CaptureQueue.kt:131,162-163`, `CaptureUploader.kt:128-129`, `ShareViewModel.kt:122-123,148-149` | Rethrowing `CancellationException` | Defensive coroutine plumbing, not task behavior; left uncovered |
| `CaptureQueue.kt:130` (1 of 4 branches), `:320` (1 of 2) | Attachment cleanup / `AttachmentStore` copy errors from before this task | Outside this task's changed behavior |
| `CaptureQueue.kt:267` (1 of 8 branches: `%` + hex + non-hex, e.g. `%4z`) | Escape detection in the encoded-length count | Not material: it takes the same path as a lone `%`, which is tested |
| `CaptureQueue.kt:280` (5 of 18 branches of `isHexDigit`) | Short-circuit range checks | Not material; escapes are tested with digits and letters |
| `CaptureUploader.kt:127` (1 of 6 branches: status code below 200) | Retrofit never passes a 1xx answer to the caller | Not reachable in practice |
| `CaptureUploader.kt:178` (1 of 8 branches, capture path) | Capture (Inbox) sending from before this task | Outside this task's changed behavior |
| `SharedContent.kt:52` (2 of 4 branches) | `text` null short-circuit (a URL in the subject only is tested); a match losing every character to trimming | Not reachable for the empty case |
| `CaptureSheet.kt:144` (`url.orEmpty()` null branch), `:288,295` | `readLater` implies `url != null`; 288/295 are Inbox error messages from before this task | Not reachable / outside the task |
| `CaptureQueueModule.kt:17,37,40,43`, `CaptureUploader.kt:58` | Hilt providers and the `@Inject` constructor | DI glue: tests replace these bindings, and `ShareReadLaterActivityTest` uses the real Hilt graph for the queue and DAO |

## Unable to test

- FLAKE-1: no deterministic test can prove that the `Dispatchers.Main` race in `tearDown` is gone, because the fix only removes a coroutine with no observable result. The evidence is the clean repeated runs (this round: 4 runs that include the affected class). No follow-up is required.
- Real device behavior of the Android share sheet (system chooser, real browsers sharing). Robolectric covers the activity with real intents. A device run is not part of the criteria.
- Upload against the real server. The uploader tests use MockWebServer with the generated client. The IPv6 expectations are checked against the JDK's `java.net.URI`, the same class the server uses. A live server run is outside this task's criteria.
- `IDN.toASCII` on a real device uses Android's ICU-backed implementation. Robolectric uses the JVM's, the same as the server's. Any difference on a device cannot be tested here.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.

Criterion 2's command (`capture.share.*`, 100 tests), the `capture.*` selection (232 tests), ktlintCheck, the full Android suite with coverage (1680 tests) and `.taskcheck` all pass on HEAD 74e8c90a1. The TW-3 fix is covered by its regression test and by the IPv6 boundary test on both sides, and all of its new code is covered. The existing no-file share tests cover the FLAKE-1 behavior. No new tests were needed. One observation, not a defect: after FLAKE-1, the `0 -> ""` branch of `ShareViewModel.defaultTitle` can no longer run.
