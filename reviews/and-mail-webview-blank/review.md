# Code Review

Date: 2026-11-10

Task: and-mail-webview-blank - Android mail body is blank: WebView refuses its own page (403 on data: URL)

Scope: task commit range f797ff557..8cb28d7df, android changes (`git diff f797ff557..8cb28d7df -- android`), committed
changes only. Round 2: final code after the round-1 review, the test-writer's additional tests and the CR-1 fix.

Requirements:
- tasks/and-mail-webview-blank.md acceptance criteria 1-5 and the two recorded assumptions
- AGENTS.md project notes (security conventions, lint contract, fictitious test data)
- SPEC.md section 3 "HTML email is untrusted" (as referenced by the KDoc of lockDownForMail)
- reviews/and-mail-webview-blank/fixes.md (CR-1 resolution)

Git range:
- Branch: main
- HEAD: 8cb28d7df8934c0ed7be9dc20f46bb30992072b2
- Base/start: f797ff55725968fe767d2daaec73297bba1c9505
- Commits reviewed: 3 code commits (2aeb07047 fix, b846a170b tests and round-1 reports, 8cb28d7df CR-1 comment fix);
  the other commits in the range only touch `tasks/` (verify and assume entries)
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Files reviewed in full: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt`,
`android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailWebViewTest.kt`; context read:
`android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailImages.kt`,
`android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadTestSupport.kt` (`pageRequest` helper),
`android/app/src/main/java/ch/sonensei/cockpit/core/network/NetworkModule.kt` (`CockpitHttp.okHttpClient`).

Gradle was not run for this review (the parent asked for a read-only review because the test-writer runs it).

## Verification notes

Criterion 1: `shouldInterceptRequest` (MailBodyView.kt:54-63) returns `null` only when `request.url.scheme` equals
`data` case-insensitively (a null scheme compares false and falls through). The `else` branch is unchanged
(`images?.response(request) ?: blockedResponse()`), so server images still go through `MailImageFetcher`, and other
hosts and schemes, non-image `MAIL_ORIGIN` paths and non-GET requests still get the empty 403 (MailImages.kt:38-45).
`data:` requests are not intercepted regardless of method, which matches the recorded assumption; a `data:` URL carries
its payload inline and has no host, so this opens no network path.

Criterion 2: the override's return type is `WebResourceResponse?`, as in the platform API. Tests:
- `the mail document and data images are left to the web view, with and without an image fetcher` asserts null for a
  main-frame `data:text/html;charset=utf-8;base64,...` request and for `data:image/png` and upper-case `DATA:image/gif`
  subresource requests, with a strict-mock `OkHttpClient` fetcher (any call would throw a non-IO exception and fail the
  test) and without a fetcher.
- `a scheme that only starts with data is still blocked` (new) covers `database:`, `datax:` and a server path
  containing `data:`, all 403; this guards against a prefix-match regression.
- `server images still go through the fetcher, other server paths and non-GET requests never reach it` (new) uses
  MockWebServer with the app's `CockpitHttp.okHttpClient` (same pattern as TokenAuthenticatorTest): one attachment
  image is fetched and returned with status 200 and its body; non-image server paths, a POST to the attachment and a
  DELETE to the image proxy get an empty 403, and the request count stays at 1, so they never reached the network. The
  server is closed in `finally`. Test data is fictitious.
- The existing "other host is blocked" test keeps its intent: its `data:image/png` entry (now intentionally allowed)
  moved to the new test and was replaced by `content://` and `javascript:` entries. "Without an image fetcher every
  request is blocked" is unchanged.

Criterion 3: only `shouldInterceptRequest` and two KDoc blocks changed. `lockDownForMail` settings, the CSP in
`mailDocument` and `shouldOverrideUrlLoading` are unchanged; the existing tests for them still assert the same values.

Round-1 CR-1: fixed. The `lockDownForMail` KDoc (MailBodyView.kt:19-25) now says the client answers every network
request and that `data:` URLs are left to the WebView, matching the code and the class KDoc. The similar sentence in the
`MailImageFetcher` KDoc (MailImages.kt:27) is still accurate for that class's own `response` function (it is called
for the non-`data:` requests) and lies outside the task's touches; not reported.

Criterion 4 is recorded as skipped (no emulator or device) with an assumption; criterion 5 (Gradle and `.taskcheck`)
is evidence the parent worker records. Neither is a code finding.

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
