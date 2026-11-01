# Code Review

Date: 2026-11-01

Task: and-settings - Android: Settings screen (server, notifications, capture queue, app version)

Scope: `git diff e2395de9e..HEAD -- android` (final code of the task: the implementation commit, the test-writer tests and the fixes for the earlier findings CR-1 to CR-5)

Requirements:
- tasks/and-settings.md acceptance criteria 1-7 and the recorded assumptions (`python tasks.py verify and-settings`)
- AGENTS.md project notes (no secrets or user data in logs, en/de strings, lint is part of the contract, stay inside `touches`)
- The previous review of this task (CR-1 to CR-5) and reviews/and-settings/fixes.md
- Parent instructions: verify the fixes, look for regressions caused by them and for remaining gaps against the criteria; Gradle is run by the parent
- Existing code used for context: core/network/ServerUrl.kt (`setServerUrl`), push/PushRegistrar.kt, capture/queue/CaptureUploader.kt, ui/CockpitShell.kt (navigation), auth/PasskeySettings.kt, auth/CockpitRoot.kt

Git range:
- Branch: main
- HEAD: d732b66fd68f122b69e302abda6d5201377a5146
- Base/start: e2395de9ee7581d82e3e80de3a79a87841439c98
- Commits reviewed: 3 (e9ec42ace implementation, 2380357ab test-writer tests, d732b66fd fixes)
- Uncommitted changes: none at the start of the review

Note on method: the full diff was read, and every changed file under `android/` was read in full: SettingsScreen.kt, SettingsViewModel.kt, SettingsEnvironment.kt, CaptureFailure.kt, strings_settings.xml (en, de), and the tests AndroidSettingsEnvironmentTest, CaptureFailureTest, SettingsScreenTest, SettingsServerChangeTest, SettingsStringsTest, SettingsViewModelTest, plus the edits to AppStartRoutingTest and InAppPasskeyTest. Gradle was not run in this review; the parent runs `./gradlew ktlintCheck lintDebug createDebugUnitTestCoverageReport`.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Verification of the earlier findings

- CR-1 (crash when the store cannot be written): fixed. `saveServer()` (SettingsViewModel.kt:146-170) catches non-cancellation exceptions, rethrows `CancellationException`, resets `saving` and sets `saveFailed` without logging the address. Editing the address or saving again clears the flag (lines 141, 149). The `saveFailed` message and the validation error cannot show at the same time, because each save clears both before it runs. The dialog shows the new string in both languages. Covered by the SettingsViewModelTest case "a store that cannot be written keeps the form open with a save error, saving again works" and the SettingsScreenTest case "an address that could not be stored says so and can be saved again". The logout that `setServerUrl` performs before the write on an origin change is existing repository behavior and outside this task.
- CR-2 (distributor switch untested): fixed by AndroidSettingsEnvironmentTest. It uses the real UnifiedPush library on Robolectric's package manager. It checks the order delete, clear, register, and which distributor is saved at each step. It also checks that the distributor in use only re-registers and that a first choice saves the distributor and registers.
- CR-3 (overlapping user loads): fixed. `loadUser()` cancels the previous `userJob` before it starts a new request (SettingsViewModel.kt:105-126). A cancelled request rethrows the cancellation, so it never writes a result. The regression test "an older user request that fails last does not overwrite the newer answer" covers it.
- CR-4 (switches not serialized): fixed within one screen instance. `useDistributor` runs inside `switching.withLock` (SettingsEnvironment.kt:100-114). The test "a second switch waits until the first one has finished" checks the exact step sequence. See CR-1 below for a remaining gap when the screen is left.
- CR-5 (English failure text in the German UI): fixed. `CaptureFailure.of` covers every text that `CaptureUploader` writes: the four constants, `serverError(code)` and the rejection text. I checked all `Outcome.Retry`/`Outcome.Rejected` sources in CaptureUploader.kt. Unknown texts fall back to the stored value. The uploader's own tests pin the exact texts (CaptureUploaderTest, CaptureUploaderFailureTest), so a wording change there would fail a test. The en/de strings have matching names and placeholders, which SettingsStringsTest checks.
- Lint changes from the fixer: the `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` intent is guarded with `SDK_INT >= S`. The button only renders when `exactAlarmsSettingExists` is true, which uses the same condition. `toUri()` replaces `Uri.parse`. `Uri.fromParts` is still used, so the `android.net.Uri` import is still needed.

