# Test Report

Date: 2026-11-08

Task: and-mail-images: Android shows mail images (inline and on request)

Scope: `git diff 76a46b9ff9de8a22a4210eadb3abb00e76f80af9..b2627d776f8e668ff4dcc25c19f2570bd23b4f65 -- android` (task commits 6d93a6246 and b2627d776, rebased copies of 982f7e8b0 and 6ee3de4a5 with identical android changes), plus the uncommitted test files listed below.

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
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadScreenTest.kt` (8 tests added; the shared `show` helper now also records the two banner callbacks)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadTestSupport.kt` (helper `pageRequest` for WebResourceRequest)

No existing test was weakened, removed or disabled. No production file was changed.

## Tests added or updated

Count: 48 new tests (MailImagesTest 12, MailReadImagesViewModelTest 17, MailReadImagesGermanTest 4, MailWebViewTest 4, MailReadRepositoryTest 3, MailReadScreenTest 8).

## Requirement coverage

The test-writer sub-agent cannot run Gradle in this environment; the parent worker runs the command below. "Not run yet" means exactly that: no result is claimed here.

Command for all rows: `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: only the configured server's attachment download and image-proxy URLs are fetched, through the app's OkHttp client | MailImagesTest: `the server's attachment downloads and image proxy are server images`, `a server image is fetched from the configured server and passed to the page`, `an inline attachment is fetched from its download path`, `the web view client answers server images through the fetcher` | as above | Not run yet (parent runs Gradle) |
| AC1: every other request stays blocked (other host, http, port, user info, other paths, dot segments, encoded slashes, non-GET, non-image or failed answers, unreachable server) | MailImagesTest: `other hosts, schemes, ports, user info and paths are not server images`, `an answer that is not an image is not passed to the page`, `a failed image answer is refused, also when it claims to be an image`, `an unreachable or unconfigured server gives a refused image, not a crash`, `only GET requests are fetched`, `a request to another host or path never reaches the server`, `a server url with a malformed port is refused, not thrown`; MailWebViewTest: `without an image fetcher every request is blocked, also the server's images` | as above | Not run yet |
| AC1: WebView headers (cookies, referrer) are not forwarded | MailImagesTest: `a server image is fetched from the configured server and passed to the page` | as above | Not run yet |
| AC1: JavaScript stays off; links to the stand-in origin are not opened | MailWebViewTest: `the settings lock the untrusted html down` (existing), `a tapped link to the server's stand-in origin is not opened` | as above | Not run yet |
| AC2: banner with "Load images" (reloads with remoteImages=PROXY) and "Always for this sender" (adds to mailImageAllowedSenders) | MailReadScreenTest: `blocked remote images show a banner with load images and always for this sender`, `one blocked image is counted in the singular`, `without a sender address the banner offers only load images`, `a mail without blocked images has no banner`, `the banner buttons wait while an action runs`, `failed images are reported`, `an unsaved sender is reported`, `the route's load images shows the mail with proxied images and the banner goes away`; MailReadRepositoryTest: `remote images are asked for through the proxy only when wanted`, `a failed proxied load is reported like any other load`; MailReadImagesViewModelTest: `load images shows the mail again with proxied images`, `a failed load images keeps the mail and says so`, `load images runs once while busy and not before the mail is shown`, `images that arrive after the mail was closed or replaced are dropped`, `always for this sender adds the sender to the allowlist and loads the images`, `a sender already allowed, in any case, is not saved again`, `a sender that could not be saved still gets this mail's images, with a notice`, `settings not loaded yet start the allowlist with this sender`, `when the images fail after always for this sender, the image failure is shown`, `always for this sender does nothing for a mail without a sender address` | as above | Not run yet |
| AC3: policy allow or an allowed sender loads images through the proxy without asking | MailReadImagesViewModelTest: `without settings, or with the default policy, remote images stay blocked`, `policy allow loads the mail with proxied images right away`, `policy allow that fails to load shows the load error`, `an allowed sender, in any case, gets its images through the proxy after the first load`, `an allowed sender whose proxied load fails still sees the mail, with its images blocked`, `an allowed sender's mail without blocked images is loaded only once`, `a mail without a sender address is not matched by the allowlist`; MailReadRepositoryTest: `the sender address is the first non-blank From address, trimmed` | as above | Not run yet |
| AC4: CSP allows images only from the server origin and data:; a request to another host is blocked | MailWebViewTest: `the page's directives allow images only from the server origin and data, and nothing else`, `a request to another host is blocked, also with an image fetcher`, `the page around the mail allows only inline styles and images from the server or data` (existing); MailImagesTest: `the page origin is the server's stand-in, without a trailing slash` | as above | Not run yet |
| AC5: manual verification on an emulator/device | none | none | Not testable by this helper (needs an emulator and a running server; parent's responsibility) |
| AC6: new strings in English and German | MailReadScreenTest banner/notice tests (English); MailReadImagesGermanTest: `the banner and its buttons are German`, `one blocked image is counted in the German singular`, `failed images are reported in German`, `an unsaved sender is reported in German` | as above | Not run yet |
| AC7: unit/Compose tests cover the new behavior; ktlintCheck, testDebugUnitTest, lintDebug and scripts/check.sh pass | all of the above | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`; `sh scripts/check.sh` | Not run yet (parent runs) |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'`

Result:

Not run by the test-writer (sub-agents cannot run Gradle here). The parent worker runs it and records the result in `quality.json` and `tasks.py verify`.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run by the test-writer; the parent worker runs it (ktlintCheck also checks the new test files).

## .taskcheck

Result:

Not run by the test-writer; the parent worker runs `sh scripts/check.sh`.

## Bugs found

None found by inspection. Two points that the new tests pin down and that the parent should look at if they fail:

- `a server url with a malformed port is refused, not thrown`: relies on `android.net.Uri` (Robolectric uses the framework class) treating `server.invalid:abc` as the host, so `isServerImage` is false. If it ever returned true, `Request.Builder().url(...)` would throw an `IllegalArgumentException`, which `MailImageFetcher.response` does not catch (it only catches `IOException`). Chromium canonicalizes URLs before `shouldInterceptRequest`, so this is not expected to be reachable in practice.
- `the server's attachment downloads and image proxy are server images` includes an upper-case UUID and `HTTPS://SERVER.INVALID/...`; both are accepted by the current regex and the case-insensitive scheme/host checks.

## Coverage

Status: unavailable at the time of writing (the test-writer cannot run Gradle); to be measured by the parent worker with the command below. No numbers are claimed here.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-images; base 76a46b9ff9de8a22a4210eadb3abb00e76f80af9; HEAD b2627d776f8e668ff4dcc25c19f2570bd23b4f65; task commits 6d93a6246, b2627d776 (from the parent and `git log 76a46b9ff..HEAD`).
- Source/test snapshot identifier (including uncommitted inputs), sha256: MailImagesTest.kt f8d640b1a801fe2e, MailReadImagesViewModelTest.kt 9cda7b571f03cf42, MailReadImagesGermanTest.kt bcc5d283ee585a95, MailReadScreenTest.kt 4f80aa5658fce66e, MailReadRepositoryTest.kt 9588a044bc6d631d, MailWebViewTest.kt 380cb86f0ac59bde, MailReadTestSupport.kt f011f855ad591fdc (first 16 hex digits).
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailImages.kt`, `.../mail/read/MailBodyView.kt`, `.../mail/read/MailReadRepository.kt`, `.../mail/read/MailReadScreen.kt`, `.../mail/read/MailReadViewModel.kt`, `.../mail/list/MailApi.kt` (one added parameter). Excluded: `res/values/strings_mail.xml`, `res/values-de/strings_mail.xml` (resources, no executable code; covered by the English and German Compose tests); test files; `tasks/and-mail-images.md`.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `./gradlew createDebugUnitTestCoverageReport` in `android/` (whole debug unit test suite), per `docs/ARCHITECTURE.md` "Coverage".
- Run start/completion time and exit status: not run by the test-writer.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (per-file `sourcefile` counters in package `ch/sonensei/cockpit/mail/read` and `ch/sonensei/cockpit/mail/list`).
- Evidence outputs are fresh and inputs stayed unchanged: not applicable yet.
- Final run after latest test additions and production fixes: no; the parent worker must run coverage on the snapshot above.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `mail/read/MailImages.kt` | not measured | not measured | not measured | not measured |
| `mail/read/MailBodyView.kt` | not measured | not measured | not measured | not measured |
| `mail/read/MailReadRepository.kt` | not measured | not measured | not measured | not measured |
| `mail/read/MailReadScreen.kt` | not measured | not measured | not measured | not measured |
| `mail/read/MailReadViewModel.kt` | not measured | not measured | not measured | not measured |
| `mail/list/MailApi.kt` | not measured | not measured | not measured | not measured |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail/read/MailBodyView.kt` `MailBodyView` update block, `openExternally` | Loads the page on `https://server.invalid/` and opens tapped links externally | Base URL is asserted by the existing `MailBodyViewTest`; `openExternally` starts another app and is outside this task's change |
| All scoped files | Exact gaps unknown until the parent's coverage run | Parent to report the JaCoCo counters; the test-writer can be re-run to add tests for any material gap |

## Unable to test

- AC5 (manual check on an emulator or device against a running server with a `cid:` image and a remote image): needs an emulator and a running stack; the parent worker records it or skips it with a reason.
- Real Chromium behavior (that the WebView really asks `shouldInterceptRequest` for `img` loads with `blockNetworkLoads = false`, and enforces the CSP): Robolectric's WebView does not render or load; the tests check the client, the fetcher and the CSP text. Covered only by AC5.

## Summary

Tests added; no production defects found by inspection. The tests have not been executed by the test-writer (no Gradle access): the parent worker must run `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'`, `./gradlew ktlintCheck testDebugUnitTest lintDebug`, `./gradlew createDebugUnitTestCoverageReport` and `sh scripts/check.sh`, and send failures or the coverage counters back so this report can be completed.
