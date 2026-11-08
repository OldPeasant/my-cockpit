# Fix Report

Date: 2026-11-08

Task: and-capture-improve - Android capture: syntax preview, save and add another, attachments

Scope: current task; findings CR-1, CR-2 and CR-3 (all P3) from `reviews/and-capture-improve/review.md`, on top of task commits 4068cec04 and 0eee5e56d (base 6f9fedef6).

## Fixed findings

### CR-1 - `\s`/`\S` in CaptureSyntax are Unicode-aware on the Android runtime but not on the server JVM

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/capture/share/CaptureSyntax.kt`

Problem:
`TOKEN` and `WHITESPACE` used `\s`/`\S`. Android's ICU regex counts Unicode spaces such as U+00A0 as `\s`. The server's `java.util.regex` does not. So the preview could show a chip for a token the server leaves in the text.

Fix:
`TOKEN` is now `(^|[ \t\n\u000B\f\r])([#@!^])([^ \t\n\u000B\f\r]+)` and `WHITESPACE` is `[ \t\n\u000B\f\r]+`. These are the same characters as the server's OpenJDK `\s`, so both runtimes split tokens the same way. `RelativeDueDates` (`capture/queue`, outside this task's touches) has the same pattern from before this task. I did not change it.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.*'` - all CaptureSyntax tests pass. The tests run on OpenJDK, where `\s` is already ASCII, so they cannot show the ICU difference. The fix is checked by reading the code.

### CR-2 - Tapping anywhere on an attachment chip removes it immediately

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/capture/share/CaptureSheet.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/capture/share/CaptureSheetCaptureTest.kt` (only to follow the moved enabled state)

Problem:
`FileChip` was an `InputChip` whose `onClick` removed the file. A tap on the file name dropped the attachment with no undo, and TalkBack offered the whole chip as a single "activate" action.

Fix:
`FileChip` is now a `Surface` (rounded, outlined) with no click action. It shows the file icon, the name and, only when removal is possible, an `IconButton` (48 dp, tag `share:remove-file`, `enabled` false while saving). The button carries the existing "Remove %1$s" content description and is the only control that removes the file. Two assertions in `CaptureSheetCaptureTest` checked the enabled state of the `share:file` chip, which no longer has an action. "attachments cannot be removed while saving" now checks the remove button instead. In "without a remove action...", the chip-enabled assertion was dropped, because the remaining assertion (no close icon) already covers it.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.*'` - attachment tests pass (removal through the close button, disabled while saving, no close icon without a remove action).
- `./gradlew lintDebug` - Pass

### CR-3 - No "Saved" toast after process death once "save and add another" has been used

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/capture/share/ShareActivity.kt`

Problem:
`SavedAndClearedToast` kept `shown` in `rememberSaveable`, but `ShareUiState.savedAndCleared` lives only in the ViewModel. After process death `shown` came back as N while the counter restarted at 0, so the next N "save and add another" saves showed no confirmation.

Fix:
`shown` now uses plain `remember`, starting from the current `savedAndCleared`. A configuration change keeps the ViewModel, so the recreated composable starts at the same count and does not repeat the toast. After process death both values start at 0 together. The KDoc explains why the counter is not saveable.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.*'` - both `SavedAndClearedToast` tests pass ("each capture saved with add another is confirmed...", "a sheet shown again with an earlier count does not repeat the confirmation").

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat ktlintCheck` - Pass
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.*'` - 331 tests, 2 failed. Both failures are the existing WIP tests from the test-writer and have nothing to do with these fixes. `CaptureSheetCaptureTest` "after save and add another the cleared title takes the focus" and `ShareAddAnotherActivityTest` "save and add another queues the share, keeps the sheet open and cleared, then a plain save closes it" both fail on `assertTextEquals("")` for `share:text`. The field is empty and focused, but its semantics `Text` holds the placeholder "What is it?". The test assertion is wrong, not the production code.
- `cd android && ./gradlew lintDebug` - Pass

Full test suite:
- Not run (only the capture tests, as the parent agent asked)

Lint/type-check/build:
- ktlintCheck and lintDebug pass

`.taskcheck`:
- Not run (the parent worker runs it after the test-writer fixes the WIP tests)

Remaining unresolved findings:
0

Note for the test-writer: `CaptureSheetCaptureTest` "a tap on the attachment chip removes it too" still describes the old behavior that CR-2 removes. It passes only by chance, because `performClick` taps the center of the narrow chip, which falls on the 48 dp remove button. It should be turned into "a tap on the chip body does not remove it", for example by clicking the file name text. A regression test for CR-1 would need the ICU regex engine, so it cannot be written as a JVM unit test.

## Final status

All validated findings fixed. The capture test run still fails only on the two existing WIP test assertions owned by the test-writer.
