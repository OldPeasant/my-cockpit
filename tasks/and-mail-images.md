---
title: Android shows mail images (inline and on request)
status: in_progress
owner: dell26/agent-1
deps: [and-user-settings]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/read,android/app/src/test/java/ch/sonensei/cockpit/mail/read,android/app/src/main/res/values/strings_mail.xml,android/app/src/main/res/values-de/strings_mail.xml]
prio: 1
lease_until: 2026-11-08T09:03:55Z
---
## Goal
Android shows no images in mail at all: the WebView blocks network loads and its CSP only allows `data:`
(`MailBodyView.kt:23-62`), so even inline `cid:` images (which the server rewrites to the authenticated attachment
download) never load, and there is no "Load images" action (only a "N images blocked" line, `MailReadScreen.kt:296`).
Decided behavior (owner, 2026-11-08): remote images blocked by default, same as web.

## Acceptance criteria
- [ ] Inline `cid:` images render: the WebView fetches only URLs of the configured cockpit server (attachment download and `/api/v1/mail/image-proxy`) through `shouldInterceptRequest` with the app's auth (OkHttp client with the access token); every other request stays blocked and JavaScript stays off
- [ ] The "N images blocked" line becomes a banner with "Load images" (reloads the message with `remoteImages=PROXY`) and "Always for this sender" (adds to `mailImageAllowedSenders`)
- [ ] `mailImagePolicy = allow` or an allowed sender loads remote images through the proxy without asking
- [ ] The CSP of the mail document allows images only from the server origin and `data:`; a test asserts a request to another host is blocked
- [~] Manually verified on an emulator or device against a running server with a mail containing a `cid:` image and a remote image (record what you ran); if no emulator is available, skip with reason
  - skipped (2026-11-08 09:47+0100 by dell26/agent-1): No emulator/device or adb available to this agent (no adb/emulator on PATH, Android SDK dir outside the sandbox); WebView image loading through shouldInterceptRequest verified only by Robolectric unit tests
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 09:44+0100 by dell26/agent-1): The mail page is loaded on the placeholder origin https://server.invalid (Retrofit's stand-in for the configured server); the CSP allows img-src only from it and data:, and shouldInterceptRequest maps it to the configured server via the authenticated OkHttp client. The WebView never sees the real host or the token; anything not intercepted cannot resolve (.invalid).
- ASSUMPTION (2026-11-08 09:44+0100 by dell26/agent-1): WebView blockNetworkLoads/blockNetworkImage are now false (Chromium may block before shouldInterceptRequest and Blink skips image requests otherwise); safety comes from shouldInterceptRequest answering every request (allowed server image paths via OkHttp, everything else an empty 403) plus the CSP. Only image/* answers are passed to the page.
- ASSUMPTION (2026-11-08 09:44+0100 by dell26/agent-1): Allowed-sender check is case-insensitive on the first From address (as web); for an allowed sender the mail is loaded with BLOCK first and re-fetched with PROXY (sender only known from the mail), policy=allow fetches PROXY directly. 'Always for this sender' loads the images even if saving the setting fails (shows a notice). Added optional remoteImages query param to MailApi.message (mail/list, outside touches, one line).
- ASSUMPTION (2026-11-08 09:47+0100 by dell26/agent-1): Manual emulator check (criterion 5) skipped: no emulator here. Real-WebView behaviour (shouldInterceptRequest firing for img loads with blockNetworkLoads=false, CSP with placeholder origin) is assumed from Android WebView docs and needs a human check on a device.
