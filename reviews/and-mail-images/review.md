# Code Review

Date: 2026-11-08

Task: and-mail-images - Android shows mail images (inline and on request)

Scope: re-review of the final code, `git diff 76a46b9ff..a441e181d`: the task commits 6d93a6246 (rebased 982f7e8b0), b2627d776 (rebased 6ee3de4a5) and a441e181d (the test-writer's tests plus an explicit 200 status for image answers). The first review was at 6ee3de4a5; this one covers the whole final diff, and the delta since then (`git diff b2627d776..a441e181d`) was read in full. The working tree was clean.

Requirements:
- tasks/and-mail-images.md acceptance criteria 1-7 and the recorded assumptions (placeholder origin https://server.invalid, blockNetworkLoads/blockNetworkImage false, allowed-sender check, optional `remoteImages` query param in MailApi)
- docs/PARITY.md item 5 (remote images blocked by default, per-sender allowlist from synced settings, `cid:` images always shown)
- SPEC.md §7.3 / §4.1 (sanitized HTML, remote images blocked by default, "load images", proxied through the server)
- AGENTS.md project notes (lint, strings EN/DE, no secrets in logs, fictitious test data)
- Server URL formats: server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadService.kt (`attachmentUrl`, `proxyUrl`, `MESSAGES_PATH`, `IMAGE_PROXY_PATH`), mail/sanitize/HtmlMailSanitizer.kt, mail/api/RemoteImageFetcher.kt

Git range:
- Branch: main
- HEAD: a441e181d0cf3fa5881e32bca2acf8633db0117d
- Base/start: 76a46b9ff9de8a22a4210eadb3abb00e76f80af9
- Commits reviewed: 3 code commits (6d93a6246, b2627d776, a441e181d), plus board-only commits (assume/verify) in the range

Summary: 0 P0, 0 P1, 0 P2, 0 P3

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

Status of the earlier finding:
- CR-1 (P1, Tests: nothing tested request interception, the banner or the image loading logic) is resolved in a441e181d:
    - `MailImagesTest` tests `isServerImage` against the server's real formats (`/api/v1/mail/image-proxy?url=...`, `/api/v1/mail/messages/{uuid}/attachments/{partId}`). It also tests refused variants: other or look-alike hosts, http, another port, user info, other paths, dot segments and encoded slashes, and a malformed port. `MailImageFetcher` is tested against MockWebServer through `ServerUrlInterceptor`: the configured server is hit, WebView Cookie/Referer are not forwarded, non-image, failed and unreachable answers are refused, only GET is fetched, and other hosts never reach the server. The WebView client answering through the fetcher is tested too.
    - `MailWebViewTest` parses the CSP directives and asserts that a request to another host is blocked with a strict OkHttpClient mock (criterion 4). It also checks that every request is blocked without a fetcher and that links to the stand-in origin are not opened.
    - `MailReadImagesViewModelTest` covers: policy allow, allowed sender (case-insensitive, moved mail), a failed proxied load falling back to the blocked copy, load images success and failure, the busy guard, late results after close or reopen, always-for-this-sender saving, already allowed, not saved for each `SettingsUpdateResult`, settings not loaded, images failing after saving, and no sender address.
    - `MailReadScreenTest` and `MailReadImagesGermanTest` cover: the banner, its buttons and the plural strings in English and German, the hidden "always" button without an address, disabled buttons while busy, the two notices, and the route's load images flow removing the banner.
    - `MailReadRepositoryTest` covers: the `remoteImages=PROXY` query only when wanted, the `fromAddress` mapping, and failed proxied loads.
    - The tests assert behavior (requests seen by MockWebServer, state, UI text) rather than internals. All test data is fictitious.

Production change in a441e181d (checked):
- `MailImages.kt:63` now builds image answers with `WebResourceResponse(mime, null, 200, "OK", emptyMap(), stream)`. Before, the 3-argument constructor left `statusCode` at 0 in the reviewed code. The 6-argument form matches `blockedResponse()`, with the status in a named constant. No other production code changed since the first review.

Checked on the final code, no finding:
- WebView security:
    - Every subresource request goes through `shouldInterceptRequest`, which never returns null. Page navigations are always cancelled, and links to the placeholder host are not opened externally.
    - JavaScript, file and content access, DOM storage and multiple windows stay off. The CSP is `default-src 'none'; style-src 'unsafe-inline'; img-src https://server.invalid data:` on the base origin `https://server.invalid/`.
    - Only GET requests to the image proxy path or a strict attachment path, over https on the stand-in host and port 443, are fetched. The token and the real host are added only by the app's OkHttpClient. Only successful `image/*` answers reach the page, and other answers are closed.
- Server conformity:
    - `attachmentUrl(..., inline = true)` and `proxyUrl` produce URLs that `isServerImage` accepts. `ServerUrlInterceptor` keeps the encoded query and adds a server path prefix.
    - A mail cannot inject its own server-relative image URLs. jsoup drops relative `src` values and unknown schemes, absolute stand-in URLs count as remote images (blocked or proxied), and CSS `url()` is stripped. The server's proxy follows redirects itself, so the device never contacts the remote host.
- View model: the new paths use the session guard (`updateFor`) and the `busy` guard of `run`. A failed proxied re-fetch keeps the blocked copy with its banner.
- Strings: the four new strings are in `values/` and `values-de/`, and the German texts are correct.
- Criterion evidence: criterion 5 (manual emulator check) is recorded as skipped with a reason. The remaining criteria are for the parent to record with the validation it ran. Criterion 7 still needs `sh scripts/check.sh`, which also runs the mail.list in-app tests outside the `mail.read.*` filter.
