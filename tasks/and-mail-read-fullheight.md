---
title: Android read view: mail at full height in one page scroll, linked items below
status: in_progress
owner: dell26/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt,android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt,android/app/src/test/java/ch/sonensei/cockpit/mail/read]
prio: 1
lease_until: 2026-11-10T07:04:01Z
---
## Goal
Reading mail on the phone is tiring (owner report, 2026-11-10): the read view (`MailReadContent`/`Message` in
`MailReadScreen.kt`) is a fixed-height `Column`. Toolbar, previous/next row, headers, attachments, images banner and
reply buttons sit above the body, the linked-items panel (up to 240dp, `heightIn(max = 240.dp)`) sits below it, and the
body only gets `weight(1f)` of what is left. The WebView then scrolls inside that small box, so only a fraction of the
mail is visible at once.

Wanted (owner): the mail as big as possible. The whole read view scrolls as one page, like a mail app: headers,
attachments, banner and reply buttons scroll away at the top, the mail follows at its full content height (no inner
scroll box), and the linked items come after the mail, reached by scrolling to the end.

## Acceptance criteria
- [ ] Below the toolbar and the previous/next row, which stay fixed, the read view is one vertical scroll: headers,
      attachments, images banner, reply buttons, mail body, linked items, in that order. Scrolling the page past the
      headers leaves the mail filling the screen below the fixed bar
- [ ] An HTML mail is shown at its full content height: the WebView does not scroll vertically by itself, and its height
      follows the rendered content, including after images load (cid, "Load images") and after "Load images" reloads the
      mail. Long newsletters and very short mails both work: no clipped bottom, no large empty space
- [ ] Wide newsletters stay fitted to the screen width (wide viewport / overview mode as today), and pinch zoom and
      horizontal panning still work. If full-height layout and zoom conflict, record the trade-off you chose with `assume`
- [ ] A plain-text mail is part of the same page scroll (no nested scroll box) and stays selectable
- [ ] The linked-items panel is no longer height-capped and shows all items at the end of the page; opening an item
      and back still work
- [ ] The WebView lock-down is unchanged: `lockDownForMail`, CSP, `shouldInterceptRequest`, `shouldOverrideUrlLoading`
      (with the `data:` pass-through from `and-mail-webview-blank`)
- [ ] Compose/Robolectric tests cover the new structure: one scroll container holding headers, body and links, no
      `heightIn` cap on links, plus the body height logic where it can be tested outside Chromium
- [~] Manually verified on an emulator or device with a long HTML newsletter, a short mail, a plain-text mail and a mail
      with linked items (record what you ran). If no emulator is available, skip with reason; the owner checks on the phone
  - skipped (2026-11-10 07:31+0100 by dell26/agent-1): no Android emulator or device attached to this agent machine (no adb in the sandbox); owner checks on the phone with a long newsletter, a short mail, a plain-text mail and a mail with linked items
- [ ] `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-10 from the owner's report. Android only; the web read view is not part of this task.
Hints, not requirements: a WebView inside a Compose `verticalScroll` needs a definite height. A common way is to
measure the content height (e.g. `WebView.contentHeight * scale` after `onPageFinished` and on content-size changes,
or the `PictureListener`/`onSizeChanged` route) and set the `AndroidView` height from it. JavaScript must stay off, so
no JS bridge for measuring. Keep the scroll position when only the images change (no jump back to the top).
- ASSUMPTION (2026-11-10 07:31+0100 by dell26/agent-1): Body height comes from Chromium's own wrap-content measuring (WebView with WRAP_CONTENT height under an unbounded Compose verticalScroll measures to its rendered content and re-measures on image loads/zoom), not from contentHeight*scale or a PictureListener; MailWebView only keeps its previous height during a same-mail reload (Load images) until onPageFinished so the page does not jump to the top. Zoom trade-off: pinch zoom grows the body height with the zoom factor (page scrolls), horizontal panning stays in the WebView.
- ASSUMPTION (2026-11-10 07:35+0100 by dell26/agent-1): The progress bar and the notice line (task created / action failed) stay fixed above the scrolling page with the toolbar and previous/next row: they are short status feedback about actions in the toolbar and must stay visible while the mail is scrolled.
