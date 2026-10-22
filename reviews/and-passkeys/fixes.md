# Fix Report

Date: 2026-10-22

Task: and-passkeys - Android passkey login via Credential Manager, registration from settings

Scope: current task; findings CR-2 to CR-5 of `reviews/and-passkeys/review.md` (base 870413dd0, implementation 50b7b5b98). CR-1 is handled by the follow-up task `deploy-assetlinks` and was not touched.

## Fixed findings

### CR-2 - Passkey name is not limited to the server's 100 characters

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/auth/PasskeySettingsViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/PasskeyViewModelsTest.kt`

Problem:
The add-passkey name field accepted any length, and `PasskeyForm.canSubmit` checked only non-blank. The server (`PasskeyRegistrationRequest.name` maxLength 100) rejects longer names only in the finish call, after the authenticator already stored the credential, leaving an orphaned passkey on the device.

Fix:
Added `PasskeyForm.NAME_MAX = 100`. `onNameChange` (the settings name field's only input path) truncates to it, and `canSubmit` also requires the trimmed name to fit, so no over-long name can reach `register`. The default device name was already capped at 100 (`AuthRepository.DEVICE_NAME_MAX`).

Validation:
- New test `a passkey name is capped at the server's 100 characters` (exactly 100 accepted and submittable; 101 typed is cut to 100 and that is what gets registered; a 101-char form cannot submit; surrounding blanks don't count) - Pass

### CR-3 - A 400 from the passkey token endpoint shows the password form's message

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/auth/PasskeyRepository.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/PasskeyRepositoryTest.kt`

Problem:
Passkey login mapped HTTP 400 from the token endpoint to `LoginResult.InvalidRequest` ("Fill in every field ..."), which the user can't act on in the passkey flow.

Fix:
400 now maps to `LoginResult.PasskeyFailed`. The expectation in `token answers map to login results` was changed from `InvalidRequest` to `PasskeyFailed`. This is the intended behavior change, not a weaker test.

Validation:
- `PasskeyRepositoryTest` `token answers map to login results` - Pass

### CR-4 - Passkey list load can overwrite a passkey just added

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/auth/PasskeySettingsViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/PasskeyViewModelsTest.kt`

Problem:
`load()` started a new coroutine on every call, with no ordering. A list answer that arrived after a successful registration replaced the list that already held the new passkey. Repeated Retry taps also raced each other.

Fix:
The view model keeps the running `loadJob`. `load()` cancels it before it starts a new one. After a successful registration, a running load is cancelled and the new passkey is appended. If a load was still running at that point, a fresh `load()` starts, so older passkeys that the cancelled initial load would have shown still appear. Only cancelling would have left just the new passkey in the list.

The test-writer's existing test `a list that answers after a registration does not hide the new passkey` was adjusted minimally to match this. Its second `list()` call now returns the server's current list (older passkey plus new one). It still checks that the late stale answer (`emptyList()`) never overwrites the list, and it now also checks that a fresh load happened.

Validation:
- `a list that answers after a registration does not hide the new passkey` (adjusted) - Pass
- New test `a new load replaces a running one, so an older answer never wins` - Pass

### CR-5 - Settings column does not scroll as the passkey list grows

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/settings/SettingsScreen.kt`

Problem:
The settings `Column` did not scroll. With many passkeys, a small screen or a large font scale, the logout button was pushed off screen.

Fix:
Small change: the column modifier is now `modifier.fillMaxSize().verticalScroll(rememberScrollState())`. `fillMaxSize` keeps the previous full-height layout: the `weight(1f)` placeholder still fills the free space when the content fits, and gives up its space first when it doesn't. Logout can then be reached by scrolling. The file is outside the task's `touches`, which is why the edit is kept small.

Validation:
- `./gradlew ktlintCheck lintDebug` and full `testDebugUnitTest` (includes `LogoutButtonTest`) - Pass. No automated scroll test was added. No instrumented or emulator run was done; the layout behavior was checked by reasoning about Compose constraints (verticalScroll keeps the minimum height from fillMaxSize).

## Not fixed

### CR-1 - Digital Asset Links

Status:
Out of scope

Reason:
The parent worker said to leave it alone. It is covered by the follow-up task `deploy-assetlinks` and a recorded assumption.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*' lintDebug` - Pass (183 auth tests, 0 failures; ktlintFormat reformatted the new test's `!!` chains to the project's continuation style; lintDebug clean)

Full test suite:
- `cd android && ./gradlew testDebugUnitTest` - Pass (0 failures, 0 errors)

Lint/type-check/build:
- ktlintCheck and lintDebug - Pass (no suppressions added)

`.taskcheck`:
- `sh scripts/check.sh` - Pass ("all checks passed": server, web, android)

The test-writer's uncommitted test changes (`PasskeyRepositoryTest.kt`, `PasskeyScreensTest.kt`, `PasskeyViewModelsTest.kt`, new `InAppPasskeyTest.kt`) were kept. The only changes to them are the two expectation adjustments described under CR-3 and CR-4 and the added tests.

Remaining unresolved findings:
0 (CR-1 out of scope by decision of the parent worker)

## Final status

All validated findings fixed and validation passed.
