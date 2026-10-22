# Test Report

Date: 2026-10-22

Task: web-passkeys - Web passkeys

Scope: task commit `651bef4f85f8c6e9e0d036e52795a8eb8405b36c` on top of base
`9b6ff39d92db4647633fd686b03aab1a4790efbe` (`git diff 9b6ff39d9..651bef4f8 -- web`), branch `main`,
HEAD = `651bef4f85f8c6e9e0d036e52795a8eb8405b36c`. Task ownership: base and task commit supplied by
the parent worker; the diff touches only `web/` files inside the task's `touches`
(`web/src/app/features/auth`, `web/src/app/features/settings/security`) plus `web/e2e/passkeys/`.
Uncommitted inputs at measurement time: only the test files listed below (no production changes;
`git diff --stat 651bef4f8 -- web/src ':!*.spec.ts'` is empty).

Requirements:
- `tasks/web-passkeys.md` acceptance criteria 1-3 and its recorded assumptions
- `SPEC.md` §4.1 (passkeys, login), `api/openapi.yaml` (passkey endpoints and their status codes)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`
- Parent worker's instructions (focus on criterion 2: ceremony error handling)

## Test files changed

- `web/src/app/features/auth/passkey/webauthn.spec.ts` (new)
- `web/src/app/features/auth/passkey/passkey-login.service.spec.ts` (new)
- `web/src/app/features/auth/login/login.spec.ts` (new `Login with a passkey` block and one unsupported-browser test)
- `web/src/app/features/settings/security/security.service.spec.ts` (new `passkeys` block; fake `WebAuthn` provider added to the shared setup)
- `web/src/app/features/settings/security/security-settings.spec.ts` (new `passkeys` block)

No existing test was removed, skipped or weakened.

## Tests added or updated

Count: 100 test cases (focused run went from 124 to 224 tests; `it.each` rows counted individually).

- `webauthn.spec.ts`: base64url encoding (URL-safe alphabet, no padding), decoding of every padding
  length and of padded input, round trip of all 256 byte values, ArrayBuffer result; `supported()`
  for all combinations of `PublicKeyCredential` / `navigator.credentials`; `create`/`get` pass
  challenge, user id and credential ids as ArrayBuffers, keep/omit `transports`, return the
  Registration/AuthenticationResponseJSON shape, defaults for missing `getTransports`,
  `getClientExtensionResults`, `authenticatorAttachment` and a null `userHandle`; error mapping
  NotAllowedError/AbortError -> cancelled, InvalidStateError -> duplicate, NotSupportedError ->
  unsupported, SecurityError/ConstraintError/UnknownError/TypeError/string/null/undefined -> failed,
  cause kept; a synchronous throw; null credential -> cancelled; unsupported browser -> unsupported
  without calling `navigator.credentials`.
- `passkey-login.service.spec.ts` (HttpTestingController + fake WebAuthn): full ceremony (options ->
  `WebAuthn.get(publicKey)` -> `POST /passkeys/login` with ceremonyId, credential, rememberDevice ->
  `GET /auth/me` refresh, `AuthService.user()` updated), `SILENT_ERRORS` and `SKIP_LOGIN_REDIRECT`
  on both requests, rememberDevice false, PasskeyError passed through with no login request,
  401 (no /me, no toast, no navigation), 429 with Retry-After, 500 on options (no prompt opened),
  unreachable server (status 0).
- `login.spec.ts`: button hidden without WebAuthn (real jsdom detection); passkey sign-in without
  username/password, return URL, unsafe return URL dropped, "keep me signed in" -> `login(true)`,
  TOTP enrollment redirect; messages for cancelled/unsupported/failed/duplicate PasskeyErrors,
  401 (passkey not accepted), other API errors; typed username kept; 429 backoff disables both
  sign-in buttons and counts down (with and without Retry-After); one ceremony at a time; error
  cleared on the next attempt.
- `security.service.spec.ts`: list (silent), `passkeysSupported()` delegation, add (TOTP body,
  `WebAuthn.create(publicKey)`, finish body with ceremonyId/credential/name), 403 wrong TOTP (no
  ceremony, no finish), PasskeyError (no finish), 409 on finish, DELETE with 204 and 404, no toasts.
- `security-settings.spec.ts`: list with "Last used"/"Added ..., not used yet", empty state, loading
  state, load error with retry, unsupported browser (no add form, hint, delete still offered); add
  with trimmed name/code, list updated, "Passkey ... added." status, form reset; first passkey into
  an empty list; add while the list is still loading (no duplicate when it arrives); client-side
  validation (empty/blank name, empty/short/non-digit code -> no request); messages for each
  PasskeyError failure (name kept, used code cleared, no finish request), 403, 429 with/without
  Retry-After, 409, 400, 500 and unexpected errors; one ceremony while an add is pending; error
  cleared after success; delete after confirmation, cancel, pending-button state, failure message,
  404 treated as already deleted, last passkey -> empty state, "added" note hidden after delete,
  keyboard focus moves to the next row and then to the heading.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: register passkeys in Security settings | `SecuritySettings > passkeys > adds a passkey with a name and a current TOTP code`, `adds the first passkey to an empty list`, `adds a passkey while the list is still loading...`, `does not start a ceremony with ...` (5 rows), `offers no add form in a browser without WebAuthn...`; `SecurityService > passkeys > adds a passkey...` | focused `ng test` (below) and `npm run coverage` | Pass |
| AC1: list/delete passkeys in Security settings | `SecuritySettings > passkeys > lists the passkeys...`, `says so when there are no passkeys`, `shows the loading state...`, `shows a passkey load error with a retry...`, `deletes a passkey after the confirmation`, `keeps the passkey when the confirmation is cancelled`, `disables the delete button...`, `shows a failed delete...`, `drops a passkey that was already deleted elsewhere (404)`, `deletes the last passkey...`, `moves the focus to the next passkey...`; `SecurityService > passkeys > lists the passkeys...`, `deletes a passkey with DELETE...` | same | Pass |
| AC1: "Sign in with a passkey" on login | `Login with a passkey > signs in with a passkey alone...`, `keeps the session for 30 days...`, `opens the TOTP enrollment...`, `drops an unsafe return URL...`; `Login > offers no passkey button when the browser has no WebAuthn`; `PasskeyLogin > runs the ceremony and refreshes the session`, `sends rememberDevice false...` | same | Pass |
| AC2: ceremony error handling in the browser helper | `WebAuthn > create/get > maps a ... to ...` (12 rows), `maps ... thrown by the browser to failed` (4 rows), `maps a synchronous throw...`, `rejects as cancelled when the browser returns no credential` (create, get), `rejects as unsupported without calling the browser...` (create, get), `supported` (4 tests); `base64url` (5 tests) | same | Pass |
| AC2: ceremony error handling on login | `Login with a passkey > explains a ... ceremony and stays on the page` (4 rows), `explains a passkey the server does not accept (401)`, `shows the server message on other API errors`, `blocks both sign-in buttons during a throttled passkey login...`, `waits 60 s...`, `starts one ceremony at a time...`, `clears a passkey error...`, `keeps username and password typed so far...`; `PasskeyLogin > rejects with ...` (5 tests) | same | Pass |
| AC2: ceremony error handling in Security settings | `SecuritySettings > passkeys > explains a ... browser ceremony...` (4 rows), `explains a wrong TOTP code (403)...`, `explains a throttled attempt...`, `explains a passkey that is already registered (409)`, `explains a registration the server rejects (400...)`, `shows the server message for other failures...`, `starts only one ceremony while an add is pending`, `clears the previous error once an add succeeds`; `SecurityService > passkeys > does not start the ceremony when the TOTP code is wrong (403)`, `rejects with the PasskeyError...`, `rejects with a 409 ApiError...` | same | Pass |
| AC2: `cd web && npm run check` passes | whole web suite, lint, e2e typecheck, API check, build (run inside `.taskcheck`) | `sh scripts/check.sh` | Pass |
| AC3: Playwright `e2e/passkeys/passkeys.spec.ts` against the e2e stack | `e2e/passkeys/passkeys.spec.ts` (not changed) | Not run by the test-writer | Not run: the parent asked not to run e2e (slow, needs the podman e2e stack); the parent reports having run it (3 passed). This report gives no evidence for AC3. |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/auth/**/*.spec.ts' --include='src/app/features/settings/security/*.spec.ts'`

