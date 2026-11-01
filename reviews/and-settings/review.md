# Code Review

Date: 2026-11-01

Task: and-settings - Android: Settings screen (server, notifications, capture queue, app version)

Scope: `git diff 6397eb4b5..HEAD -- android` (the single and-settings implementation commit on top of the task base)

Requirements:
- tasks/and-settings.md acceptance criteria and recorded assumptions
- AGENTS.md project notes (conventions: no secrets in logs, i18n en/de, lint)
- Parent instructions (focus: correctness, lifecycle/coroutines, security, a11y, i18n, architecture)
- Existing code: core/network/ServerUrl.kt, push/PushRegistrar.kt, push/UnifiedPushConnector.kt, capture/queue/CaptureSync.kt, capture/queue/CaptureQueue.kt, capture/queue/CaptureUploader.kt, reminders/ReminderPermissionPrompt.kt, auth/LoginViewModel.kt

Git range:
- Branch: main
- HEAD: 62ba17452085080e0137df14f74a1c6947a413a5 (the parent named 7bc24114d; the checked-out HEAD is 62ba17452, "and-settings: server, notifications, capture and about sections in Android settings")
- Base/start: 6397eb4b54895fc9a3265f312cba5cbcdcf143e0
- Commits reviewed: 1

Note on method: shell access was denied in this review session, so the diff could not be listed with git. The review read the current versions of the main files named by the parent in full (SettingsScreen.kt, SettingsViewModel.kt, SettingsEnvironment.kt, strings_settings.xml en/de, SettingsViewModelTest.kt, SettingsScreenTest.kt) plus the context files listed above. The edits to InAppPasskeyTest/AppStartRoutingTest named in the task notes were not inspected. Gradle was not run; the parent runs `./gradlew ktlintCheck testDebugUnitTest lintDebug`.

Summary: 0 P0, 0 P1, 2 P2, 3 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Saving the server address crashes the app when the settings store cannot be written**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/settings/SettingsViewModel.kt:142`
    - Category: Correctness
    - Problem: `saveServer()` calls `serverUrls.setServerUrl(form.address)` inside `viewModelScope.launch` without a try/catch. `ServerUrlRepository.setServerUrl` (core/network/ServerUrl.kt:126) calls `dataStore.edit`, which throws `IOException` when the preferences file cannot be written (for example, full storage). `readablePrefs()` only guards reads. The exception is not caught in `viewModelScope`, so it reaches the default handler and crashes the process. The `saving = true` form state never resets.
    - Why it matters: the review checklist requires that unavailable or full storage must not break a save. Here it kills the app from a settings dialog.
    - Suggested fix: catch non-cancellation exceptions around `setServerUrl` in `saveServer`. Reset `saving` to false and show a "could not be saved, try again" message in the dialog (new string in en and de). Add a ViewModel test where `setServerUrl` throws.

- [ ] **CR-2 | The distributor switch sequence is untested**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/settings/SettingsEnvironment.kt:95`
    - Category: Tests
    - Problem: `AndroidSettingsEnvironment.useDistributor` holds the riskiest new logic. For the same distributor it only re-registers. For a different one it runs `deleteOnServer`, `clear`, `saveDistributor` and `register` in that order. The order matters: `saveDistributor` must come after `clear`, because the library's unregister can drop the saved distributor, and a new instance id must be used. No test covers it. `SettingsViewModelTest` replaces the whole environment with `FakeEnvironment`, and no test exists for `AndroidSettingsEnvironment`.
    - Why it matters: a regression in this order could leave the server pushing to an old endpoint, or leave the device with no saved distributor. No current test would catch either.
    - Suggested fix: add a Robolectric test of `AndroidSettingsEnvironment`. Build `PushRegistrar` through its internal constructor with a fake `UnifiedPushConnector`, `PushInstanceStore` and a mocked `PushApi`. Assert: a DELETE for the old instance, unregister of the old instance, the new distributor saved, and a register with a different instance id. Also assert that choosing the current distributor only registers. If the static `UnifiedPush` calls make this impractical, move them behind the existing `UnifiedPushConnector` seam.

## P3 Low

- [ ] **CR-3 | Overlapping user loads can show a stale result**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/settings/SettingsViewModel.kt:99`
    - Category: Correctness
    - Problem: `loadUser()` launches a new request every time it is called: at init, on Retry, and after a valid `saveServer`. The previous job is not cancelled and results carry no request identity. A slower, older request that finishes last overwrites the newer result. Example: the init request is still pending against an unreachable path while the user saves a same-origin address with a new path prefix. The new request answers `Known`, then the old one ends and sets `Unknown`.
    - Why it matters: the screen shows "cannot be reached" (or an old name) after a successful reload. The impact is low (Retry fixes it), but this is the out-of-order pattern the project review checklist calls out.
    - Suggested fix: keep the `Job` of the running load and cancel it before starting a new one, or ignore results from a load that is no longer the latest.

- [ ] **CR-4 | Distributor switches are not serialized**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/settings/SettingsViewModel.kt:164`
    - Category: Correctness
    - Problem: `chooseDistributor` closes the picker and launches `environment.useDistributor` without tracking it. While the first switch waits on its network DELETE (`PushRegistrar.deleteOnServer`), the user can open the picker again and pick another distributor. Two `useDistributor` runs then interleave on `Dispatchers.IO`. The second run's `clear()` can unregister the instance the first run has just registered. If that instance's endpoint already reached the server through `onNewEndpoint`, nothing deletes it on the server.
    - Why it matters: this needs two quick choices within one network round trip, but it can leave a stale push registration on the server that receives pushes for nothing.
    - Suggested fix: serialize switches, for example with a `Mutex` in `AndroidSettingsEnvironment.useDistributor`. Alternatively, add a `switching` flag in the UI state that disables "Choose push app" until the running switch has finished.

- [ ] **CR-5 | The capture "last attempt failed" detail stays in English in the German UI**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/settings/SettingsScreen.kt:321`
    - Category: Quality (i18n)
    - Problem: `settings_capture_failed` inserts `capture.lastError` unchanged. That value is one of the English constants written by `CaptureUploader` (`OFFLINE = "Server unreachable"`, `NOT_LOGGED_IN`, `"Rejected by the server (HTTP ...)"`, etc., capture/queue/CaptureUploader.kt:185-212). The German screen therefore shows, for example, "Letzter Versuch fehlgeschlagen: Server unreachable".
    - Why it matters: the screen mixes languages, against the "all new UI strings in English and German" intent. The raw value comes from the earlier queue design, but this task is the one that shows it.
    - Suggested fix: map the known `CaptureUploader` constants (and the HTTP-code pattern) to localized strings in the screen, with the raw text as a fallback. Or record an assumption that the raw text is accepted for now and leave localization to a follow-up task.

## Review conclusion

Material issues found; fix before completing the task.

What is in place: the acceptance criteria are implemented. There are server and user sections that reuse `setServerUrl`; notification, exact alarm and distributor states, each with a system intent or the picker; queue count, last result and Sync now through `CaptureSyncScheduler.schedule`; version name and code plus the `download/android` link; passkeys and logout are kept and the placeholder is gone; and en/de strings for all new resources. ViewModel and Compose tests cover the main paths. No secrets or user data are logged (exceptions are swallowed without logging). Intents are guarded against `ActivityNotFoundException`. Status icons are decorative and their text carries the meaning. Sync and server errors use polite live regions.

Fix CR-1 (crash on a failed write) and CR-2 (untested push switch) before `done`. CR-3 to CR-5 are low-impact and may be fixed or recorded as accepted.
