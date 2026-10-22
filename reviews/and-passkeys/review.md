# Code Review

Date: 2026-10-22

Task: and-passkeys - Android passkeys (passkey login via Credential Manager, registration from settings)

Scope: `git diff b876b933c..HEAD` (implementation commit 7dfbb43ed, merged by 741e5dcbd), committed changes only

Requirements:
- tasks/and-passkeys.md acceptance criteria and recorded assumption (settings list and add passkeys only; deletion stays in the web app)
- SPEC.md §4.1 (Android login: password+TOTP or passkey via Credential Manager), §9.2 item 1
- docs/ARCHITECTURE.md (passkey environment variables row), AGENTS.md project notes
- api/openapi.yaml `/api/v1/auth/passkeys*` operations and schemas
- server `core.auth.passkey.api.PasskeyDtos` (request validation), tasks/passkeys-server.md, tasks/deploy-passkey-env.md

Git range:
- Branch: main
- HEAD: 741e5dcbd0bf374e915504699d8c4157e71e8717
- Base/start: b876b933c8bab901bf8a0bd756d884c111a3bba3
- Commits reviewed: 1 (7dfbb43ed; 741e5dcbd is the merge of it)

Summary: 0 P0, 1 P1, 1 P2, 3 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Digital Asset Links for the passkey RP ID are neither provided nor tracked, so Android passkey login cannot work in a real deployment**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/auth/PasskeyCredentials.kt:72` (consumer); missing on the server/deploy side
    - Category: Integration
    - Problem: Credential Manager only performs WebAuthn get/create for an RP ID when the domain serves `/.well-known/assetlinks.json` that associates this app (`delegate_permission/common.get_login_creds` with the signing cert fingerprint). The `passkeys-server` task explicitly recorded "Android's /.well-known/assetlinks.json is left to deployment/and-passkeys". This task does not add it, there is no assetlinks handling anywhere in the repository (server, `deploy/Caddyfile`, web), no follow-up task exists (`deploy-passkey-env` only forwards `COCKPIT_AUTH_PASSKEY_*`), and no assumption was recorded on this task. Without it every passkey request ends in the authenticator error path (shown as "passkey could not be used" / "could not be created").
    - Why it matters: criterion 1 ("Login with passkey; registration from settings") is only satisfied in unit tests with a fake authenticator; the end-to-end flow is blocked by an untracked integration dependency that was handed to this task. Criterion 1 currently has no recorded evidence either (`tasks.py verify and-passkeys` shows both unticked).
    - Suggested fix: at minimum, create a follow-up task (serve `assetlinks.json` for `COCKPIT_DOMAIN` with the app package and configurable SHA-256 cert fingerprint, document it next to `COCKPIT_AUTH_PASSKEY_ORIGINS`) and record an assumption on this task; when verifying criterion 1, either run it against a real stack with assetlinks and the Android origin configured, or mark it skipped with that reason rather than citing unit tests.

## P2 Medium

- [ ] **CR-2 | Passkey name is not limited to the server's 100 characters; an over-long name creates an orphaned passkey on the device**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/auth/PasskeySettings.kt:180`, `android/app/src/main/java/ch/sonensei/cockpit/auth/PasskeySettingsViewModel.kt:32`, `android/app/src/main/java/ch/sonensei/cockpit/auth/PasskeyRepository.kt:191`
    - Category: Correctness
    - Problem: `PasskeyRegistrationRequest.name` has `maxLength: 100` (server `@Size(max = 100)`), but the name field accepts any length and `PasskeyForm.canSubmit` only checks non-blank. The name is first sent in the final `finishPasskeyRegistration` call, after the authenticator has already created and stored the credential. The server then answers 400, which maps to `Failed` ("The passkey could not be created. Try again."); retrying with the same name repeats this.
    - Why it matters: each attempt leaves a passkey in the user's credential provider that the server never registered (it later fails login with 401), and the error message does not tell the user what to change.
    - Suggested fix: cap the input at 100 characters (as `deviceName()` already does with `DEVICE_NAME_MAX`) and/or include the length in `canSubmit`; add a view model test for the boundary.

## P3 Low

- [ ] **CR-3 | A 400 from the passkey token endpoint shows the password form's "Fill in every field" message**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/auth/PasskeyRepository.kt:146`
    - Category: Correctness
    - Problem: passkey login maps 400 to `LoginResult.InvalidRequest`, rendered as `login_error_invalid_request` ("... Fill in every field and try again."), although the passkey flow needs only the server address and the 400 is caused by a malformed assertion/ceremony.
    - Why it matters: misleading guidance; the user cannot fix it by filling fields.
    - Suggested fix: map 400 to `PasskeyFailed` (or `ServerError`) in the passkey path.

- [ ] **CR-4 | Passkey list load can overwrite a passkey just added**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/auth/PasskeySettingsViewModel.kt:63-65` and `:85-87`
    - Category: Correctness
    - Problem: `load()` and `submit()` both write `passkeys` without shared request identity. If a (slow or retried) list request was answered before the registration finished but is delivered after it, `passkeys ?: it.passkeys` replaces the list that already contains the new passkey. Repeated Retry taps also start parallel loads with no ordering.
    - Why it matters: the confirmation says the passkey was added while the list does not show it until the next reload. Low probability on normal networks.
    - Suggested fix: cancel the running load job when starting a new load or after a successful registration (or reload after success).

- [ ] **CR-5 | Settings column does not scroll as the passkey list grows**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/settings/SettingsScreen.kt:19-21`
    - Category: Quality
    - Problem: the passkey section is placed in a non-scrolling `Column` above a `weight(1f)` placeholder and the logout button. Each passkey adds a row; with several passkeys, a small screen or large font scale the logout button is pushed off screen with no way to reach it.
    - Why it matters: logout becomes unreachable in plausible configurations.
    - Suggested fix: make the settings column scrollable (or bound the list), keeping logout reachable.

## Review conclusion

Material issues found; fix before completing the task.

Notes for the parent worker: the review could not run Gradle in this sandbox (command denied), so test results were not independently re-run; the test sources were read and exercise the Credential Manager exception mapping, real Retrofit/kotlinx serialization against MockWebServer, the view models and the Compose screens. Thinking about the TOO_MANY_ATTEMPTS case in settings: it reuses the login string ("Sign-in is paused") and ignores `Retry-After`; acceptable but worth aligning if CR-2 is touched.
