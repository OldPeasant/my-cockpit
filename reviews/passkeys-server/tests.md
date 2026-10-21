# Test Report

Date: 2026-10-20

Task: passkeys-server - Passkeys (WebAuthn) backend

Scope: base `7547cf44331b638adb5ec6a1a6d3b01807b37aaa` (`git merge-base HEAD origin/main`, after the rebase onto
main) to HEAD `1e5bb332419de950821f27f3f03c859fa97c0cce`, branch `main`. Task-owned commits, supplied by the parent
and confirmed with `git log --oneline origin/main..HEAD` (exactly these four): `925af6f` (WebAuthn registration,
login and management endpoints), `7577d45` (tests, OpenAPI contract, config docs), `f38df20` (additional tests from
the previous test-writer run), `1e5bb33` (regenerated web API client). Diff examined:
`git diff -M 7547cf44..HEAD`. Working tree clean before and after this run (`git status --short` empty).

This report replaces the earlier one written against `bf6564b` (pre-rebase). Re-check of what changed since then:
`git diff bf6564b..HEAD -- server/src/main/kotlin/ch/sonensei/cockpit/core/auth
server/src/main/resources/db/migration/core/V202610201300__core_auth_passkey.sql` is empty, so the passkey production
code is unchanged. The rebase brought in main's `core/items` link module (migration `V202610201400`, independent
table, no foreign key to passkeys or `app_user`), the `api/openapi.yaml` link operations, and a test-infra change
(`PostgresTestBase` `max_connections=300`, Hikari pool size 5 in the test properties). The passkey test files are
the committed versions of the previous run's tests.

Requirements:
- `tasks/passkeys-server.md` acceptance criteria and recorded assumptions (TOTP step-up at the options call,
  wrong codes 403 and throttled; in-memory single-use ceremonies; configured RP ID/origins incl.
  `android:apk-key-hash`, request-based defaults; discoverable credentials, UV required, attestation none,
  ES256/EdDSA/RS256; passkey login shares the login throttle; passkeys not in link/search or backup)
