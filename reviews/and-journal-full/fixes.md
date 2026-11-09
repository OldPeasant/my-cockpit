# Fix Report

Date: 2026-11-08

Task: and-journal-full - Android journal: other days, mood, tags, month calendar, on this day

Scope: findings CR-1 to CR-4 of `reviews/and-journal-full/review.md`, applied to the code at 3de61858 (base 97b3c6e6). Changes stay inside `android/app/src/{main,test}/java/ch/sonensei/cockpit/journal` and the journal string files.

## Fixed findings

### CR-1 - A photo picked while another day is opening lands in the wrong day or is dropped

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/journal/JournalViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/journal/JournalScreen.kt`
- `android/app/src/main/res/values/strings_journal.xml`
- `android/app/src/main/res/values-de/strings_journal.xml`
- `android/app/src/test/java/ch/sonensei/cockpit/journal/JournalDaysRaceViewModelTest.kt`

Problem:
`addPhoto` did not check for a running navigation, and the upload coroutine never captured the day it started for. A photo started while `openDay` waited for its save was inserted into (and saved with) the next day, or dropped without a message if that day was still loading.

Fix:
`addPhoto` takes the `date` the photo belongs to (default: the shown day). `JournalRoute.upload` captures the shown day before `NotePhotoFiles.read` runs on IO, so the read window is covered as well. `addPhoto` refuses when that day is no longer shown or a navigation is active. When the upload finishes, the result is only inserted if the same day is still shown. In both cases the new message `PHOTO_DAY_CHANGED` tells the user (EN and DE strings added, mapped in `MessageBanner`). The existing test `a photo started while the day is saved for navigation does not land in the next day` now passes without changes. Two regression tests were added: a photo picked during a navigation is not uploaded and is reported, and a photo read for a day that is no longer shown is not added to the shown day.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.*'` - Pass

### CR-2 - The out-of-order and navigation-guard paths have no tests

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/journal/JournalViewModel.kt`

Problem:
`JournalDaysRaceViewModelTest.kt` already covers this finding. Running the whole file exposed a production bug: `a stale on-this-day answer for the same day does not replace the newer one` failed. `loadOnThisDay` only guarded against a different date, so if the same day was opened twice (today, yesterday, today), the late answer of the first request overwrote the newer one.

Fix:
The on-this-day request now runs in a tracked `onThisDayJob`. Each new request cancels the previous one, so only the newest answer is applied. The test was left unchanged.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.*'` - Pass (whole race file green)

### CR-3 - A month load answered after a save drops that save's calendar mark

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/journal/JournalViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/journal/JournalDaysRaceViewModelTest.kt`

Problem:
`loadMonth` replaced `monthDays` with the server answer. If that answer was computed before an entry was saved, the save's mark disappeared.

Fix:
This mirrors `savedSince` in `web/src/app/features/journal/journal-page.ts`. Each successful save records its entry in `savedSince`, which is cleared whenever a month request starts. When the answer arrives, the saved entries of that month are merged over it, with their current mood. A regression test was added: an entry saved while the month loads stays marked when the month answers without it.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.*'` - Pass

### CR-4 - Calendar day labels and the heading use the process default locale

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/journal/JournalScreen.kt`

Problem:
The day heading and each calendar day's `contentDescription` used `dateFormat`, which was bound to the default locale when the class loaded.

Fix:
Both now use `dateFormat.withLocale(LocalLocale.current.platformLocale)`, like the month title and weekday names.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.*'` - Pass

### Pre-existing failing screen tests (found while validating)

Files changed:
- `android/app/src/test/java/ch/sonensei/cockpit/journal/JournalDaysScreenStatesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/journal/JournalGermanTest.kt`

Problem:
Two tests from commit 3de61858 failed on the unmodified code too. This was checked by running them against the `HEAD` version of `JournalScreen.kt`.
- `leaving the tags field for the text tidies the tags`: the test rendered a frozen state (`tagsText = "sport"`). The text field therefore reset the typed value and reported `setTags("sport")` again on focus loss. In the app, the view model updates `tagsText` with each change, so this does not happen there.
- `the day bar, mood, calendar and earlier years speak German`: the test first scrolled to the earlier years at the bottom, then opened the calendar. The calendar appears at the top of the scrolled column, out of view, and the test asserted it was displayed without scrolling.

Fix:
The tests were corrected, not weakened. The tags test now uses a state that follows `setTags`, as the view model does, and keeps the same assertion (`["tags sport,,  sea", "tidy"]`). The German test scrolls to the month title and the day label before asserting that they are displayed. Both still assert the same texts.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.*'` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.*'` - Pass (all journal tests, including the whole `JournalDaysRaceViewModelTest`)

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` - BUILD SUCCESSFUL, 0 test failures

