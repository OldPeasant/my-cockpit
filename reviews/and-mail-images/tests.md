# Test Report

Date: 2026-11-08

Task: and-mail-images: Android shows mail images (inline and on request)

Scope: `git diff 76a46b9ff9de8a22a4210eadb3abb00e76f80af9..a441e181d0cf3fa5881e32bca2acf8633db0117d -- android` (task commits 6d93a6246, b2627d776, a441e181d; 6d93a6246 and b2627d776 are rebased copies of 982f7e8b0 and 6ee3de4a5 with identical android changes; a441e181d adds the tests below and the explicit 200 status for image answers). Working tree clean at a441e181d when this report was finalized.

Requirements:
- `tasks/and-mail-images.md` acceptance criteria and recorded assumptions
- `AGENTS.md`, `docs/ARCHITECTURE.md` ("Coverage", Android conventions)
- Parent worker's description of the change

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailImagesTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadImagesViewModelTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadImagesGermanTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailWebViewTest.kt` (4 tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadRepositoryTest.kt` (3 tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadScreenTest.kt` (8 tests added; the shared `show` helper also records the two banner callbacks)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadTestSupport.kt` (helper `pageRequest` for WebResourceRequest)

No existing test was weakened, removed or disabled. The test-writer changed no production file; the production fix for TW-1 was made by the parent worker.

## Tests added or updated

Count: 48 new tests (MailImagesTest 12, MailReadImagesViewModelTest 17, MailReadImagesGermanTest 4, MailWebViewTest 4, MailReadRepositoryTest 3, MailReadScreenTest 8).

## Requirement coverage

The test-writer cannot run Gradle in this environment; the parent worker ran the commands and reported the results below.

Command for the test rows (run by the parent at the final code): `cd android && ./gradlew ktlintFormat ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'` -> pass, 110 tests.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: only the configured server's attachment download and image-proxy URLs are fetched, through the app's OkHttp client, and passed to the page with status 200 | MailImagesTest: `the server's attachment downloads and image proxy are server images`, `a server image is fetched from the configured server and passed to the page`, `an inline attachment is fetched from its download path`, `the web view client answers server images through the fetcher` | as above | Pass |
| AC1: every other request stays blocked (other host, http, port, user info, other paths, dot segments, encoded slashes, non-GET, non-image or failed answers, unreachable server) | MailImagesTest: `other hosts, schemes, ports, user info and paths are not server images`, `an answer that is not an image is not passed to the page`, `a failed image answer is refused, also when it claims to be an image`, `an unreachable or unconfigured server gives a refused image, not a crash`, `only GET requests are fetched`, `a request to another host or path never reaches the server`, `a server url with a malformed port is refused, not thrown`; MailWebViewTest: `without an image fetcher every request is blocked, also the server's images` | as above | Pass |
| AC1: WebView headers (cookies, referrer) are not forwarded | MailImagesTest: `a server image is fetched from the configured server and passed to the page` | as above | Pass |
| AC1: JavaScript stays off; links to the stand-in origin are not opened | MailWebViewTest: `the settings lock the untrusted html down` (existing), `a tapped link to the server's stand-in origin is not opened` | as above | Pass |
| AC2: banner with "Load images" (reloads with remoteImages=PROXY) and "Always for this sender" (adds to mailImageAllowedSenders) | MailReadScreenTest: `blocked remote images show a banner with load images and always for this sender`, `one blocked image is counted in the singular`, `without a sender address the banner offers only load images`, `a mail without blocked images has no banner`, `the banner buttons wait while an action runs`, `failed images are reported`, `an unsaved sender is reported`, `the route's load images shows the mail with proxied images and the banner goes away`; MailReadRepositoryTest: `remote images are asked for through the proxy only when wanted`, `a failed proxied load is reported like any other load`; MailReadImagesViewModelTest: `load images shows the mail again with proxied images`, `a failed load images keeps the mail and says so`, `load images runs once while busy and not before the mail is shown`, `images that arrive after the mail was closed or replaced are dropped`, `always for this sender adds the sender to the allowlist and loads the images`, `a sender already allowed, in any case, is not saved again`, `a sender that could not be saved still gets this mail's images, with a notice`, `settings not loaded yet start the allowlist with this sender`, `when the images fail after always for this sender, the image failure is shown`, `always for this sender does nothing for a mail without a sender address` | as above | Pass |
| AC3: policy allow or an allowed sender loads images through the proxy without asking | MailReadImagesViewModelTest: `without settings, or with the default policy, remote images stay blocked`, `policy allow loads the mail with proxied images right away`, `policy allow that fails to load shows the load error`, `an allowed sender, in any case, gets its images through the proxy after the first load`, `an allowed sender whose proxied load fails still sees the mail, with its images blocked`, `an allowed sender's mail without blocked images is loaded only once`, `a mail without a sender address is not matched by the allowlist`; MailReadRepositoryTest: `the sender address is the first non-blank From address, trimmed` | as above | Pass |
| AC4: CSP allows images only from the server origin and data:; a request to another host is blocked | MailWebViewTest: `the page's directives allow images only from the server origin and data, and nothing else`, `a request to another host is blocked, also with an image fetcher`, `the page around the mail allows only inline styles and images from the server or data` (existing); MailImagesTest: `the page origin is the server's stand-in, without a trailing slash` | as above | Pass |
| AC5: manual verification on an emulator/device | none | none | Not testable by this helper (needs an emulator and a running server; parent's responsibility) |
| AC6: new strings in English and German | MailReadScreenTest banner/notice tests (English); MailReadImagesGermanTest: `the banner and its buttons are German`, `one blocked image is counted in the German singular`, `failed images are reported in German`, `an unsaved sender is reported in German` | as above | Pass |
| AC7: unit/Compose tests cover the new behavior; ktlintCheck, testDebugUnitTest, lintDebug and scripts/check.sh pass | all of the above | `cd android && ./gradlew createDebugUnitTestCoverageReport` (full unit suite) -> pass; `sh scripts/check.sh` | Pass for the full Android unit suite, ktlintCheck and lintDebug; `scripts/check.sh` not run by the test-writer (parent runs it) |

## Focused test execution

Command:

`cd android && ./gradlew ktlintFormat ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'` (run by the parent worker)

Result:

First run: Fail, 3 tests in MailImagesTest (`a server image is fetched from the configured server and passed to the page`, `only GET requests are fetched`, `the web view client answers server images through the fetcher`) with "expected 200 but was 0" (TW-1 below). After the parent's production fix: Pass, 110 tests.

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (runs the whole debug unit test suite; run by the parent worker)

Result:

Pass

## .taskcheck

Result:

Not run by the test-writer; the parent worker runs `sh scripts/check.sh` before `done`.

## Bugs found

### Image answers were handed to the WebView without an HTTP status

Finding ID:

`TW-1`

Test:

`MailImagesTest`: `a server image is fetched from the configured server and passed to the page`, `only GET requests are fetched`, `the web view client answers server images through the fetcher`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailImages.kt:63` (`MailImageFetcher.fetch`)

Requirement:

AC1: inline `cid:` and proxied images of the configured server render through `shouldInterceptRequest`.

Expected behavior:

A fetched server image is answered with status 200, its image MIME type and the body stream.

Actual behavior:

The 3-argument `WebResourceResponse(mime, encoding, stream)` constructor left `statusCode` at 0.

Severity:

Low (Chromium probably treats an answer without a status as OK, but the answer was not an explicit success; the tests require 200).

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.` Status: fixed by the parent in a441e181d; the image answer is now `WebResourceResponse(mime, null, 200, "OK", emptyMap(), stream)`. The test-writer reviewed the fix: it is correct and minimal (the 6-argument constructor needs a non-empty reason phrase, which "OK" provides; the blocked answer already used it). The three tests above assert the 200 status, failed before the fix and pass after it, so no extra test was added.

## Coverage

Status: verified (from the parent's run at the final code; per-file counters and line details read by the test-writer from the JaCoCo XML).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-images; base 76a46b9ff9de8a22a4210eadb3abb00e76f80af9; HEAD a441e181d0cf3fa5881e32bca2acf8633db0117d; task commits 6d93a6246, b2627d776, a441e181d (from the parent and `git log 76a46b9ff..HEAD`).
- Source/test snapshot identifier (including uncommitted inputs): commit a441e181d, working tree clean; sha256 (first 16 hex digits) MailImages.kt 3df0a7bdec53e936, MailImagesTest.kt 72ee8ce1e88acc22 (differs from the pre-run snapshot f8d640b1a801fe2e only by ktlintFormat line wrapping of one MockResponse builder), MailReadImagesViewModelTest.kt 9cda7b571f03cf42, MailReadScreenTest.kt 4f80aa5658fce66e, MailWebViewTest.kt 380cb86f0ac59bde.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailImages.kt`, `.../mail/read/MailBodyView.kt`, `.../mail/read/MailReadRepository.kt`, `.../mail/read/MailReadScreen.kt`, `.../mail/read/MailReadViewModel.kt`, `.../mail/list/MailApi.kt` (one added parameter; the file is mostly DTOs of other tasks). Excluded: `res/values/strings_mail.xml`, `res/values-de/strings_mail.xml` (resources, no executable code; covered by the English and German Compose tests); test files; `tasks/` and `reviews/`. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `./gradlew createDebugUnitTestCoverageReport` in `android/`, whole debug unit test suite (`docs/ARCHITECTURE.md` "Coverage").
- Run start/completion time and exit status: completed 2026-11-08 09:59:53 +0100 (report.xml modification time), exit status success (reported by the parent).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 8dae3e9b62b8e48f3612a9a9225442ed8206201db4c8110e6be19493431d9b25.
- Evidence outputs are fresh and inputs stayed unchanged: the report was written 4 s before commit a441e181d (09:59:57); the parent ran it on the working tree that became that commit, after the TW-1 fix and ktlintFormat. Its counters match the numbers the parent reported.
- Final run after latest test additions and production fixes: yes.

Values use covered/total (percentage). JaCoCo has no statement counter; the Statements column is JaCoCo instructions. Functions are JaCoCo methods.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `mail/read/MailImages.kt` | 170/173 (98.3%) | 27/28 (96.4%) | 8/8 (100%) | 33/33 (100%) |
| `mail/read/MailBodyView.kt` | 307/310 (99.0%) | 23/24 (95.8%) | 13/14 (92.9%) | 49/50 (98.0%) |
| `mail/read/MailReadRepository.kt` | 689/695 (99.1%) | 66/66 (100%) | 32/34 (94.1%) | 62/62 (100%) |
| `mail/read/MailReadScreen.kt` | 3177/3276 (97.0%) | 139/160 (86.9%) | 53/63 (84.1%) | 236/240 (98.3%) |
| `mail/read/MailReadViewModel.kt` | 1028/1028 (100%) | 76/76 (100%) | 49/49 (100%) | 108/108 (100%) |
| `mail/list/MailApi.kt` | 629/1072 (58.7%) | N/A (no branches) | 77/112 (68.8%) | 85/122 (69.7%) |

Aggregate over all six files (summed counters, no file missing or unmeasured): instructions 6000/6554 (91.5%), branches 331/354 (93.5%), methods 232/280 (82.9%), lines 573/615 (93.2%). Without `MailApi.kt` (mostly DTOs of other tasks): instructions 5371/5482 (98.0%), branches 331/354 (93.5%), methods 155/168 (92.3%), lines 488/493 (99.0%).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailImages.kt:78` (1 branch, `encodedPath ?: return false`) | A same-origin https URL without a path | Not reachable: a hierarchical https URL with a host always has a path; justified gap |
| `MailBodyView.kt:112` (1 branch, same document on recomposition) | The page is not reloaded on recomposition | Pre-existing behavior, unchanged by this task; justified gap |
| `MailBodyView.kt:128` (`ActivityNotFoundException` catch in `openExternally`) | A tapped link without a handling app | Pre-existing code outside this task's change |
| `MailBodyView.kt:68` (companion initializer) | None (class initialization) | JaCoCo/Robolectric artifact; non-material |
| `MailReadScreen.kt:354`, `:360` (2 branches each: `callbacks` null inside `onClick`) | Clicking a disabled banner button does nothing | Covered behaviorally by `the banner buttons wait while an action runs` (a disabled button never runs `onClick`); the null branch is unreachable |
| `MailReadScreen.kt:113`, `:115` (default `{}` callbacks) | None | Defaults of `MailReadCallbacks`, never invoked; non-material |
| `MailReadScreen.kt:223` (1 branch in the notice `when`) | None | Compiler-generated exhaustive-`when` fallback; both new notices are covered |
| `MailReadScreen.kt:64-70, 107-111, 146-152, 210-242, 377-384` | Route defaults, callback defaults, existing notices and reply buttons | Code of other tasks or compiler-generated default/`when` paths; outside this task's change |
| `MailReadRepository.kt:20`, `:22` | Generated members of the `MailAttachment` data class | Pre-existing, outside this task's change |
| `MailApi.kt:69-72` (message default-argument helper) and DTO lines 96-233 | The new `remoteImages` parameter; DTOs of other tasks | Retrofit calls the interface method with both arguments, so the default-argument helper never runs; the parameter's effect is covered by `remote images are asked for through the proxy only when wanted`; DTO lines belong to other tasks |

## Unable to test

- AC5 (manual check on an emulator or device against a running server with a `cid:` image and a remote image): needs an emulator and a running stack; the parent worker records it or skips it with a reason.
- Real Chromium behavior (that the WebView really asks `shouldInterceptRequest` for `img` loads with `blockNetworkLoads = false`, and enforces the CSP): Robolectric's WebView does not render or load; the tests check the client, the fetcher and the CSP text. Covered only by AC5.

## Summary

Tests added and all validation passed. Testing found one production defect (TW-1, missing 200 status on image answers); the parent fixed it in a441e181d, and the tests that exposed it now pass. Task-scoped coverage is sufficient: the remaining gaps are unreachable branches, compiler-generated code or code of other tasks. `scripts/check.sh` and the manual AC5 check remain for the parent worker.
