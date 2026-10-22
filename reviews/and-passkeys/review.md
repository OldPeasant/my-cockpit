# Code Review

Date: 2026-10-22

Task: and-passkeys - Android passkeys (passkey login via Credential Manager, registration from settings)

Scope: re-review of the final code. Task commits between base 870413dd0 and b1daad478: f29f54305 (implementation), 0948e3615 (follow-up task deploy-assetlinks), a334707fa (earlier review report), b1daad478 (fixes for CR-2..CR-5). Committed changes only. This report supersedes the earlier review of this task (findings CR-1..CR-5); see "Status of previous findings".

Scope notes:
- The supplied range `870413dd0..b1daad478` also contains the and-review task's commits (bcb2b04c3..6b0273eee, already done and reviewed under `reviews/and-review/`). Those files (`android/.../review/*`, `strings_review.xml`, `reviews/and-review/*`, `tasks/and-review.md`) are not part of this task and were not reviewed. The task diff reviewed is `git diff 1726672a3..b1daad478` (1726672a3 = merge base with main for these commits).
- During the review the local branch was rebased: HEAD is now ee040f481 with the same four task commits (`git range-diff` shows them identical; `git diff b1daad478 ee040f481 -- android` is empty), so the findings apply unchanged to ee040f481.
- The working tree has uncommitted test edits (`InAppPasskeyTest.kt`, `PasskeyViewModelsTest.kt`), presumably from a test-writer run in progress; they were not reviewed.

Requirements:
- tasks/and-passkeys.md acceptance criteria and recorded assumptions (settings list and add passkeys only; Digital Asset Links left to deploy-assetlinks)
- SPEC.md §4.1 (Android login: password+TOTP or passkey via Credential Manager, tokens in Keystore-backed storage), §9.2 item 1
- api/openapi.yaml `/api/v1/auth/passkeys`, `/authentication/options`, `/token`, `/registration/options`, `/registration` operations and the `Passkey*` schemas
- docs/ARCHITECTURE.md (passkey environment variables row), AGENTS.md project notes (touches, lint, logging, assumptions)
- tasks/deploy-assetlinks.md (follow-up for CR-1)

Git range:
- Branch: main
- HEAD: b1daad4784f3d51d1445822c229554cd9f3dff03 (rebased meanwhile to ee040f481, patch-identical)
- Base/start: 870413dd0d59738dece5252f2c47b2dca2d97018 (task merge base 1726672a3c9754c2e625b3a690f3aed51a48b7a9)
- Commits reviewed: 4 (f29f54305, 0948e3615, a334707fa, b1daad478)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of previous findings

