# Fix Report

Date: 2026-10-21

Task: and-share-target - Android share target

Scope: current task. These are the findings CR-1, CR-2 and CR-3 from `reviews/and-share-target/review.md`, applied on top of the task commits caaed3b1 and 0ee297f1 (base 66b8f182). Changes stay inside the task's touches (`capture/share` and its test folder) plus `res/values/strings_share.xml`. No capture/queue code was changed.

## Fixed findings

### CR-1 - System Back during save finishes the activity and silently discards the in-flight capture

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/capture/share/CaptureSheet.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/capture/share/CaptureSheetTest.kt`

Problem:
While a save was running, Cancel and taps on the scrim were disabled, but system Back still reached the default `ComponentActivity` handling. That finished the activity, which cancelled `viewModelScope` and the attachment copy, so nothing was queued and the user saw no feedback.

Fix:
`CaptureSheet` now registers `BackHandler(enabled = state.saving) {}`. Back is consumed while a save runs, the same way the disabled Cancel button behaves. Once the save fails (or before it starts), Back works normally again.

Two Robolectric Compose tests send Back through the host activity's `OnBackPressedDispatcher`:
- `system back while saving is consumed and does not close the sheet`: the activity does not finish and cancel is not called.
- `system back closes the sheet again once saving failed`: after `saving` turns into an error, Back finishes the activity.

Both tests were checked to fail with the handler disabled (2 of 11 failed), then pass with the fix.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*' --offline` - Pass

### CR-2 - Shared text is not checked against the server's 100,000-character notes limit

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/capture/share/ShareViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/capture/share/CaptureSheet.kt`
- `android/app/src/main/res/values/strings_share.xml`
- `android/app/src/test/java/ch/sonensei/cockpit/capture/share/ShareViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/capture/share/CaptureSheetTest.kt`

Problem:
Notes from a share had no length limit. The server rejects notes over `TaskService.MAX_NOTES_LENGTH = 100_000` with a 400, and the uploader treats that as a permanent rejection. The sheet still said "Saved to Inbox", but the item never arrived.

Fix:
- Added `ShareViewModel.MAX_NOTES_LENGTH = 100_000`, with KDoc that points to the server constant.
- Added a new `ShareError.NOTES_TOO_LONG`. `save()` refuses notes longer than the limit before it queues anything, and the sheet stays open and editable.
- Editing the notes clears the error, using the existing behavior.
- Added the plural resource `share_error_notes_too_long`.

New tests:
- notes exactly at the limit are queued;
- notes one character over the limit are refused with `NOTES_TOO_LONG`, nothing is queued, and the save succeeds after the notes are shortened;
- the sheet test `every error has its message` now covers the new error.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*' --offline` - Pass

### CR-3 - Limits are duplicated as literals in the error strings

Files changed:
- `android/app/src/main/res/values/strings_share.xml`
- `android/app/src/main/java/ch/sonensei/cockpit/capture/share/CaptureSheet.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/capture/share/CaptureSheetTest.kt`

Problem:
"50" and "100,000" were written into the error strings as literals, separate from `CaptureQueue.MAX_ATTACHMENTS` and `MAX_TEXT_LENGTH`.

Fix:
- `share_error_too_many_files` now uses `%1$d`.
- The two "characters" messages became `<plurals>` with `%1$,d`, which formats the number with the locale's grouping, so English still shows "100,000". A plain string failed Android lint `PluralsCandidate`, so lint was fixed this way rather than suppressed.
- `ShareError.message()` is now a `@Composable` that passes `CaptureQueue.MAX_ATTACHMENTS`, `CaptureQueue.MAX_TEXT_LENGTH` and `ShareViewModel.MAX_NOTES_LENGTH` to the resources.
- The message test also asserts the constant values, so a changed limit shows up in the test.

Validation:
- `cd android && ./gradlew ktlintCheck lintDebug --offline` - Pass

## Not fixed

None.

The reviewer also suggested an optional extra for CR-2: adding the notes check to `CaptureQueue.capture`, so that every capture path enforces it. This was not done because the parent worker said to leave the capture/queue code alone (it is outside this task's touches). The share path is now guarded.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*' lintDebug --offline` - Pass. The share suites had 0 failures and 0 errors: CaptureSheetTest 11, ShareViewModelTest 22, ShareActivityTest 11, ShareIntentParserTest 14, ShareManifestTest 4.

Full test suite:
- Covered by `.taskcheck` below: unit tests of the server, web and Android.

Lint/type-check/build:
- `ktlintCheck` and `lintDebug` - Pass. Both are part of the focused command above.

`.taskcheck`:
- `sh scripts/check.sh` - Pass ("all checks passed")

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