Lint/type-check/build:
- ktlintCheck and lintDebug are part of the command above - Pass

`.taskcheck`:
- `sh scripts/check.sh` - Fail in this environment, for a reason outside this task: the server's Testcontainers tests cannot find a Docker environment (`Could not find a valid Docker environment`, 424 of 3068 server tests). The Android part of the check passes (see above). No server code was changed.

Remaining unresolved findings:
0

# Fix Report - round 2

Date: 2026-11-08

Task: and-journal-full - Android journal: other days, mood, tags, month calendar, on this day

Scope: P3 findings CR-1 and CR-2 of the re-review in `reviews/and-journal-full/review.md` (HEAD 6d24e961), applied to the code at 74856fac. Changes stay inside `android/app/src/{main,test}/java/ch/sonensei/cockpit/journal` and the journal string files.

## Fixed findings (round 2)

### CR-1 - A camera photo returned after process death is added to today, not to the day it was taken for

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/journal/JournalScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/journal/JournalPhotoAfterRestoreRouteTest.kt` (new)

Problem:
Confirmed in the code: `cameraUri` and `insertAt` survive process death in `rememberSaveable`, but `JournalRoute.upload` read the day from the view model when the result arrived. A recreated view model starts on today, so the photo was accepted for today.

Fix:
`JournalRoute` remembers the day the camera or gallery was opened for in a new `rememberSaveable` `photoDay` (ISO date string), set when the photo source is launched. `upload` passes that day (falling back to the shown day) to `addPhoto`, which already refuses with `PHOTO_DAY_CHANGED` when the shown day differs. The view model was not changed for this finding.

Regression tests (`JournalPhotoAfterRestoreRouteTest`, Robolectric, a stand-in `ActivityResultRegistry` answers the camera, `StateRestorationTester.emulateSavedInstanceStateRestore()` plus a new view model stand in for the process death):
- a photo taken for yesterday is not uploaded or saved after the screen is recreated on today, and `PHOTO_DAY_CHANGED` is shown; checked to fail with the old line (`val date = viewModel.uiState.value.date`) and pass with the fix;
- a photo taken for yesterday while yesterday is still shown is uploaded and inserted there.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.JournalPhotoAfterRestoreRouteTest'` with the fix temporarily removed - Fail (1 of 2, as expected)
- `cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.*'` - Pass

### CR-2 - "No entries from this day in earlier years" shows while the earlier years are still loading

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/journal/JournalViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/journal/JournalScreen.kt`
- `android/app/src/main/res/values/strings_journal.xml`
- `android/app/src/main/res/values-de/strings_journal.xml`
- `android/app/src/test/java/ch/sonensei/cockpit/journal/JournalLateAnswersViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/journal/JournalDaysScreenStatesTest.kt`

Problem:
Confirmed: `JournalUiState` had no loading flag for the earlier years, so the "none" text showed as soon as the entry loaded, before `onThisDay` answered.

Fix:
New `JournalUiState.onThisDayLoading` (default `false`, so existing states are unchanged). `load()` sets it for the day it asks for; `loadOnThisDay` clears it on success and on failure, only when the answer is for the shown day (stale requests are cancelled through `onThisDayJob` and guarded by the date). While it is set, `OnThisDay` shows a small `CircularProgressIndicator` (tag `journal-on-this-day-loading`, content description from the new string `journal_on_this_day_loading`: "Loading earlier years…" / "Frühere Jahre werden geladen…") instead of the "none" or failure text.

Regression tests:
- view model: loading stays set after the entry loaded until the earlier years answer; a failed answer clears it and marks failure; a late answer of a day left does not clear the loading of the day opened after it;
- screen: while loading, progress is shown and neither the "none" nor the failure text.

Validation:
- `cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.*'` - Pass

## Not fixed (round 2)

None.

## Validation summary (round 2)

Focused tests:
- `cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.*'` - Pass

Full test suite:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest lintDebug` - BUILD SUCCESSFUL; 4582 unit tests, 0 failures, 0 skipped

Lint/type-check/build:
- ktlintCheck and lintDebug are part of the command above - Pass

`.taskcheck`:
- Not run in this round: the parent asked not to run the server check (no Docker here). The Android part of the check passes (above).

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed. The Android check passes. `.taskcheck` fails here only because Docker is unavailable for the server's Testcontainers tests.

Round 2: the P3 findings CR-1 and CR-2 of the re-review are fixed with regression tests, and `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest lintDebug` passes. The code changed after the re-review, so test-writer and code-reviewer need to run again on the final code.