- `SPEC.md` 4.1 (passkeys as alternative to password+TOTP for web and Android), 4.2 (Problem Details)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md` project notes (generated client in sync,
  never log secrets, fictitious test data, out-of-order Flyway migrations)

## Test files changed

- None in this run. The existing passkey tests (committed in `7577d45` and `f38df20`) were re-run and re-reviewed
  against HEAD:
  `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/passkey/{PasskeyCeremoniesTests, PasskeyConfiguredRelyingPartyTests, PasskeyEdgeCaseTests, PasskeyRelyingPartiesTests, PasskeyServiceUnitTests, PasskeyWebTests, TestAuthenticator}.kt`

## Tests added or updated

Count: 0

Reason: the production code under test is byte-identical to the revision the previous run analysed; the 46
passkey tests still cover every acceptance criterion and recorded assumption (table below), changed executable
lines are 434/434 covered, and the remaining uncovered branches are the same defensive/unreachable ones justified
below. The rebase added no interaction with passkeys that needs a new test (the link module does not reference
passkeys, consistent with the recorded assumption that passkeys are not user content; Flyway runs
`out-of-order=true`, so `V202610201300` after an applied `V202610201400` is fine and is exercised by every Postgres
test from an empty schema).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 registration needs an authenticated session + TOTP (web) | PasskeyWebTests: `a logged-in owner registers a passkey after confirming with TOTP`, `registration needs a login, the CSRF header and a correct TOTP code`; PasskeyServiceUnitTests: `a principal that is not the active owner cannot start a registration`, `a registration ceremony can only be finished by the owner who started it`; PasskeyEdgeCaseTests: `managing passkeys needs a login, the CSRF header for a session, and a UUID` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.passkey.*'` | Pass |
| AC1 registration via Android device token | PasskeyWebTests: `an Android device registers a passkey with its bearer token`; PasskeyConfiguredRelyingPartyTests: `the Android app registers a passkey and logs in with it, and the synced passkey also works on the web` | same | Pass |
| AC1 registration ceremony verified by webauthn4j (challenge, type, origin, RP ID, UV required, single use, expiry, duplicates, malformed) | PasskeyWebTests: `a registration ceremony works once, ...`, `the same authenticator cannot be registered twice`; PasskeyEdgeCaseTests: `registration requires user verification`, `a passkey is bound to the relying party ID it was registered for`, `registration and authentication ceremonies cannot be swapped`, `a passkey name of 100 characters is accepted, 101 is a 400`, `a passkey registered without transports is stored without them and still excluded and usable` | same | Pass |
| AC1 authentication ceremony, web session | PasskeyWebTests: `a passkey logs the browser in without password or TOTP`, `the web passkey login needs the CSRF header`, `invalid assertions are rejected like wrong credentials`, `a sign count that does not increase is rejected`; PasskeyEdgeCaseTests: `a login without remember device gets a browser-session cookie and a short session`, `a synced passkey that always reports a sign count of zero can log in again and again`, `an assertion without a user handle is accepted when it verifies`, `a malformed assertion for a valid ceremony is rejected like wrong credentials`, `the login records the authenticator's latest backup state and user verification`, `a passkey of an account whose setup is not complete cannot log in` | same | Pass |
| AC1 authentication ceremony, Android token login (incl. `android:apk-key-hash` origin) | PasskeyWebTests: `a passkey logs an Android device in and returns its tokens`; PasskeyEdgeCaseTests: `a failed passkey token login is a 401 problem and registers no device`; PasskeyConfiguredRelyingPartyTests: both tests (Android origin accepted; other app hash, request origin and other RP ID rejected) | same | Pass |
| AC1 credential list/delete | PasskeyWebTests: `a deleted passkey can no longer log in`; PasskeyEdgeCaseTests: `managing passkeys needs a login, the CSRF header for a session, and a UUID`, `deleting the account deletes its passkeys` | same | Pass |
| Assumption: shared login throttle (wrong TOTP and failed assertions count; success resets; blocked attempt does not consume the ceremony; failed grant write is not a success) | PasskeyWebTests: `wrong TOTP codes for a registration count against the login throttle`, `failed passkey logins share the login throttle`; PasskeyEdgeCaseTests: `a successful passkey login resets the throttle shared with the password login`, `a correct TOTP code for a registration resets the throttle, wrong ones use it up`; PasskeyServiceUnitTests: 4 throttle tests | same | Pass |
| Assumption: ceremonies in memory, single use, 5 min, max 256 | PasskeyCeremoniesTests (4) | same | Pass |
| Assumption: RP from config, request-based default | PasskeyRelyingPartiesTests (2), PasskeyConfiguredRelyingPartyTests (2) | same | Pass |
| Refactor `LoginService.startSession` / `DeviceTokenService.issueFor` (regression) | existing LoginServiceThrottleTests, LoginEdgeCaseTests, DeviceTokenEdgeCaseTests, plus PasskeyServiceUnitTests grant-write failure tests | `cd server && ./gradlew test jacocoTestReport ktlintCheck --rerun-tasks` | Pass |
| Never log secrets | PasskeyEdgeCaseTests: `passkey registration and login never log tokens or the TOTP code`; PasskeyServiceUnitTests: `the registration options request never shows the TOTP code` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.passkey.*'` | Pass |
| AC2 passkey tests pass (Postgres via Testcontainers) | all 46 tests in `ch.sonensei.cockpit.core.auth.passkey` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.passkey.*'` | Pass (46/46, Testcontainers Postgres 16) |
| AC3 OpenAPI documented, `OpenApiContractTest` passes without the flag | `ch.sonensei.cockpit.core.web.OpenApiContractTest` (no `UPDATE_OPENAPI` set) | `cd server && ./gradlew test jacocoTestReport ktlintCheck --rerun-tasks` | Pass (1/1) |
| Generated web client in sync with `api/openapi.yaml` (TW-1 of the previous run) | web `api:check` drift check | `cd web && npm run api:check`; also inside `sh scripts/check.sh` | Pass ("src/app/api/generated is up to date.") |
| Real browser / real Android Credential Manager ceremony | none | - | Not run: no browser or Android device here; ceremonies are exercised with the software authenticator `TestAuthenticator` (ES256, none attestation) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.passkey.*'`

Result:

Pass: 46 tests, 0 failures, 0 errors, 0 skipped (PasskeyCeremoniesTests 4, PasskeyConfiguredRelyingPartyTests 2,
PasskeyEdgeCaseTests 17, PasskeyRelyingPartiesTests 2, PasskeyServiceUnitTests 7, PasskeyWebTests 14), read from
`server/build/test-results/test/TEST-*.xml`; BUILD SUCCESSFUL, exit 0.

## Full test execution

Command:

`cd server && ./gradlew test jacocoTestReport ktlintCheck --rerun-tasks`

Result:

