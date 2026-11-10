---
title: Android mail body is blank: WebView refuses its own page (403 on data: URL)
status: in_progress
owner: dell26/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt,android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailWebViewTest.kt]
prio: 1
lease_until: 2026-11-10T06:20:20Z
---
## Goal
Since `and-mail-images` (f6cd5056d) the Android read view shows almost nothing of most mails: the WebView shows
"Web page not available ... data:text/html;charset=utf-8;base64, could not be loaded because:
net::ERR_HTTP_RESPONSE_CODE_FAILURE" (owner report on a device, 2026-11-10). "Load images" changes nothing.

Cause: `MailWebViewClient.shouldInterceptRequest` (`MailBodyView.kt`) answers *every* request, and anything that is not
a server image gets `blockedResponse()` (empty 403). `loadDataWithBaseURL` is implemented by Chromium as a load of a
`data:text/html;base64,...` URL, and current WebView versions pass that main-document request (and `data:` images,
which the CSP allows) through `shouldInterceptRequest` too, so the page itself is answered with 403 and replaced by the
error page. Robolectric does not run Chromium, so the unit tests could not see it; the manual device check of
`and-mail-images` was skipped.

## Acceptance criteria
- [x] `shouldInterceptRequest` returns `null` (WebView handles it locally) for `data:` URLs, so the mail document and
      `data:` images load; server images still go through `MailImageFetcher`; every other request (other hosts and
      schemes, `MAIL_ORIGIN` paths that are not server images, non-GET) still gets the empty 403 without network access
  - verified (2026-11-10 06:55+0100 by dell26/agent-1): MailWebViewTest (Robolectric) via ./gradlew testDebugUnitTest --tests '*mail.read.*' at 8cb28d7df -> data:text/html main-frame and data:image subresource return null; server attachment via MockWebServer fetcher 200; other hosts/schemes, MAIL_ORIGIN non-image paths, POST/DELETE -> empty 403, strict OkHttp mock sees no call
- [x] Unit tests: a main-frame `data:text/html;...` request and a `data:image/png;base64,...` subresource request are
      not intercepted (null); the existing "other host is blocked" tests still pass; the override return type is
      nullable as in the platform API
  - verified (2026-11-10 06:55+0100 by dell26/agent-1): ./gradlew testDebugUnitTest --tests '*mail.read.*' -> MailWebViewTest 12/12 pass incl. new data: null test and existing other-host-blocked tests; override signature now WebResourceResponse? (MailBodyView.kt)
- [x] No other security setting is loosened (JavaScript off, CSP unchanged, `shouldOverrideUrlLoading` unchanged)
  - verified (2026-11-10 06:55+0100 by dell26/agent-1): git diff f797ff557..8cb28d7df -- android -> only shouldInterceptRequest body + comments change; lockDownForMail settings, CSP in mailDocument and shouldOverrideUrlLoading untouched; MailWebViewTest settings/CSP/link tests pass
- [~] Manually verified on an emulator or device against a running server with an HTML newsletter, a `cid:` image and
      "Load images" (record what you ran); if no emulator is available, skip with reason and assume what you could not see
  - skipped (2026-11-10 06:52+0100 by dell26/agent-1): no emulator, adb or device on this machine (which adb emulator -> not found); Robolectric does not run Chromium
- [x] `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass
  - verified (2026-11-10 07:02+0100 by dell26/agent-1): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport at 8cb28d7df -> exit 0 (5414 tests, 0 failures); sh scripts/check.sh -> all checks passed

## Notes
Planned 2026-11-10 from the owner's bug report; diagnosis by an interactive session. Keep the fix minimal: do not
change the image fetching, the banner or the CSP.
- ASSUMPTION (2026-11-10 06:52+0100 by dell26/agent-1): Not checked on a device: assumed that returning null for data: URLs from shouldInterceptRequest lets Chromium load the loadDataWithBaseURL document and data: images (platform contract: null = WebView loads it itself); owner should re-check an HTML newsletter, a cid: image and Load images on a device
- ASSUMPTION (2026-11-10 06:53+0100 by dell26/agent-1): data: URLs are left to the WebView regardless of HTTP method: a data: URL carries its content and never reaches the network, so the criterion's 'non-GET gets 403' is read as applying to network (non-data:) requests
