# Fix Report

Date: 2026-10-20

Task: p0-web-devices - Settings: security and devices

Scope: current task; review findings CR-1..CR-4 (all P3, approved for fixing by the parent worker)
from `reviews/p0-web-devices/review.md`. The code changes are in commit e46ff3d (written by an
earlier fixer run that stopped before its report). This run reviewed that commit against each
finding, confirmed it is complete (no missing pieces, the generated web client needed no change),
and re-ran all validation on it. No further code changes were needed.

## Fixed findings

### CR-1 - Overlapping revokes share one `revoking` id and one `revokeError`

Files changed:
- `web/src/app/features/settings/security/security-settings.ts`
- `web/src/app/features/settings/security/security-settings.html`
- `web/src/app/features/settings/security/security-settings.spec.ts`

Problem:
A single `revoking` id let a second revoke re-enable the first row's button (and vice versa) while a
DELETE was still running, and the revoke error did not name the device.

Fix:
`revoking` is now a `ReadonlySet<string>` of pending device ids (added on start, removed in
`finally`); each button uses `[disabled]="revoking().has(device.id)"`. `revokeError` holds
`{ name, message }`, and the alert reads "<device name> could not be revoked: <message>".
Tests: "keeps each revoke button disabled while its own request runs, when revokes overlap", and the
failure tests now assert "Pixel 9 could not be revoked: Try later".

Validation:
- `cd web && npm run -s check` - Pass (api up to date, lint clean, 24 files / 357 tests passed, build OK)

### CR-2 - 409 response of the recovery-code endpoint is not documented in OpenAPI

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/api/RecoveryCodeController.kt`
- `api/openapi.yaml` (regenerated)

Problem:
`RecoveryCodeService` throws `ConflictException` (409 Problem Details) when setup is not complete,
but the endpoint documented only 200, 400, 401 and 429.

Fix:
Added `@ApiResponse(responseCode = "409", description = "Setup is not complete: there is no active
account", content = ProblemDetail as application/problem+json)` and regenerated `api/openapi.yaml`.
`cd web && npm run api:gen` produced no diff in `web/src/app/api/generated/` (the extra error response
adds no types; `api:check` reports it up to date).

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*' --tests '*OpenApiContractTest' ktlintCheck` - Pass
- `cd web && npm run api:gen` then `git status` - no changes

### CR-3 - `cdkFocusInitial` on Cancel has no effect with `autoFocus: 'dialog'`

Files changed:
- `web/src/app/features/settings/security/security-settings.ts`
- `web/src/app/features/settings/security/security-settings.spec.ts`

Problem:
The dialog was opened with `autoFocus: 'dialog'`, so the `cdkFocusInitial` on Cancel was ignored.

Fix:
Removed `autoFocus: 'dialog'` so the Material default (`first-tabbable`, which honours
`cdkFocusInitial`) focuses Cancel; Enter therefore does not revoke. Test: "focuses Cancel when the
revoke dialog opens".

Validation:
- `cd web && npm run -s check` - Pass

### CR-4 - Keyboard focus is lost when the codes appear/disappear or a revoked row is removed

Files changed:
- `web/src/app/features/settings/security/security-settings.ts`
- `web/src/app/features/settings/security/security-settings.html`
- `web/src/app/features/settings/security/security-settings.spec.ts`

Problem:
The focused element was removed in three places (regenerate button, "I saved them", revoked row),
so focus fell back to `<body>`.

Fix:
After a successful regenerate the codes title (`tabindex="-1"`) is focused; after "I saved them" the
TOTP input is focused; after a revoke (or a 404 "already revoked") the next row's revoke button,
else the previous one, else the Devices heading (`tabindex="-1"`) is focused. The revoke and
regenerate moves happen only when focus was actually lost (`document.activeElement` is `<body>`), so
a focus the user moved elsewhere meanwhile is kept. All moves run in `afterNextRender`. Tests in the
"keyboard focus" block assert `document.activeElement` for each case, including the
"leaves the focus where the user moved it" case.

Validation:
- `cd web && npm run -s check` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npm run -s check` - Pass: "src/app/api/generated is up to date", "All files pass linting", Test Files 24 passed, Tests 357 passed, build complete
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*' --tests '*OpenApiContractTest' ktlintCheck` - BUILD SUCCESSFUL; JUnit XML: 21 suites, 179 tests, 0 failures, 0 errors, 0 skipped (includes `RecoveryCodeWebTests` on Postgres via Testcontainers and `OpenApiContractTest`)

Full test suite:
- `sh scripts/check.sh` (= `.taskcheck`, server + web + android) - "all checks passed"

Lint/type-check/build:
- ESLint `--max-warnings=0`, e2e typecheck and `ng build` inside `npm run check` - Pass
- `./gradlew ktlintCheck` - Pass, nothing suppressed

`.taskcheck`:
- Pass

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
