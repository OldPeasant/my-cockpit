---
title: Mail body: pan diagonally instead of axis-locked scrolling
status: in_progress
owner: dell26/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/read,android/app/src/test/java/ch/sonensei/cockpit/mail/read]
prio: 2
lease_until: 2026-11-25T07:54:22Z
---
## Goal
Reading a wide HTML mail on Android, the body moves the way the finger moves, diagonally included. Today the
WebView of the body (`mail/read/MailBodyView.kt`, full content height) sits in the read screen's `verticalScroll`
(`mail/read/MailReadScreen.kt`): whoever claims a drag first gets all of it. A drag with the slightest vertical
part scrolls the page only; only an exactly horizontal one reaches the WebView, and then it moves sideways only.
Reported by the owner: "I have to move my finger EXACTLY horizontally".

Intended approach: one drag handler for the read screen's scrolling content splits each drag delta: the vertical part
scrolls the page (its `ScrollState`), the horizontal part scrolls the WebView (`scrollBy`, clamped to its horizontal
range). A fling keeps the release velocity of both axes. Subject, header and attachments keep scrolling away with the
body (do not give the WebView its own vertical scrolling).

## Acceptance criteria
- [ ] A diagonal drag over a mail body wider than the screen moves the body horizontally and the page vertically in
      the same gesture; a mostly vertical drag keeps its small horizontal part, a mostly horizontal one its small
      vertical part (no axis lock)
- [ ] A fling after a diagonal drag continues in both axes and stops at the edges (horizontal: the WebView's content
      range; vertical: the page's)
- [ ] Unchanged: tapping a link opens it, pinch-zoom of the body works (and the panning respects the zoomed width), a
      mail no wider than the screen scrolls only vertically, dragging outside the body (header, attachments) scrolls
      the page as before, TalkBack scrolling still works
- [ ] Tests: the delta splitting / clamping logic as unit tests; a Compose UI test (Robolectric or instrumented) that a
      diagonal swipe on a wide body changes both the page scroll and the WebView's `scrollX`
- [~] Verified on a real device or emulator with a wide HTML mail (e.g. a newsletter with a fixed 800px table):
      diagonal drag, fling, link tap, pinch-zoom. Record device/emulator and what was checked with `verify`
  - skipped (2026-11-25 08:28+0100 by dell26/agent-1): no Android device or emulator on this machine (adb not installed, SDK dir not accessible); real-device check of diagonal drag, fling, link tap and pinch-zoom with a wide newsletter needs a human

## Notes
- Android only; web is not affected (the browser pans the mail iframe freely).
- ASSUMPTION (2026-11-25 08:28+0100 by dell26/agent-1): Diagonal pan lives in MailBodyView (Modifier.mailBodyPan on the WebView's AndroidView) and reaches the page through nested scrolling, instead of a handler on MailReadScreen's Column with its ScrollState: self-contained, no change to the body lambda signature; drags outside the body keep the page's own verticalScroll
- ASSUMPTION (2026-11-25 08:28+0100 by dell26/agent-1): A second finger only hands the gesture to the WebView (pinch-zoom) when it lands before the first finger passed the touch slop; once a pan has started it stays a pan until all fingers lift
