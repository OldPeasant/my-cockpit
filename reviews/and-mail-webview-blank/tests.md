# Test Report

Date: 2026-11-10

Task: and-mail-webview-blank - Android mail body is blank: WebView refuses its own page (403 on data: URL)

Scope: `git diff f797ff557..ca187f390` (base f797ff55725968fe767d2daaec73297bba1c9505, task commit ca187f3903c239a6b8c734f555abf6c9629457a9, branch main). Production: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt`. Tests: `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailWebViewTest.kt` (task commit plus uncommitted additions from this run).

Requirements:
- tasks/and-mail-webview-blank.md acceptance criteria
- docs/ARCHITECTURE.md "Coverage"

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailWebViewTest.kt`

## Tests added or updated

Count: 2 added (on top of the 1 added and 1 updated in the task commit)

- `a scheme that only starts with data is still blocked`: `database:`, `datax:` and a server path containing `data:` still get 403 with an image fetcher (strict OkHttp mock, so no network).
- `server images still go through the fetcher, other server paths and non-GET requests never reach it`: through the real `MailWebViewClient` with a `MailImageFetcher` on a MockWebServer, an attachment URL on `MAIL_ORIGIN` returns 200 with the image bytes; `MAIL_ORIGIN` non-image paths (`/`, `/api/v1/settings`, a message path) and POST/DELETE to image paths return an empty 403, and the server sees exactly one request.

## Requirement coverage

Command for all rows: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests '*mail.read.*' createDebugUnitTestCoverageReport` (exit 0; MailWebViewTest 12/12, MailImagesTest 12/12, all mail.read suites 0 failures).

| Requirement | Tests | Result |
|---|---|---|
| AC1 data: URLs return null (document and data images) | `the mail document and data images are left to the web view, with and without an image fetcher` | Pass |
| AC1 server images still go through `MailImageFetcher` | `server images still go through the fetcher, other server paths and non-GET requests never reach it` | Pass |
| AC1 other hosts and schemes, `MAIL_ORIGIN` non-image paths, non-GET get empty 403 without network | `a request to another host is blocked, also with an image fetcher`, `a scheme that only starts with data is still blocked`, `server images still go through the fetcher, ...`, `without an image fetcher every request is blocked, also the server's images`, MailImagesTest `only GET requests are fetched` | Pass |
| AC2 unit tests for main-frame data:text/html and data:image subresource; other-host tests still pass; nullable return type | same as above (nullable return type is checked by compilation of `assertNull` on the override result) | Pass |
| AC3 no security setting loosened (JS off, CSP, shouldOverrideUrlLoading) | `the settings lock the untrusted html down`, `the page's directives allow images only from the server origin and data, and nothing else`, link tests | Pass |
| AC4 manual check on emulator/device | none | Not run: no emulator or device available to this helper; Robolectric does not run Chromium |
| AC5 full `ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` | none | Not run by this helper (parent runs them; Gradle must not run in parallel) |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests '*mail.read.*' createDebugUnitTestCoverageReport --console=plain -q`

Result:

Pass

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run (left to the parent worker within the time budget)

## .taskcheck

Result:

Not run (left to the parent worker)

## Bugs found

None.

Note on interpretation: a non-GET request to a `data:` URL returns null (the scheme check comes before the method check). Chromium cannot send such a request over the network, so this involves no network access; no test asserts either way. The parent may record this reading of AC1 as an assumption.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured from the JaCoCo XML line entries for the changed executable lines 56-62 of `MailBodyView.kt`: lines 57, 60, 61, 62 all covered (mi=0), branches on 60 (2/2) and 61 (4/4) covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-webview-blank; base f797ff557; HEAD ca187f390 (single task commit, supplied by the parent and matching the task `touches`).
- Source/test snapshot identifier (including uncommitted inputs): `MailBodyView.kt` sha256 a568b031bd1286805f9fe1e80d22b4b277433bbdbd5ffd41c1e26e7f648eb158 (unchanged from HEAD); `MailWebViewTest.kt` (uncommitted additions) sha256 adc10b8cc7d2b39a95ab9562b5abe9602d29a7a680f5b33eb3381f6c70bc7a22.
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt`; nothing excluded, renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; command above; working directory `android/`; selection `--tests '*mail.read.*'`.
- Run start/completion time and exit status: 2026-11-10 about 06:50 to 06:53:05 +0100; exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 534e8daa4e2d3c4e2d0c7c0cb3f5407fb946b6d02ca33d67ef5459d92fe91ec9.
- Evidence outputs are fresh and inputs stayed unchanged: report and test-result XML written by this run (06:53 local); inputs hashed after the run, no edits during it.
- Final run after latest test additions and production fixes: yes (no production fixes made).

Values below use covered/total (percentage).

| Changed file | Statements (instructions) | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `MailBodyView.kt` | 326/333 (97.9%) | 28/32 (87.5%) | 13/14 (92.9%) | 52/53 (98.1%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailBodyView.kt`:75 (partial), 116 (3 branches), 119 (1 branch), 135 | Pre-existing composable and helper code not changed by this task | Justified gap: outside the task diff |

## Unable to test

- AC4: the real Chromium behavior (the data: main-document load passing through `shouldInterceptRequest`) cannot be reproduced under Robolectric; needs a manual emulator or device check by the parent or a human.

## Summary

Tests added and all validation passed for the focused scope; no production defects found. Full Android suite, lintDebug, `.taskcheck` and the manual device check remain for the parent worker.
