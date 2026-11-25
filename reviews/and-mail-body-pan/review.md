# Code Review

Date: 2026-11-25

Task: and-mail-body-pan - Mail body: pan diagonally instead of axis-locked scrolling

Scope: committed changes `git diff 5c923a8e29f4da3647378a4f461384a3badb2d8b..a31a5716c -- android` (MailBodyPan.kt new, MailBodyView.kt, MailBodyPanTest.kt). Static review only (Gradle not run, as instructed). An uncommitted modification of MailBodyPanTest.kt present in the working tree during the review (presumably the test-writer's) is not part of this review.

Requirements:
- tasks/and-mail-body-pan.md (goal, acceptance criteria 1-5)
- AGENTS.md project notes (conventions, lint)
- MailReadScreen.kt context (body hosted in `Column.verticalScroll`)

Git range:
- Branch: main
- HEAD: a31a5716cb76f8090e02f86c2f97db8bdd398506
- Base/start: 5c923a8e29f4da3647378a4f461384a3badb2d8b (merge-base with origin/main)
- Commits reviewed: 1 (a31a5716c "and-mail-body-pan: pan mail body diagonally, fling both axes")

Summary: 0 P0, 1 P1, 2 P2, 1 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Vertical release velocity is measured in coordinates that move with the page scroll**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyPan.kt:100`, `:114`, `:130`
    - Category: Correctness
    - Problem: The `VelocityTracker` is fed `down.position` / `change.position`, i.e. positions local to the AndroidView. That node sits inside the `verticalScroll` content, and the same gesture scrolls that content by the vertical finger delta (lines 123-127). Compose re-runs measure/layout before handling each touch event and translates every change into the node's current local coordinates, so as the page follows the finger the body moves with it and the local y of the finger stays nearly constant. The tracker then sees almost no vertical movement, and `v.y` passed to `dispatchPostFling` is close to zero (only the residue of one event's layout lag). The horizontal axis is unaffected because panning the WebView does not move the Compose node. On a device, the result would be that a diagonal or vertical fling on the body keeps going sideways but barely or not at all vertically. That breaks criterion 2 ("continues in both axes") and is a regression for plain vertical flings on the body (criterion 3, "scrolls only vertically ... as before"). The Robolectric fling test probably does not catch this: injected events are dispatched as a batch without a layout pass reflecting the scroll between them, and its assertion `pageScroll() > 200f` after a 200 px swipe passes with any non-zero fling. Criterion 5 (device check) is not recorded yet, so nothing has exercised this path on real input.
    - Why it matters: Fling in both axes is a stated acceptance criterion, and vertical flinging over the mail body is the most common gesture on the read screen.
    - Suggested fix: Track velocity from the finger's own movement, independent of the node's position. For example, accumulate `positionChange()` into a running offset (`total += delta; velocity.addPosition(change.uptimeMillis, total)`), starting at `Offset.Zero` for the down event. Another option is to convert positions to root or window coordinates. Then confirm on a device or emulator (criterion 5) that a vertical-only fling on the body travels as far as one on the header.

## P2 Medium

- [ ] **CR-2 | The up event is not added to the velocity tracker, so lifting after holding still can still fling**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyPan.kt:108-114`
    - Category: Correctness
    - Problem: When the tracked pointer lifts, the loop records `lastUp` and breaks before `velocity.addPosition(...)`, so the release sample is never added. Compose's `VelocityTracker` estimates velocity from the samples before the newest added sample, and it only discards older movement when the gap between consecutive samples exceeds about 40 ms. If the user drags, holds the finger still (Android emits no or only jitter MOVE events), and then lifts, the newest sample is still the last movement. The tracker therefore reports the earlier drag speed and an unintended fling starts in both axes. Compose's own `draggable`/`scrollable` add the up change to the tracker for this reason.
    - Why it matters: A deliberate "drag, stop, lift" to place the content would fling it away. Reading a wide mail invites this gesture, especially near the horizontal edges.
    - Suggested fix: Add the up change to the tracker before deciding on a fling (with the same coordinate basis as the CR-1 fix). Optionally, skip the fling below the platform's minimum fling velocity.

- [ ] **CR-3 | No automated check that taps and two-finger gestures still reach the WebView**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailBodyPanTest.kt:135-175`
    - Category: Tests
    - Problem: Criterion 3 depends on the new Initial-pass handler leaving taps (link opening) and pre-slop second-finger gestures (pinch-zoom) unconsumed. The UI tests cover only swipes. Nothing asserts that a tap on the body is delivered to the WebView (down and up, no cancel), or that a second pointer before slop stops the handler from consuming. A regression here, such as consuming on down or lowering the slop check, would silently break links and pinch-zoom. The device check (criterion 5) is also not recorded yet.
    - Why it matters: Link taps and pinch-zoom are the "unchanged" behaviors most at risk from an ancestor pointer handler at `PointerEventPass.Initial`. Without a test, only manual device runs protect them.
    - Suggested fix: In the existing Robolectric setup, make the test WebView record the `MotionEvent` actions it receives in `onTouchEvent`/`dispatchTouchEvent`. Then assert that `performTouchInput { click(...) }` delivers DOWN and UP without CANCEL, and that a two-finger down before slop leaves the events unconsumed (no CANCEL). Also assert that a swipe past slop does produce CANCEL.

## P3 Low

- [ ] **CR-4 | Touching outside the body does not stop a running horizontal fling**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyPan.kt:98`, `:131`
    - Category: Correctness
    - Problem: The fling job is cancelled only by a new down on the body itself (`fling[0]?.cancel()`). A press or drag on the header or attachments preempts the page fling, because the scroll mutex is taken with UserInput priority. The horizontal `animateDecay` keeps panning the WebView until it decays or reaches an edge.
    - Why it matters: This is a small inconsistency: the body keeps sliding sideways while the user is already scrolling the page elsewhere. The impact is limited because the decay is short.
    - Suggested fix: Also cancel the horizontal fling when the page's scroll is taken over, for example from an `onPreScroll` in the body's nested scroll connection, which receives the page's user scrolls only for children. Simpler: accept this behavior and record it as an assumption.

## Review conclusion

Material issues found; fix before completing the task.

The rest of the design checks out on reading. Consuming at `PointerEventPass.Initial` on the outer AndroidView modifier, ahead of the interop filter, sends the WebView a cancel and keeps the parent `verticalScroll` from starting its own drag. The pan is clamped to `computeHorizontalScrollRange - computeHorizontalScrollExtent`, which follows the zoom. The horizontal fling stops at the edges and does nothing for a narrow mail. The vertical part goes through nested pre/post scroll and fling, and the page's scrollable clamps it. The fling job is cancelled on a new down on the body and on leaving composition. The header and attachments, and TalkBack semantics, are untouched by the change. The splitting and clamping logic has focused unit tests.
