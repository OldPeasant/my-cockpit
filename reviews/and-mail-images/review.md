# Code Review

Date: 2026-11-08

Task: and-mail-images - Android shows mail images (inline and on request)

Scope: task commits of and-mail-images on top of the task base (`git diff 76a46b9ff..6ee3de4a5`). They were later rebased to 6d93a6246 / b2627d776; `git diff 6ee3de4a5 b2627d776 -- android` is empty, so the reviewed Android code is the same. Uncommitted test changes by the parallel test-writer were not reviewed.

Requirements:
- tasks/and-mail-images.md acceptance criteria 1-7 and the recorded assumptions (placeholder origin https://server.invalid, blockNetworkLoads/blockNetworkImage false, allowed-sender check, optional `remoteImages` query param in MailApi)
- docs/PARITY.md item 5 (remote images blocked by default, per-sender allowlist from synced settings, `cid:` images always shown)
- SPEC.md §7.3 / §4.1 (sanitized HTML, remote images blocked by default, "load images", proxied through the server)
- AGENTS.md project notes (lint, strings EN/DE, no secrets in logs)
- Server URL formats: server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadService.kt (`attachmentUrl`, `proxyUrl`, `MESSAGES_PATH`, `IMAGE_PROXY_PATH`), mail/sanitize/HtmlMailSanitizer.kt, mail/api/RemoteImageFetcher.kt

Git range:
- Branch: main
- HEAD: b2627d776f8e668ff4dcc25c19f2570bd23b4f65 (rebased copy of 6ee3de4a5)
- Base/start: 76a46b9ff9de8a22a4210eadb3abb00e76f80af9
- Commits reviewed: 2 (982f7e8b0, 6ee3de4a5)

Summary: 0 P0, 1 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | No tests for request interception, the images banner or the image loading logic in the view model**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailImages.kt:38` (also `MailBodyView.kt:52`, `MailReadViewModel.kt:141-220`, `MailReadScreen.kt:333`, `MailReadRepository.kt:63`)
    - Category: Tests
    - Problem: The task commits only adjust existing tests (new constructor, base URL, the CSP string, `blockNetworkLoads` now false). Nothing tests `isServerImage`, `MailImageFetcher.response`, or `MailWebViewClient.shouldInterceptRequest`: refusing another host, a non-GET request, other server paths, a wrong port, user info, a non-image or failed answer, and an IOException. Nothing tests the banner and its buttons (Load images, Always for this sender, hidden without a sender address, disabled while busy), `loadImages`, `alwaysLoadImages` (sender already allowed, saving fails and shows SenderNotSaved, loading fails and shows ImagesFailed), the `mailImagePolicy = allow` / allowed-sender paths in `loadMessage` (including falling back to the blocked copy), the `remoteImages=PROXY` query, or the `fromAddress` mapping.
    - Why it matters: Criterion 4 requires "a test asserts a request to another host is blocked". Criterion 7 requires tests for the new behavior. With `blockNetworkLoads` now false, the interception code is the only thing that stops the WebView from loading remote content, so a regression there would quietly leak trackers and the user's IP.
    - Suggested fix: Add unit tests for `isServerImage` (the server's real formats `/api/v1/mail/messages/{uuid}/attachments/{partId}?inline=true` and `/api/v1/mail/image-proxy?url=...`, plus refused variants), and test `MailImageFetcher` with a MockWebServer-backed OkHttpClient that uses `ServerUrlInterceptor`. Test `MailWebViewClient` with `images = null` and with a request to another host, ViewModel tests for the paths listed above, and Compose tests for the banner. The parallel test-writer may already cover this. If it does, this finding is resolved by its tests.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task.

Notes from the review (checked, no finding):
- Security of the WebView setup: every subresource request goes through `shouldInterceptRequest`, which never returns null (it returns the server image or an empty 403). Main-frame navigations are always cancelled in `shouldOverrideUrlLoading`, and links to the placeholder host are not opened externally. JavaScript, file and content access, DOM storage and multiple windows stay off. The CSP is `default-src 'none'; style-src 'unsafe-inline'; img-src https://server.invalid data:` on the base origin `https://server.invalid/`.
- Only GET requests to https on the placeholder host, port 443 or default, without user info, are fetched. Their paths must match the image proxy path exactly or an attachment path with a 36-character id and a part id that has no escapes or dot segments. The access token and the real host are added only by the app's OkHttpClient (`ServerUrlInterceptor` + `AccessTokenInterceptor`). WebView headers and cookies are not forwarded. Only `image/*` answers reach the page, and other answers are closed.
- Server conformity: `attachmentUrl(..., inline = true)` produces `/api/v1/mail/messages/{uuid}/attachments/{partId}?inline=true`, and `proxyUrl` produces `/api/v1/mail/image-proxy?url={encoded}`. Both match `isServerImage` (it checks only the path). The encoded query is kept by `ServerUrlInterceptor`, and a server path prefix (e.g. `/cockpit/`) is added by the interceptor. A mail cannot write its own server-relative image URLs into the page: jsoup drops relative `src` values (no base URI), unknown schemes are removed, absolute `https://server.invalid/...` sources count as remote images (blocked or proxied), and CSS `url()` is stripped. So "blocked by default" cannot be bypassed through the authenticated fetcher. The server's image proxy follows redirects itself, so the device never contacts the remote host.
- View model: the session guard (`updateFor`) covers the new paths. `run` blocks overlapping actions through `busy`. A failed proxied re-fetch keeps the blocked copy with its banner. "Always for this sender" skips saving when the sender is already listed (case-insensitive) and shows SenderNotSaved when the update is not `Updated`, including `NotLoaded`.
- Strings: the four new strings are in `values/` and `values-de/`, and the German texts are correct.
- Criterion evidence: `tasks.py verify and-mail-images` shows nothing recorded as verified yet and #5 skipped, so no criterion is claimed without evidence. The mail.list in-app tests (outside the `mail.read.*` filter the parent ran) still have to pass in `sh scripts/check.sh` for criterion 7.
