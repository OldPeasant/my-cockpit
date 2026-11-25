# Code Review

Date: 2026-11-25

Task: and-mail-body-pan - Mail body: pan diagonally instead of axis-locked scrolling

Scope: round 2 review of the final revision ee4b0774e. Full scope: `git diff 5c923a8e29f4da3647378a4f461384a3badb2d8b..ee4b0774e -- android` (MailBodyPan.kt new, MailBodyView.kt, MailBodyPanTest.kt). The focus is the delta since round 1: fix commit ee4b0774e ("fling velocity from finger deltas, no fling after hold") and the test-writer's commit a5461b65c. This was a static review. Gradle was not run because a GradleWorkerMain process was already running. The working tree was clean.

Reviewed revision: ee4b0774efd0fe2f8f2fb3ff5df53231ccc81116

Requirements:
- tasks/and-mail-body-pan.md (goal, acceptance criteria 1-5, recorded assumptions)
- reviews/and-mail-body-pan/review.md (round 1, findings CR-1 to CR-4)
- reviews/and-mail-body-pan/fixes.md
- AGENTS.md project notes (conventions, lint)

Git range:
- Branch: main
- HEAD: ee4b0774efd0fe2f8f2fb3ff5df53231ccc81116
- Base/start: 5c923a8e29f4da3647378a4f461384a3badb2d8b
- Commits reviewed: 4 code/test commits (c181305fc implementation, 60d9f88ab round 1 review, a5461b65c tests, ee4b0774e fixes) plus task-board commits (assume/verify/heartbeat) that touch only `tasks/`

Summary: 0 P0, 0 P1, 0 P2, 0 P3 (open findings in the final revision)

## Dispositions of the round 1 findings

- **CR-1 (P1) | Vertical release velocity measured in coordinates that move with the page: fixed, verified.**
  The new `FingerVelocity` (`MailBodyPan.kt:71-87`) starts at `Offset.Zero` at the down time. It feeds the tracker the running sum of `positionChange()` deltas, and `mailBodyPan` uses it at `:123`, `:132`, `:138` and `:154`. Compose computes `position` and `previousPosition` of a change in the node's current local coordinates, so their difference is the finger's own movement on screen and does not depend on how far the body has moved with the page. The sum is therefore the finger's path, and the vertical velocity no longer goes to zero when the body scrolls along with the finger. The unit test `the release velocity follows the finger's own movement, not its position on the moving body` (`MailBodyPanTest.kt:122`) checks both axes from deltas alone. As fixes.md says, Robolectric cannot reproduce layout moving under the finger. Confirming this on a device remains part of criterion 5, which is skipped with a reason.
- **CR-2 (P2) | Up event not added to the velocity tracker: fixed, verified.**
  The up change is now added before the loop breaks (`MailBodyPan.kt:132`). If the finger holds still before lifting, the gap to the last movement sample exceeds the tracker's stop threshold, so the velocity is zero and `flingBoth` launches no animation on either axis. The unit test `dragging, holding still and lifting gives no fling` (`:134`) asserts `Velocity.Zero`. The Compose UI test `a drag-hold-lift on the body leaves the page and the body where the finger left them` (`:143`) asserts that `scrollX` and the page scroll do not change over 2 s after release. According to fixes.md, this test failed with the fix removed (scrollX 827) and passes with it.
- **CR-3 (P2) | No check that taps and two-finger gestures still reach the WebView: resolved by tests.**
  The test-writer's commit a5461b65c adds tests that record, after the body's handlers, whether each event was consumed. They are `a tap on the body is not consumed, so the WebView gets the link click` (`:319`), `a move below the touch slop is not consumed and scrolls nothing` (`:331`), `a drag past the touch slop is consumed from then on, up to the release` (`:348`), `a second finger before the slop leaves the pinch to the WebView` (`:364`) and `a second finger after the slop keeps the one-finger drag going` (`:386`). Consumed changes are what make the AndroidView interop cancel the WebView's gesture, so these tests cover what CR-3 asked for. No production change was needed.
- **CR-4 (P3) | Touching outside the body does not stop a running horizontal fling: accepted, deferred.**
  The code is unchanged. The decision is recorded as an assumption in tasks/and-mail-body-pan.md (2026-11-25 08:41). The impact is limited because the horizontal decay is short and stops at the content edges. Round 1 offered accepting this behavior as an option.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Regression check of the delta

- The fix changes only the velocity bookkeeping. Slop detection still sums `positionChange()` into `moved`. The drag split, consumption, nested pre and post scroll, and the fling launch condition (`dragging && lastUp != null`) are unchanged.
- The up sample is added before `lastUp`/`consume` and only for the tracked pointer. A tap or a below-slop gesture still starts no fling (`dragging` is false). The pinch path (break before the up) is unaffected.
- `flingBoth` still skips an axis whose velocity is exactly zero. After drag, hold and lift, neither the horizontal decay nor `dispatchPreFling`/`dispatchPostFling` runs, so the page's scroll state is left alone.
- MailBodyView.kt and MailReadScreen.kt are unchanged since round 1, and the header/attachment and TalkBack paths are untouched.
- The new tests sit in the task's `touches` (mail/read test package) and use fictitious data. The fix adds no new dependencies or logging.

## Review conclusion

No material issues found.

CR-1 and CR-2 are fixed correctly, CR-3 is covered by tests, and CR-4 is deferred as a recorded assumption. Criterion 5 (real device or emulator: diagonal drag, fling speed on both axes, link tap, pinch-zoom) is still skipped with a reason in the task file. It is the only remaining place where the CR-1 fix can be confirmed on real input.