- **CR-1 (P1) Digital Asset Links not provided or tracked** - Resolved as tracked. `tasks/deploy-assetlinks.md` exists (deps and-passkeys, deploy-passkey-env; criteria cover serving `/.well-known/assetlinks.json`, env configuration and the `android:apk-key-hash` origin), and the assumption is recorded in `tasks/and-passkeys.md`. The end-to-end flow on a real install still depends on that task; see the note on criterion 1 evidence below.
- **CR-2 (P2) Passkey name not limited to 100 characters** - Fixed. `PasskeyForm.NAME_MAX = 100`; `onNameChange` truncates (`PasskeySettingsViewModel.kt:84`) and `canSubmit` checks the trimmed length (`:33`), so no over-long name reaches the authenticator. The default name is already capped (`AuthRepository.DEVICE_NAME_MAX`). Covered by `a passkey name is capped at the server's 100 characters`.
- **CR-3 (P3) 400 from the token endpoint showed "Fill in every field"** - Fixed. `PasskeyRepository.kt:279` maps 400 to `LoginResult.PasskeyFailed`; the test expectation in `token answers map to login results` was changed accordingly (intended behavior change, not a weakened test).
- **CR-4 (P3) Late list answer could overwrite a newly added passkey** - Fixed. `load()` cancels the running `loadJob` (`PasskeySettingsViewModel.kt:71`); a successful registration cancels a running load and starts a fresh one (`:97-102`). Retrofit/`CompletableDeferred` resumption after cancellation throws, so the stale answer never reaches `state.update`. Covered by `a list that answers after a registration does not hide the new passkey` and `a new load replaces a running one, so an older answer never wins`.
- **CR-5 (P3) Settings column did not scroll** - Fixed. `SettingsScreen.kt:22` uses `fillMaxSize().verticalScroll(...)`; `verticalScroll` keeps the minimum height from `fillMaxSize`, so the `weight(1f)` placeholder fills free space when content fits and shrinks to zero otherwise, keeping logout reachable by scrolling. No automated scroll test; the change is two lines and reasoned correctly in `fixes.md`. The file lies outside the task's `touches` (as did the original 2-line insertion of the passkey section, which criterion 1 requires); no other open task touches it.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-6 | After a failed list load, a successful registration shows only the new passkey as the list**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/auth/PasskeySettingsViewModel.kt:97-102`
    - Category: Correctness
    - Problem: if the initial `list()` failed (`passkeys == null`, `loadFailed == true`, `loadJob` completed), `submit` sees `stale == false`, sets `passkeys = emptyList() + result.passkey` and does not reload. The section then renders a one-entry list (the new passkey) next to "Your passkeys could not be loaded. Retry", although the user may have several passkeys.
    - Why it matters: a list that looks complete but is not; recoverable through Retry and the failure text stays visible, hence low priority.
    - Suggested fix: also reload after success when the list was never loaded or the last load failed (e.g. `val stale = loadJob?.isActive == true || it.passkeys == null || it.loadFailed`), and add a view model test for that path.

## Notes for the parent worker

- Contract check: request paths, methods and bodies match api/openapi.yaml (options calls keep `publicKey` as raw JSON via `PasskeyCeremonyApi`; `PasskeyTokenRequest` and `PasskeyRegistrationRequest` use the generated models; 400/401/403/429 on `/token`, 400/403/429 on `/registration/options` and 400/409 on `/registration` are all mapped; `Retry-After` is honored on login). `deviceName` is capped at 100 and the TOTP is trimmed; an over-long TOTP gets a 400 before the authenticator runs, mapped to "code is wrong", which is acceptable.
- Security/logging: no logging added; `PasskeyForm.toString` omits the code (tested); tokens are stored through the same `PersistentTokenStore` path as the password login, off the main thread. `androidx.credentials` and `credentials-play-services-auth` were already in the build (minSdk 29), so no dependency or ARCHITECTURE update is needed.
- Tests (criterion 2): the committed tests cover Credential Manager exception/response mapping (`PasskeyCredentialsTest`), repository flows against MockWebServer with real Retrofit/kotlinx serialization, both view models, the Compose screens (`PasskeyScreensTest`) and the real Hilt app on Robolectric (`InAppPasskeyTest`). Gradle could not be run in this review sandbox, so the suite result was not independently re-run; `fixes.md` reports `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` passing (183 tests) and `sh scripts/check.sh` passing.
- Evidence: `python tasks.py verify and-passkeys` currently shows both criteria unrecorded. For criterion 1, the unit/Robolectric tests use a fake or absent authenticator; a real passkey login/registration needs a device and the assetlinks/origin setup of deploy-assetlinks. Unless it is actually run on a device against a configured server, record criterion 1 as `--skip` with that reason (the assumption already exists) rather than citing the unit tests as proof of the end-to-end flow.
- Minor, not a finding: on 429 the settings form reuses the login message ("Sign-in is paused for a moment") and does not block resubmission for `Retry-After` seconds; a repeated tap only yields another 429.

## Review conclusion

No material issues found. CR-1 is tracked by deploy-assetlinks plus a recorded assumption, and CR-2..CR-5 are fixed and covered by tests. CR-6 is an optional low-priority improvement. Criterion evidence still has to be recorded as described above.