Pass: 916 tests in 79 test classes, 0 failures, 0 errors, 0 skipped (incl. OpenApiContractTest 1/1 and the 46
passkey tests); ktlintCheck passed; exit 0. Run 2026-10-20T13:26:22Z to 13:27:56Z.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` from the repo root: "tfp: completion gate tests" OK, "server: ktlint + tests"
(`./gradlew check`) OK, "web: eslint + tests + build" (`npm run -s check`: api:check, lint, e2e typecheck,
test:ci, ng build) OK, "android" step skipped by the environment (`COCKPIT_NO_ANDROID=1`, no Android SDK on this
machine; this task touches no Android code), final line "== all checks passed".

## Bugs found

None open.

### Generated web API client not regenerated after the OpenAPI change (resolved)

Finding ID:

`TW-1`

Test:

`sh scripts/check.sh` (`.taskcheck`), step "web: eslint + tests + build" (api:gen drift check in `npm run check`)

Production source:

`web/src/app/api/generated/` (generated from `api/openapi.yaml`)

Requirement:

AGENTS.md project notes: "API changes: server first, regenerate `api/openapi.yaml`, then clients".

Expected behavior:

The generated web client includes the Passkeys operations and models and `.taskcheck` passes.

Actual behavior:

Resolved in `1e5bb33` (`cd web && npm run api:gen`, generated files only: `fn/passkeys/*.ts`, 14 models,
`functions.ts`, `index.ts`, `models.ts`). Confirmed at HEAD `1e5bb33` by `cd web && npm run api:check` ->
"src/app/api/generated is up to date." against the current (rebased) `api/openapi.yaml`, and by
`sh scripts/check.sh` -> "all checks passed".

Severity:

High when found (blocked `tasks.py done`); now resolved.

Fix required:

No. Already fixed in `1e5bb33` and confirmed above.

No defects were found in the server passkey behavior.

## Coverage

Status: verified (fresh run at HEAD `1e5bb33`, clean working tree, no production or test changes in this run).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 434/434 changed executable lines covered (100%). Method: added/modified lines from
`git diff -U0 7547cf44..HEAD -- server/src/main/kotlin` intersected with JaCoCo `line` elements of the same report
(covered when `ci > 0`); removed and non-executable lines are not counted. Changed-branch coverage is not derived
from this.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: passkeys-server; base
  `7547cf44331b638adb5ec6a1a6d3b01807b37aaa` (merge-base with `origin/main`); HEAD
  `1e5bb332419de950821f27f3f03c859fa97c0cce`; commits `925af6f`, `7577d45`, `f38df20`, `1e5bb33` supplied by the
  parent and matching `git log origin/main..HEAD`; file list from
  `git diff --name-only 7547cf44..HEAD -- server/src/main web/src android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `1e5bb33`, no uncommitted or untracked
  inputs (`git status --short` empty before and after). SHA-256 of `git ls-files -s` over
  `server/src/main/kotlin/ch/sonensei/cockpit/core/auth` and the passkey test folder:
  `35696e1d86d44723c82465553c89afff71eed84553c03fb1c2c542c3aea22b83`, identical before and after the runs.
- Exact included files: the 10 Kotlin files in the table below. Excluded:
  `server/src/main/resources/db/migration/core/V202610201300__core_auth_passkey.sql` (SQL, not instrumented by
  JaCoCo; applied by Flyway in every Postgres test); the 25 files under `web/src/app/api/generated/` (generated
  client, checked for drift by `npm run api:check`, no hand-written behavior); `api/openapi.yaml` (generated
  contract, checked by OpenApiContractTest); `docs/ARCHITECTURE.md` (documentation). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo 0.8.15 (Gradle `jacoco` plugin);
  `./gradlew test jacocoTestReport ktlintCheck --rerun-tasks` in `server/`; whole server suite (916 tests).
- Run start/completion time and exit status: 2026-10-20T13:26:22Z to 2026-10-20T13:27:56Z; exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (SHA-256 `c7ed488691a5ea967cb28c92879af6af2c7ba3189355e94ca278c4c655106e1c`, mtime 13:27:55Z),
  `server/build/jacoco/test.exec` (SHA-256 `8a45a22a3c46ce6844cc2f3dbd83756129eaeaacf98ee01cc959c8e17082ed79`,
  mtime 13:27:55Z).
- Evidence outputs are fresh and inputs stayed unchanged: `--rerun-tasks` forced test execution and report
  regeneration; both artifacts' mtimes fall inside the run window; snapshot hash and HEAD unchanged.
- Final run after latest test additions and production fixes: yes (no tests or production code changed since
  `1e5bb33`).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. JaCoCo has no
separate statement counter; "Statements" shows its INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/DeviceTokenService.kt` | 404/407 (99.3%) | 17/18 (94.4%) | 27/27 (100.0%) | 69/69 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/LoginService.kt` | 660/669 (98.7%) | 50/52 (96.2%) | 43/45 (95.6%) | 90/90 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/infra/SecurityConfiguration.kt` | 291/293 (99.3%) | 2/4 (50.0%) | 10/10 (100.0%) | 39/39 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/api/PasskeyController.kt` | 316/323 (97.8%) | 16/18 (88.9%) | 14/14 (100.0%) | 46/46 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/api/PasskeyDtos.kt` | 584/586 (99.7%) | 2/2 (100.0%) | 70/70 (100.0%) | 127/127 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/api/PasskeyRelyingParties.kt` | 102/111 (91.9%) | 9/10 (90.0%) | 2/2 (100.0%) | 15/15 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/domain/Passkey.kt` | 102/109 (93.6%) | 1/4 (25.0%) | 17/18 (94.4%) | 28/28 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/domain/PasskeyCeremonies.kt` | 217/219 (99.1%) | 7/8 (87.5%) | 23/23 (100.0%) | 31/31 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/domain/PasskeyService.kt` | 1152/1184 (97.3%) | 66/74 (89.2%) | 49/50 (98.0%) | 174/174 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/infra/PasskeyRepository.kt` | N/A | N/A | N/A | N/A |

Aggregate (summed counters over the 9 files with bytecode; `PasskeyRepository.kt` is a Spring Data interface with
no instrumented code, N/A; the SQL migration and the generated web client are excluded as above): instructions
3828/3901 (98.1%), branches 170/190 (89.5%), methods 255/259 (98.5%), lines 619/619 (100.0%). Identical to the
pre-rebase measurement, as expected for unchanged production code.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `PasskeyService.kt:282` (blank-entry filter of stored transports) | exclude list without blank transports | Stored value never contains blank entries (written from webauthn4j's parsed set); transports absent/empty is tested. No test |
| `PasskeyService.kt:120`, `:254` (ceremony of another principal / inactive owner) | only the owner who started a registration can finish it | Covered by the unit tests named in the table; remaining branch (inactive account with a still-valid session) not reachable through the API |
| `PasskeyService.kt:83` (`secret == null`) | stored TOTP secret undecodable | Defensive; not reachable with a valid account. No test |
| `PasskeyService.kt:137-138`, `:241` (`checkNotNull` after webauthn4j verification) | webauthn4j guarantees these values after a successful verify | Defensive; not reachable. No test |
| `PasskeyService.kt` companion `ALGORITHMS` init (missed method/instructions) | static initialisation | Compiler-generated; the offered algorithms are asserted via `pubKeyCredParams` in PasskeyWebTests. No test |
| `Passkey.kt:25`, `:53` (`name` setter, `uvInitialized || userVerified` when false) | registration requires UV, so `uvInitialized` is always true | Not reachable; recordUse result asserted in `the login records the authenticator's latest backup state and user verification` |
| `PasskeyCeremonies.kt:79` (purge removes an expired entry) | memory hygiene; expiry itself is enforced in `finish` and tested | Not observable through the API. No test |
| `PasskeyRelyingParties.kt:34` (`requireNotNull(host)`) | a request URL always has a host | Defensive. No test |
| `PasskeyController.kt:260` (principal of another type) | defensive guard | Not reachable with the configured filters. No test |
| `LoginService.kt:136`, `DeviceTokenService.kt:124`, `SecurityConfiguration.kt:111` | lines not changed by this task (recovery-code parsing, device info fallback, CSRF attribute) | Outside the task's changed lines; all changed executable lines in these files are covered |

## Unable to test

- A ceremony with a real browser (`navigator.credentials.create/get`) or a real Android Credential Manager and
  Digital Asset Links: no browser/device here and outside the server task's scope (web/Android passkey tasks and
  e2e). The server side is exercised with `TestAuthenticator`, which produces the same JSON shapes. Follow-up: the
  web and Android passkey tasks' e2e tests.
- EdDSA and RS256 credentials: `TestAuthenticator` emulates ES256 only; the offered algorithm list is asserted.
  Low risk (webauthn4j does the verification). No follow-up required.
- Server restart losing pending ceremonies (recorded assumption): not testable without restarting the context;
  the resulting behavior equals "unknown ceremony", which is tested.
- Android part of `.taskcheck`: skipped by the environment (`COCKPIT_NO_ANDROID=1`); the task changes no Android
  files.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. At HEAD `1e5bb33`: 46/46 passkey tests,
916/916 server tests, ktlintCheck and OpenApiContractTest pass, `sh scripts/check.sh` passes, 434/434 changed
executable lines covered. TW-1 (stale generated web client) is resolved by `1e5bb33` and confirmed with
`npm run api:check` and `.taskcheck`.
