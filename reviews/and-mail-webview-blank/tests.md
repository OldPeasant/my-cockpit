# Test Report

Date: 2026-11-10

Task: and-mail-webview-blank - Android mail body is blank: WebView refuses its own page (403 on data: URL)

Scope: round 2, final code. `git diff f797ff557..8cb28d7df` (base f797ff55725968fe767d2daaec73297bba1c9505, HEAD 8cb28d7df8934c0ed7be9dc20f46bb30992072b2, branch main). Task commits: 2aeb07047 (fix and tests), b846a170b (round-1 tests and reports), 8cb28d7df (fix of review finding CR-1, KDoc only). Production: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt`. Tests: `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailWebViewTest.kt`. Since round 1 (report committed in e5bbd7f10) only the `lockDownForMail` KDoc (lines 22-24) changed; no executable line changed.

Requirements:
- tasks/and-mail-webview-blank.md acceptance criteria and its recorded assumptions (data: URLs left to the WebView regardless of HTTP method; device check not done)
- docs/ARCHITECTURE.md "Coverage"
- reviews/and-mail-webview-blank/fixes.md (CR-1)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailWebViewTest.kt` (in the task commits; unchanged in round 2)

## Tests added or updated

Count: 0 in round 2 (round 1 and the task commit added 3 and updated 1; they are listed under "Requirement coverage").

No test was added in round 2: the only change is a comment, and the behavior it describes (data: left to the WebView, every other request answered by the client) is already covered. A non-GET request to a `data:` URL is deliberately not asserted: it is a recorded assumption a human may still reverse.

## Requirement coverage

Command for all automated rows, run by the parent worker on 8cb28d7df with no other changes: `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -q` (exit 0). This helper read the resulting test-result XMLs: 441 suites, 5414 tests, 0 failures, 0 errors, 0 skipped; MailWebViewTest 12/12, MailImagesTest 12/12, MailBodyViewTest 2/2.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 data: URLs return null (document and data images) | `the mail document and data images are left to the web view, with and without an image fetcher` | command above | Pass |
| AC1 server images still go through `MailImageFetcher` | `server images still go through the fetcher, other server paths and non-GET requests never reach it` | command above | Pass |
| AC1 other hosts and schemes, `MAIL_ORIGIN` non-image paths, non-GET get empty 403 without network | `a request to another host is blocked, also with an image fetcher`, `a scheme that only starts with data is still blocked`, `server images still go through the fetcher, other server paths and non-GET requests never reach it`, `without an image fetcher every request is blocked, also the server's images`, MailImagesTest `only GET requests are fetched` | command above | Pass |
| AC2 unit tests for main-frame data:text/html and data:image subresource; other-host tests still pass; nullable return type | same as AC1 (the nullable return type is checked at compile time by `assertNull` on the override result) | command above | Pass |
| AC3 no security setting loosened (JS off, CSP, shouldOverrideUrlLoading) | `the settings lock the untrusted html down`, `the page's directives allow images only from the server origin and data, and nothing else`, `web and mail links go to another app, nothing loads inside the view`, `links the page follows by itself or in a frame are dropped`, `a tapped link to the server's stand-in origin is not opened` | command above | Pass |
| AC4 manual check on emulator/device | none | none | Not run: no emulator or device available; Robolectric does not run Chromium (skipped and assumed in the task file) |
| AC5 `ktlintCheck testDebugUnitTest lintDebug` | full Android unit suite | command above (parent run) | Pass |
| AC5 `sh scripts/check.sh` | none | none | Not run by this helper (left to the parent worker) |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -q` (run by the parent on 8cb28d7df; this helper's permissions do not allow Gradle, so it read the outputs only)

Result:

Pass (exit 0; mail.read suites all 0 failures; MailWebViewTest 12/12)

## Full test execution

Command:

same command (it runs the whole `testDebugUnitTest` suite plus `ktlintCheck` and `lintDebug`)

Result:

Pass (441 suites, 5414 tests, 0 failures, 0 errors)

## .taskcheck

Result:

Not run (left to the parent worker)

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured from the JaCoCo XML line entries intersected with the task diff `f797ff557..8cb28d7df` (added lines 57-63 of `MailBodyView.kt`; lines 22-24 and 47-48 are comments). Executable changed lines in the report: 58, 61, 62, 63, all covered (mi=0): 4/4 lines; branches on line 61 2/2 and line 62 4/4, so 6/6 changed-line branches.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-webview-blank; base f797ff55725968fe767d2daaec73297bba1c9505; HEAD 8cb28d7df8934c0ed7be9dc20f46bb30992072b2; task commits and base supplied by the parent, matching the task `touches`.
- Source/test snapshot identifier (including uncommitted inputs): no uncommitted source or test changes (only `reviews/and-mail-webview-blank/review.md` modified in the tree); `MailBodyView.kt` sha256 c1402c59bc5c5f993d72de776f89c2d6c16580c7086763add54d5a754dd00af1; `MailWebViewTest.kt` sha256 adc10b8cc7d2b39a95ab9562b5abe9602d29a7a680f5b33eb3381f6c70bc7a22 (same as round 1).
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt`; nothing excluded, renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (`createDebugUnitTestCoverageReport`); `./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -q` in `android/`; whole debug unit test suite (no `--tests` filter), run by the parent worker.
- Run start/completion time and exit status: test-result XMLs written 2026-11-10 07:00:33 +0100, coverage report 07:00:36 +0100; exit 0 (reported by the parent).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 d75ea6b3a24d74f44dde3dc701d2e41645d65989f1a1e04b3a223fba94e36d64; test results in `android/app/build/test-results/testDebugUnitTest/`.
- Evidence outputs are fresh and inputs stayed unchanged: the round-1 report (06:53:05, sha256 534e8daa...) was replaced by this run's report (07:00:36, different hash); all result XMLs carry 07:00 timestamps; the report's line numbers match the HEAD file (shifted by one after the KDoc change); input hashes above were taken after the run and match HEAD.
- Final run after latest test additions and production fixes: yes (CR-1 fix 8cb28d7df is the measured revision; no tests added since).

Values below use covered/total (percentage).

| Changed file | Statements (instructions) | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt` | 326/333 (97.9%) | 28/32 (87.5%) | 13/14 (92.9%) | 52/53 (98.1%) |

Aggregate over the scope equals the single file above; no files are missing or unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailBodyView.kt`:76 (2 instructions, companion object), 117 (3 of 6 branches, `AndroidView` factory lambda), 120 (1 of 2 branches, tag check), 136 (`openExternally` catch) | Pre-existing composable and helper code, not changed by this task | Justified gap: outside the task diff; all changed lines and branches are covered |

## Unable to test

- AC4: real Chromium behavior (the data: main-document load passing through `shouldInterceptRequest`) cannot be reproduced under Robolectric; needs a manual emulator or device check by a human (recorded as skipped plus assumption in the task file).
- `sh scripts/check.sh` was not run by this helper (no Gradle permission; the parent runs it).

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. Full Android unit suite, ktlint and lintDebug pass on 8cb28d7df; `.taskcheck` and the manual device check remain for the parent worker.