## Acceptance criteria check

1. Server section: shows the address and the user from `/auth/me`, with Loading, Unknown and Retry states. Changes go through `ServerUrlRepository.setServerUrl`. SettingsServerChangeTest checks against the real repository that another origin logs out and the same origin keeps the session. Met.
2. Notifications section: shows the notification permission, exact alarms and the distributor state, each with a system intent or the picker, and a "find a push app" link when no distributor is installed. No server settings were invented. The missing toggles are recorded as an assumption. Met.
3. Capture section: shows the pending count (plural), the last result and "Sync now" through `CaptureSyncScheduler.schedule()`. Met.
4. About section: shows `Version {name} ({code})`, and the download button resolves `download/android` against the server URL, including a path prefix. Met.
5. Passkeys and logout are still rendered by `SettingsScreen`, and the placeholder is gone, which a test checks. Met.
6. en and de strings are complete and match, which SettingsStringsTest checks. Met.
7. ViewModel, environment, Compose UI and string tests exist for every section. Whether the Gradle run passes is evidence for the parent to record.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Leaving Settings during a distributor switch cancels it halfway**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/settings/SettingsViewModel.kt:184`
    - Category: Correctness
    - Problem: `chooseDistributor` runs `environment.useDistributor` in `viewModelScope`. Settings is a "More" screen pushed on top of the More list (ui/CockpitShell.kt:201-207). Pressing Back pops it, the ViewModel is cleared, and the running switch is cancelled. `PushRegistrar.deleteOnServer()` sets `deleted = instance` before it sends the DELETE (push/PushRegistrar.kt:117-120). A cancellation during or right after that DELETE skips `clear()`, `saveDistributor` and `register()`. The old distributor and instance stay in place, but their endpoint updates are now dropped (`instance == deleted`). The server may already have forgotten the endpoint. The user's choice is lost without any message. The DELETE can take many seconds on a slow network, so the window is real. Push stays down until the next app start, when `deleted` resets and `start()` registers again. Also, `AndroidSettingsEnvironment` is unscoped (the `@Binds` at SettingsEnvironment.kt:133 has no `@Singleton`), so the CR-4 `Mutex` only serializes switches within one ViewModel.
    - Why it matters: push for new mail can stop for the rest of the session after a normal navigation, and the screen then shows the old distributor as if nothing had happened. The impact is limited because the next app start repairs it and periodic sync still runs.
    - Suggested fix: run the switch sequence so that leaving the screen cannot cut it short. For example, wrap the body of `useDistributor` in `withContext(NonCancellable + Dispatchers.IO)`, or launch it in an application-lifetime scope. Make the lock process-wide, either by annotating `AndroidSettingsEnvironment` with `@Singleton` or by moving the lock into the singleton `PushRegistrar`. Add a test that cancels the caller during `deleteOnServer` and checks that clear, save and register still happen.

## Review conclusion

No material issues found.

All five earlier findings are fixed correctly. The fixes did not introduce any regressions I could find. All seven acceptance criteria are implemented and covered by tests. Criterion 7 still needs the parent's Gradle evidence. CR-1 is low impact and can be fixed now or recorded as accepted. No secrets, user data or email contents are logged: all new catch blocks swallow without logging. Intents are guarded against `ActivityNotFoundException`. The auth test edits outside `touches` only add `performScrollTo()` and are recorded in the task assumptions.
