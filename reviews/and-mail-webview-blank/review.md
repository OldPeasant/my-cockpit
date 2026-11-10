# Code Review

Date: 2026-11-10

Task: and-mail-webview-blank - Android mail body is blank: WebView refuses its own page (403 on data: URL)

Scope: task commit range f797ff557..ca187f390 (committed changes only)

Requirements:
- tasks/and-mail-webview-blank.md acceptance criteria 1-5
- AGENTS.md project notes (security conventions, lint contract)
- SPEC.md section 3 "HTML email is untrusted" (as referenced by the KDoc of lockDownForMail)

Git range:
- Branch: main
- HEAD: ca187f3903c239a6b8c734f555abf6c9629457a9
- Base/start: f797ff55725968fe767d2daaec73297bba1c9505
- Commits reviewed: 1 (ca187f390 "and-mail-webview-blank: leave data: requests to the WebView")

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Files reviewed in full: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt`,
`android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailImages.kt` (context, unchanged),
`android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailWebViewTest.kt`.

Gradle was not run for this review (the parent asked for a read-only review because another helper runs it).

## Verification notes

Criterion 1 (data: returns null, everything else unchanged): `shouldInterceptRequest` (MailBodyView.kt:53-62) returns
`null` only when `request.url.scheme` equals `data` case-insensitively; a null scheme (relative URL) compares false via
Kotlin's nullable `String?.equals`, so it falls through to the old path. The `else` branch is byte-for-byte the previous
expression (`images?.response(request) ?: blockedResponse()`), so server images still go through `MailImageFetcher`,
and other hosts, schemes, non-image `MAIL_ORIGIN` paths and non-GET methods still get the empty 403 from
`MailImageFetcher.response` / `blockedResponse()` (MailImages.kt:38-45, 83-84). The override's return type is now
`WebResourceResponse?`, matching the platform signature.

Security (criterion 3): the diff touches only `shouldInterceptRequest` and its KDoc. `lockDownForMail` (JS off, no
file/content access, no multiple windows, no DOM storage), the CSP in `mailDocument`
(`default-src 'none'; style-src 'unsafe-inline'; img-src MAIL_ORIGIN data:`), and `shouldOverrideUrlLoading` are
unchanged. Returning null for a `data:` URL cannot reach the network: a `data:` URL carries its payload inline and is
decoded by the renderer, it has no host. Navigations to `data:` documents initiated by the page still go through
`shouldOverrideUrlLoading`, which returns true (dropped) for everything. A `data:text/html` subframe would need
`frame-src`/`child-src`, which falls back to `default-src 'none'`; scripts are disabled anyway. Nothing loosened.

Criterion 2 (tests): the new test `the mail document and data images are left to the web view, with and without an
image fetcher` (MailWebViewTest.kt:122-134) asserts null for a main-frame `data:text/html;charset=utf-8;base64,...`
request and for `data:image/png` and upper-case `DATA:image/gif` subresource requests, for a view with a strict-mock
`OkHttpClient` fetcher (any network call would throw) and for the view without a fetcher. The existing "other host is
blocked" test keeps its intent: the `data:image/png` entry, which now must not be blocked, was moved to the new test and
replaced by `content://` and `javascript:` entries, which strengthens the negative case rather than weakening it. The
"without an image fetcher every request is blocked" test is unchanged.

Criterion 4 is recorded as skipped in `tasks.py verify` (no device run); criterion 5 has no evidence recorded yet. Both
are the parent worker's responsibility and not code findings.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | lockDownForMail KDoc still says the client answers every request**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt:21`
    - Category: Quality
    - Problem: the KDoc of `lockDownForMail` (lines 21-23) says `MailWebViewClient` "answers every request itself" so
      "the WebView never fetches anything on its own". After this change `data:` requests are deliberately left to the
      WebView. The security conclusion (no network fetch) still holds, but the sentence no longer describes the code;
      the class KDoc at lines 44-48 was updated, this one was not. The same wording exists in the `MailImageFetcher`
      KDoc (MailImages.kt:27, "Answers every resource request of a mail page"), which is outside this task's touches.
    - Why it matters: the next reader auditing the WebView's network surface may trust the comment and miss the `data:`
      exception, or "fix" it back to blocking, which would reintroduce the blank-page bug.
    - Suggested fix: reword to e.g. "answers every network request itself (... everything else refused; `data:` URLs,
      which never touch the network, are left to the WebView)".

## Review conclusion

No material issues found.
