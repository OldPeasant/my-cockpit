# Fix Report

Date: 2026-11-01

Task: and-settings - Android: Settings screen (server, notifications, capture queue, app version)

Scope: current task; findings CR-1, CR-3, CR-4, CR-5 from `reviews/and-settings/review.md` as assigned by the parent worker (CR-2 was covered by the test-writer's `AndroidSettingsEnvironmentTest` and was not assigned to the fixer). Changes stay in `android/app/src/main/java/ch/sonensei/cockpit/settings/`, `android/app/src/main/res/values{,-de}/strings_settings.xml` and `android/app/src/test/java/ch/sonensei/cockpit/settings/`.

## Fixed findings

### CR-1 — Saving the server address crashes the app when the settings store cannot be written

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/settings/SettingsViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/settings/SettingsScreen.kt`
- `android/app/src/main/res/values/strings_settings.xml`
- `android/app/src/main/res/values-de/strings_settings.xml`
- `android/app/src/test/java/ch/sonensei/cockpit/settings/SettingsViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/settings/SettingsScreenTest.kt`

Problem:
`saveServer()` called `ServerUrlRepository.setServerUrl` without a try/catch; an IOException from DataStore crashed the process and left `saving = true`.

Fix:
`ServerForm` has a new `saveFailed` flag. `saveServer()` catches non-cancellation exceptions (cancellation is rethrown), resets `saving` to false and sets `saveFailed`; nothing is logged (the address is user data). Editing the address or saving again clears the flag. The dialog shows the new string `settings_server_save_failed` ("The address could not be saved. Try again." / "Die Adresse konnte nicht gespeichert werden. Versuche es erneut.") in the existing error slot (same test tag and polite live region), and marks the field as an error.

Tests added:
- `SettingsViewModelTest`: "a store that cannot be written keeps the form open with a save error, saving again works" (mocked `setServerUrl` throws IOException; form stays open, not saving, `saveFailed`, no user reload; editing clears it; a later successful save closes the form and reloads the user).
- `SettingsScreenTest`: "an address that could not be stored says so and can be saved again".

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.settings.*'` — Pass

### CR-3 — Overlapping user loads can show a stale result

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/settings/SettingsViewModel.kt`

Problem:
Each `loadUser()` launched an independent request; an older request finishing last overwrote the newer answer.

Fix:
The ViewModel keeps the running load in `userJob` and cancels it before launching a new one, so a superseded request can no longer write its result.

Tests:
- The test-writer's existing `SettingsViewModelTest` case "an older user request that fails last does not overwrite the newer answer" failed before the fix and passes now; it was not changed.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.settings.*'` — Pass

### CR-4 — Distributor switches are not serialized

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/settings/SettingsEnvironment.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/settings/AndroidSettingsEnvironmentTest.kt`

Problem:
Two quick distributor choices could run `useDistributor` concurrently and interleave delete/clear/save/register, leaving a stale registration.

Fix:
`AndroidSettingsEnvironment` holds a `Mutex`; `useDistributor` runs its whole sequence inside `switching.withLock { ... }`, so a second switch starts only after the first finished. The switch order itself is unchanged.

Tests added:
- `AndroidSettingsEnvironmentTest`: "a second switch waits until the first one has finished" (the first switch blocks in `deleteOnServer`; a second switch is started and must not take any step while the first is blocked; after release the steps are exactly delete, clear, register at the new distributor, then delete, clear, register for the second switch). Without the lock the second call sees the old distributor still saved and registers at once, which the test detects.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.settings.*'` — Pass

### CR-5 — The capture "last attempt failed" detail stays in English in the German UI

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/settings/CaptureFailure.kt` (new)
- `android/app/src/main/java/ch/sonensei/cockpit/settings/SettingsScreen.kt`
- `android/app/src/main/res/values/strings_settings.xml`
- `android/app/src/main/res/values-de/strings_settings.xml`
- `android/app/src/test/java/ch/sonensei/cockpit/settings/CaptureFailureTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/settings/SettingsScreenTest.kt`

Problem:
The screen inserted `CaptureQueueStatus.lastError` (English constants written by `CaptureUploader`) unchanged into the German text.

Fix:
`CaptureFailure.of(lastError)` recognizes `CaptureUploader.OFFLINE`, `NOT_LOGGED_IN`, `ATTACHMENT_MISSING`, `UNEXPECTED_ERROR` and the patterns "Server error (HTTP n)" and "Rejected by the server (HTTP n)"; anything else is `Other` and shown as stored. The composable `captureFailureText` maps them to six new strings `settings_capture_error_*` in en and de. The English texts equal the uploader's constants, so English output is unchanged. `CaptureUploader` was not changed.

Tests added:
- `CaptureFailureTest` (plain JVM): the four constants, server error and rejection codes, and fallbacks (unknown text, trailing text, non-numeric or overflowing code).
- `SettingsScreenTest`: "the last capture failure is in German on a German phone" ("Letzter Versuch fehlgeschlagen: Vom Server abgelehnt (HTTP 422)") and "an unknown capture failure is shown as stored".
- `SettingsStringsTest` (existing) checks that the new strings exist in both languages with the same placeholders.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.settings.*'` — Pass

### Extra — lintDebug errors in SettingsScreen.kt (not a review finding)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/settings/SettingsScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/settings/SettingsStringsTest.kt`

Problem:
`./gradlew lintDebug` failed with 3 errors from the implementation commit, which blocks the acceptance criterion "`ktlintCheck testDebugUnitTest lintDebug` passes": InlinedApi on `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (min SDK 29) and two UseKtx (`Uri.parse`). Also `ktlintFormat` reformatted two function bodies in the test-writer's `SettingsStringsTest.kt`, which `ktlintCheck` would have flagged.

Fix:
The exact alarm intent is guarded with `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` (same pattern as `reminders/ReminderPermissionPrompt.kt`; the button is only shown on 12+ anyway), and `Uri.parse(x)` became `x.toUri()` (androidx.core, already used elsewhere). `SettingsStringsTest.kt` changes are formatting only.

Validation:
- `./gradlew ktlintCheck lintDebug` — Pass

## Not fixed

None of the assigned findings.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.settings.*'` — Pass (CaptureFailureTest 3, SettingsStringsTest 2, AndroidSettingsEnvironmentTest 11, SettingsScreenTest 21, SettingsServerChangeTest 3, SettingsViewModelTest 17; 57 tests, 0 failures), re-run after the lint fix — Pass

Full test suite:
- Not run (full `testDebugUnitTest` and `sh scripts/check.sh` left to the parent; one combined Gradle run died with "Gradle build daemon disappeared unexpectedly", likely memory pressure on the shared machine, and the split runs then passed)

Lint/type-check/build:
- `cd android && ./gradlew ktlintCheck lintDebug` — Pass

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
