---
title: Mail body: pan diagonally instead of axis-locked scrolling
status: done
owner: dell26/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/read,android/app/src/test/java/ch/sonensei/cockpit/mail/read]
prio: 2
lease_until: 
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
- [x] A diagonal drag over a mail body wider than the screen moves the body horizontally and the page vertically in
      the same gesture; a mostly vertical drag keeps its small horizontal part, a mostly horizontal one its small
      vertical part (no axis lock)
  - verified (2026-11-25 08:44+0100 by dell26/agent-1): android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*' (Robolectric) -> 377 passed; MailBodyPanTest diagonal swipe on wide body moves WebView scrollX and page scroll in one gesture; mostly vertical/horizontal drags keep the small other part (unit + UI tests)
- [x] A fling after a diagonal drag continues in both axes and stops at the edges (horizontal: the WebView's content
      range; vertical: the page's)
  - verified (2026-11-25 08:44+0100 by dell26/agent-1): same Robolectric run -> MailBodyPanTest fast diagonal fling continues in both axes past the drag, stops exactly at the right edge (maxPanX), no movement past top/left, drag-hold-lift gives no fling; vertical fling speed on a moving body only unit-tested (FingerVelocity), device check skipped (#5)
- [x] Unchanged: tapping a link opens it, pinch-zoom of the body works (and the panning respects the zoomed width), a
      mail no wider than the screen scrolls only vertically, dragging outside the body (header, attachments) scrolls
      the page as before, TalkBack scrolling still works
  - verified (2026-11-25 08:44+0100 by dell26/agent-1): same Robolectric run -> taps and sub-slop moves not consumed by the pan handler (reach the WebView), second finger before slop left to WebView (pinch), maxPanX = zoomed range minus extent, narrow mail scrolls only vertically, header drag scrolls page only; page verticalScroll/semantics unchanged (TalkBack). Real link open/pinch/TalkBack on device not run: see #5 skip
- [x] Tests: the delta splitting / clamping logic as unit tests; a Compose UI test (Robolectric or instrumented) that a
      diagonal swipe on a wide body changes both the page scroll and the WebView's `scrollX`
  - verified (2026-11-25 08:44+0100 by dell26/agent-1): MailBodyPanTest: HorizontalPan split/clamp/carry + FingerVelocity unit tests and Robolectric Compose UI test 'a diagonal swipe on a wide body changes both the page scroll and the WebView's scrollX' -> 30/30 passed; ktlintCheck + lintDebug clean
- [~] Verified on a real device or emulator with a wide HTML mail (e.g. a newsletter with a fixed 800px table):
      diagonal drag, fling, link tap, pinch-zoom. Record device/emulator and what was checked with `verify`
  - skipped (2026-11-25 08:28+0100 by dell26/agent-1): no Android device or emulator on this machine (adb not installed, SDK dir not accessible); real-device check of diagonal drag, fling, link tap and pinch-zoom with a wide newsletter needs a human

## Notes
- Android only; web is not affected (the browser pans the mail iframe freely).
- ASSUMPTION (2026-11-25 08:28+0100 by dell26/agent-1): Diagonal pan lives in MailBodyView (Modifier.mailBodyPan on the WebView's AndroidView) and reaches the page through nested scrolling, instead of a handler on MailReadScreen's Column with its ScrollState: self-contained, no change to the body lambda signature; drags outside the body keep the page's own verticalScroll
- ASSUMPTION (2026-11-25 08:28+0100 by dell26/agent-1): A second finger only hands the gesture to the WebView (pinch-zoom) when it lands before the first finger passed the touch slop; once a pan has started it stays a pan until all fingers lift
- ASSUMPTION (2026-11-25 08:41+0100 by dell26/agent-1): CR-4 deferred: a touch outside the body (header) stops the page fling but not a running horizontal body fling; it decays on its own, minor
- done by dell26/agent-1: `sh scripts/check.sh` passed in 318s on 9e8350148; criteria verified 4/5, skipped #5
