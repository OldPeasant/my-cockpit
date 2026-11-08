# Fix Report

Date: 2026-11-08

Task: and-review-flow - Android weekly review: start, steps, resume, reflection

Scope: current task. Findings CR-1 to CR-5 from `reviews/and-review-flow/review.md`, plus the failing test `ReviewWizardRouteInAppTest` "the Mail link of get clear opens Mail, and back returns to the wizard" that the test-writer reported (parent worker's list).

## Fixed findings

### CR-1 — A slower overview load can overwrite a newer move or completion

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewViewModelRaceTest.kt` (new)

Problem:
`reload()` applied whatever the server answered, also when a move, start or completion had been saved after the load started. The wizard could jump back a step, a later move then got a false CONFLICT, or a completed review was shown as open again.

Fix:
Added a generation counter (`saves`). Successful start, move and complete increment it. `reload()` records it before the request. If it changed by the time the answer arrives, the load keeps the view model's `current` and `daysSinceLastReview` and applies only the rest (projects). A load started after the save still applies the server's newer state.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*'` — Pass. New tests: overview suspended (CompletableDeferred) until after a successful move keeps the moved step and still applies the loaded projects. The same after a completion does not reopen the review. A load started after the move applies the server's state.

### CR-2 — The reflection draft (and the open wizard) is lost on any bottom-tab switch

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewDrafts.kt` (new)
- `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewViewModelRaceTest.kt` (new)

Problem:
The draft and the `wizard` flag lived only in the back-stack-scoped view model. A tab switch pops the Review entry, so coming back showed the overview and lost the typed reflection. This contradicts recorded assumption #3.

Fix:
Added a Hilt `@Singleton ReviewDrafts` holder that keeps the reflection per review id and the id of the review whose wizard is open. The view model writes it in `setReflection`, `start` and `closeWizard`, and restores both when the overview load returns that review. The draft entry is cleared after the reflection is saved to the journal. Everything for the review is cleared on successful completion. A draft is never shown for another review id. The holder is a constructor parameter with a default, so existing tests that construct the view model directly are unchanged. As before, the draft does not survive app death (assumption #3 still says so).

Validation:
- New tests: a second view model sharing the holder shows the open wizard and the draft. A closed wizard stays closed but the draft comes back. A completed review leaves no draft or open wizard. A draft of another review id is not shown. — Pass
- `ReviewWizardRouteInAppTest`, `ReviewRouteInAppTest` (Hilt injects the singleton) — Pass

### CR-3 — Quick-add can capture the same text twice

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewWizard.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewViewModelRaceTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewWizardTest.kt`

Problem:
`capture()` had no guard while the Room insert was running. A double tap, or IME Done followed by the button, queued duplicate inbox items. Text typed during the insert was cleared.

Fix:
Added `ReviewUiState.capturing`. `capture()` returns early while it is set, and it is reset in `finally`. The "Add to inbox" button is disabled while capturing. The field is cleared only if it still holds the captured text.

Validation:
- New tests: a second capture while the first is stored calls `CaptureQueue.capture` once. Text typed during the insert stays in the field. The button is disabled while `capturing`. — Pass

### CR-4 — A journal save committed on the server but reported as offline is appended twice on retry

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/review/ReflectionSaver.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReflectionSaverTest.kt`

Problem:
After an offline-reported but committed PUT, the retry loaded the entry (already ending with the section) and appended it again.

Fix:
Before each write attempt, `ReflectionSaver.save` returns `SAVED` without writing when the trimmed body already ends with the exact section (`## <heading>\n\n<text>`). This also covers a conflict whose current entry already holds it.

Validation:
- New `ReflectionSaverTest` cases: a retry after a committed save writes nothing and reports SAVED. An entry whose last section is a different reflection still gets the new one appended. A conflict whose current entry already holds the reflection counts as saved after one write. — Pass

### CR-5 — Step-track segments are below the minimum touch target

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewWizard.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewWizardTest.kt`

Problem:
Each clickable segment was 24dp tall.

Fix:
The clickable segment Box is now 48dp tall. The visible bar inside it is still 4dp/8dp. The hint text below lost its 12dp top padding, because the taller track already leaves that space. Width stays one eighth of the row, as the track has 8 segments. The full-size step menu remains the alternative.

The taller track pushed the second event of `ReviewWizardTest` "the calendar step lists the events by day..." below the fold of the lazy list in Robolectric's small default window. The test now scrolls the list to that event before asserting it. The assertions themselves are unchanged. This is a viewport adjustment, not a weakening.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.review.ReviewWizardTest'` — Pass

### TEST-1 — Mail link of Get clear: back returns to Dashboard instead of the wizard

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt`

Problem:
The step-1 "Open Mail" link went through the shell's `open()`. For a bottom-bar destination, that calls `navigateToTopLevel`, which pops the More stack including Review, so system back went to the Dashboard. docs/PARITY.md decision 6 says back returns to where the user came from.

Fix:
In `CockpitNavHost`, the open callback given to the Review destination now pushes the target on top of the current entry (`navigate(route) { launchSingleTop = true }`) instead of switching tabs. Every other destination still uses `open()`. The steps 4-7 list links behave as before, since More destinations were already pushed. The change is 1 statement in the shell, plus a comment and the line wrapping ktlint required. `ReviewScreen` and `ReviewWizard` are unchanged. Side effect: the bottom bar highlights Mail while it lies on top of Review, and Mail shown this way has no app-bar Back arrow. System back works. Adding an arrow would widen the shell change.

Validation:
- `ReviewWizardRouteInAppTest` (all 4 tests, including the formerly failing one, test unchanged) — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*'` — Pass

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — BUILD SUCCESSFUL (5m 23s)

Lint/type-check/build:
- ktlintCheck and lintDebug are part of the command above — Pass. No new strings were added, so `res/values` and `res/values-de` are unchanged.

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("all checks passed"; the web bundle-budget warning is unrelated and pre-existing)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