Result:

Pass: 9 test files, 224 tests passed (baseline before the new tests: 7 files, 124 tests).
`cd web && npm run lint`: Pass ("All files pass linting"); Prettier check of the changed test files: Pass.
One deliberate sanity check: an expected message in a new test was temporarily changed, the test
failed with the real message as "Received", and the expectation was restored.

## Full test execution

Command:

`cd web && npm run coverage` (the whole web unit suite with coverage)

Result:

Pass: 166 test files, 3453 tests passed, exit 0. The server and Android suites ran as part of `.taskcheck` (below).

## .taskcheck

Result:

Pass: `sh scripts/check.sh` exited 0 and printed "== all checks passed". It ran the TFP gate tests,
server `./gradlew check`, web `npm run check` (166 files / 3453 tests, lint, build; only the
pre-existing bundle budget warning) and the Android ktlint, unit tests and lint, on the working tree
with the new tests.

## Bugs found

No production defects found. All new tests pass against the task commit.

Observations, not defects (no requirement violated, no failing test; for the parent's judgement):

- `security-settings.ts` `loadPasskeys` vs. `addPasskey`: if a list request started before an add
  answers after it with a pre-registration list, it overwrites the list and the new passkey is
  hidden until the next load. This needs the list GET to take longer than the whole browser
  ceremony, so it is unlikely. The test covers the realistic order, where the late list already
  contains the new passkey.
- `login.ts:187` rethrows errors that are neither `PasskeyError` nor `ApiError` (left to the global
  error handler, with no message on the form). The password path (`login.ts:154`) does the same.

## Coverage

Status: verified (fresh full run on the final tests; all scoped files measured).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 160/161 (99.38%), measured by intersecting the `DA:` lines of the same run's
`web/coverage/web/lcov.info` with the added lines of `git diff -U0 9b6ff39d9 651bef4f8` for the six
scoped files. Removed and non-executable lines are not counted. Changed-branch coverage was not measured.
The only uncovered changed line is `login.ts:187`.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-passkeys`;
  base `9b6ff39d92db4647633fd686b03aab1a4790efbe`; HEAD/task commit
  `651bef4f85f8c6e9e0d036e52795a8eb8405b36c` (from the parent); scope = production files in
  `git diff --name-only 9b6ff39d9..651bef4f8 -- web/src`, excluding specs and styles.
- Source/test snapshot identifier (including uncommitted inputs): production = HEAD `651bef4f8`
  (no uncommitted production changes). Test files (sha256): `webauthn.spec.ts` 86aedf5b...b55899,
  `passkey-login.service.spec.ts` 49ff2d85...7f1d4d, `login.spec.ts` b5a690bd...5c8d49,
  `security.service.spec.ts` 9e1aeb62...ba61be, `security-settings.spec.ts` 743d8143...1f2746.
- Exact included files: `web/src/app/features/auth/login/login.ts`,
  `web/src/app/features/auth/passkey/passkey-login.service.ts`,
  `web/src/app/features/auth/passkey/webauthn.ts`,
  `web/src/app/features/settings/security/delete-passkey-dialog.ts`,
  `web/src/app/features/settings/security/security-settings.ts`,
  `web/src/app/features/settings/security/security.service.ts`. Templates `login.html` and
  `security-settings.html` are exercised through the component tests but are not instrumented by
  v8. Excluded: `auth.scss` and `security-settings.scss` (styles only), `security-settings.spec.ts`
  (test), `web/e2e/passkeys/passkeys.spec.ts` (e2e test). Nothing was renamed or deleted.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with
  `@vitest/coverage-v8` 5.0.5; `npm run coverage` in `web/`; the whole web unit suite.
- Run start/completion time and exit status: 2026-10-22T11:59:29+02:00 to 12:00:14+02:00, exit 0
  (166 files, 3453 tests passed).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json`
  (sha256 a7e1f73cfc535aa700d8b57b7e96d0a894805a7b18cbf93a63a6f06fcd3ac183),
  `web/coverage/web/coverage-summary.json` (sha256
  bc092c4a455d766235539b5e3acf631b4bb192b31719377790601195c0be006a), `web/coverage/web/lcov.info`.
- Evidence outputs are fresh and inputs stayed unchanged: the report files were written at
  12:00:13+02:00, inside the run. The test files were not edited after the run started.
- Final run after latest test additions and production fixes: yes for the test additions. There
  were no production fixes. A run after any later fix would be needed.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/auth/login/login.ts` | 93/95 (97.89%) | 60/63 (95.23%) | 16/16 (100%) | 81/82 (98.78%) |
| `web/src/app/features/auth/passkey/passkey-login.service.ts` | 14/14 (100%) | 5/5 (100%) | 3/3 (100%) | 11/11 (100%) |
| `web/src/app/features/auth/passkey/webauthn.ts` | 43/43 (100%) | 26/26 (100%) | 12/12 (100%) | 35/35 (100%) |
| `web/src/app/features/settings/security/delete-passkey-dialog.ts` | 23/23 (100%) | 8/9 (88.88%) | 1/1 (100%) | 15/15 (100%) |
| `web/src/app/features/settings/security/security-settings.ts` | 167/167 (100%) | 81/86 (94.18%) | 39/39 (100%) | 140/140 (100%) |
| `web/src/app/features/settings/security/security.service.ts` | 17/17 (100%) | 5/5 (100%) | 8/8 (100%) | 14/14 (100%) |
| Aggregate (summed counters, 6 files, none missing) | 357/359 (99.44%) | 185/194 (95.36%) | 79/79 (100%) | 296/297 (99.66%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `login.ts:187` and branch `184[1]` | Passkey login rejected with something that is neither `PasskeyError` nor `ApiError`: rethrown to the global error handler | Left uncovered. Only a programming error can reach it, because `PasskeyLogin` rejects only with these two types. A test would have to let an unhandled rejection escape from a click handler, which fails the Vitest run. |
| `login.ts:154` and branch `154[0]` | Password login: non-`ApiError` rethrown | Existed before this task and is outside the passkey scope. Left uncovered. |
| `login.ts` branch `52[0]`, `delete-passkey-dialog.ts` branch `47[0]` | Branches the compiler adds at the class declaration (decorator emit), with no behavior | Not material. |
| `security-settings.ts` branches `247[1]`, `248[1]` | `?? -1` / `?? null` in `removePasskey` when the list is `null`. Unreachable: delete buttons exist only once the list has loaded | Defensive code. Left uncovered. |
| `security-settings.ts` branches `222[1]`, `236[1]`, `237[1]` | Recovery-code copy and device revoke code that existed before this task | Outside the passkey scope. Left uncovered. |

## Unable to test

- AC3 (Playwright with a CDP virtual authenticator against the e2e stack): not run here, as the
  parent instructed. The parent reports a passing run. It needs the parent's evidence via
  `tasks.py verify`.
- A real browser WebAuthn ceremony (platform authenticator, origin/RP-ID checks) cannot run in
  jsdom. The unit tests stub `PublicKeyCredential`/`navigator.credentials`. The e2e spec covers the
  real ceremony.
- The visual layout (passkey button below the form, the "or" divider) has no unit test. Not
  needed for the criteria.

## Revision 587a4cf4f (e2e locator fix)

Scope: commit `587a4cf4f8cd05e3a5f4f329befe2fe6690b8eee` on top of `b29ee70e0` (review report).
`git diff --stat 651bef4f8 587a4cf4f` shows only `reviews/web-passkeys/*`, the unit spec files
listed above, and three e2e specs. No production code changed since the measured task commit.

What changed: `getByRole('button', { name: 'Sign in' })` became
`getByRole('button', { name: 'Sign in', exact: true })` in `web/e2e/phase0/demo.spec.ts` (lines 94
and 183), `web/e2e/phase2/demo.spec.ts` (line 372) and `web/e2e/support/smoke.spec.ts` (line 13).
Reason: the login page now has a second button, "Sign in with a passkey". Playwright's default
name match is a case-insensitive substring match, so the old locator matched both buttons and
failed in strict mode. This change is a regression fix for existing e2e specs that the task
broke. It does not weaken them: they still target the same password "Sign in" button (`login.html:93-100`).

Ambiguity check (test-writer, grep):
- `grep -rnE "name: *['\"]Sign in" web/e2e web/src`: every `Sign in` button locator now uses
  `exact: true`. The only other matches are `getByRole('button', { name: 'Sign in with a passkey' })`
  (unique) and `getByRole('heading', { name: 'Sign in' })` in `web/e2e/passkeys/passkeys.spec.ts:73`.
  The login page has one heading (`login.html:2`), so that locator is not ambiguous.
- `grep -rniE "/sign ?in|hasText: *['\"]sign in|getByText\(['\"]sign in" web/e2e web/src`: no
  matches (no regex or text locators that could hit both buttons).
- The unit specs (`web/src/**/*.spec.ts`) query the login DOM with CSS selectors or text
  assertions, not substring role locators, so they are not affected. `login.spec.ts` already checks
  the passkey button text exactly (`toBe('Sign in with a passkey')`).
- No other ambiguous `Sign in` locators remain.

Commands run by the test-writer (in `web/`, on HEAD `587a4cf4f`, clean working tree before this
report edit):
- `npm run e2e:typecheck` (`tsc -p e2e/tsconfig.json`): Pass, exit 0, no output.
- `npm run lint` (`ng lint --max-warnings=0`): Pass, "All files pass linting."

E2e execution: not run by the test-writer, as the parent instructed. Parent-reported results:
before the fix, `npm run e2e -- e2e/passkeys/passkeys.spec.ts` (with the phase-demos project)
failed in phase0 step 3 and phase2 step 5 with "strict mode violation: getByRole('button',
{ name: 'Sign in' }) resolved to 2 elements". After the fix,
`npm run e2e -- e2e/passkeys/passkeys.spec.ts e2e/support/smoke.spec.ts` on the podman e2e stack
(includes phase-demos) gave 35 passed. This report did not reproduce those runs. AC3 evidence
comes from the parent's `tasks.py verify`.

Unit tests and coverage: unchanged. This commit touches only e2e specs, which are not part of the
Vitest unit suite or its v8 coverage. Production code is identical to `651bef4f8`, so the coverage
figures in the "Coverage" section still describe the current production code. They were not
re-measured for this revision.

## Summary

Tests added and all validation passed. The focused suite (224 tests), the full web suite with
coverage (3453 tests), `npm run lint` and `.taskcheck` all pass, and no production defects were
found. Task-scoped whole-file coverage is 99.44% of statements, 95.36% of branches, 100% of
functions and 99.66% of lines. Changed-line coverage is 160/161. AC3 (e2e) was not run by the
test-writer. Revision `587a4cf4f` makes the `Sign in` button locators exact in three existing e2e
specs. A grep found no other ambiguous `Sign in` locators, and `npm run e2e:typecheck` and
`npm run lint` pass. Production code and unit coverage are unchanged.
