# Fix Report

Date: 2026-11-25

Task: and-mail-body-pan - Mail body: pan diagonally instead of axis-locked scrolling

Scope: current task, findings CR-1, CR-2 and CR-4 from reviews/and-mail-body-pan/review.md (CR-3 is already covered by the test-writer's tap/pinch tests in MailBodyPanTest.kt and was not redone, as instructed by the parent worker)

## Fixed findings

### CR-1 - Vertical release velocity is measured in coordinates that move with the page scroll

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyPan.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailBodyPanTest.kt`

Problem:
The VelocityTracker was fed `down.position` / `change.position`, local to the body. The body scrolls with the page during the drag, so on a device the finger's local y barely changes and the vertical release velocity is close to zero.

Fix:
New small internal class `FingerVelocity` wraps the VelocityTracker: it starts at `Offset.Zero` at the down time and adds the running sum of `positionChange()` deltas for each sample, i.e. the finger's own movement, independent of where the body has moved. `mailBodyPan` now uses it instead of feeding local positions. Unit test "the release velocity follows the finger's own movement, not its position on the moving body" checks that deltas alone (constant local position) give the expected velocity in both axes. Robolectric cannot reproduce the layout-moves-under-the-finger effect itself (as the review notes), so the on-device confirmation stays with criterion 5.

Validation:
- `android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'` - Pass

### CR-2 - The up event is not added to the velocity tracker, so lifting after holding still can still fling

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyPan.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailBodyPanTest.kt`

Problem:
When the tracked finger lifted, the loop broke before adding the up sample, so a drag, hold still, lift gesture reported the earlier drag speed and flung.

Fix:
The up change is added to `FingerVelocity` (same accumulated-delta scheme) before the velocity is computed. Two regression tests: the unit test "dragging, holding still and lifting gives no fling" (velocity is exactly zero after a 300 ms pause before the up), and the Compose UI test "a drag-hold-lift on the body leaves the page and the body where the finger left them" (scrollX and page scroll stay at the dragged distance and do not change over 2 s). Checked that the UI test fails without the fix: with the up-sample line temporarily removed it failed with `scrollX 827` (a fling); the line was restored afterwards.

Validation:
- `android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.MailBodyPanTest'` with the fix temporarily removed - Fail (expected, 1 of 30 failed)
- `android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'` with the fix - Pass

## Not fixed

### CR-3 - No automated check that taps and two-finger gestures still reach the WebView

Status:
Invalid (already resolved)

Reason:
Already covered by the test-writer's tests in MailBodyPanTest.kt ("a tap on the body is not consumed, so the WebView gets the link click", "a move below the touch slop is not consumed and scrolls nothing", "a second finger before the slop leaves the pinch to the WebView", "a drag past the touch slop is consumed from then on, up to the release"); no change needed by the fixer.

### CR-4 - Touching outside the body does not stop a running horizontal fling

Status:
Out of scope (deferred, by parent-worker decision)

Reason:
P3, limited impact (the horizontal decay is short). The parent worker decided not to fix it in this task; the review itself offers accepting the behavior as an option. The parent worker may record it as an assumption.

## Validation summary

Focused tests:
- `android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'` - Pass (BUILD SUCCESSFUL; MailBodyPanTest 30 tests, all passing)

Full test suite:
- Not run (the parent worker runs `.taskcheck`)

Lint/type-check/build:
- `android/gradlew -p android ktlintFormat ktlintCheck` - Pass (no formatting changes needed)

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
1 (CR-4, deferred by decision)

## Final status

All validated findings fixed and validation passed. CR-4 is deferred by the parent worker's decision; the on-device confirmation of the vertical fling speed (CR-1) remains part of criterion 5.
