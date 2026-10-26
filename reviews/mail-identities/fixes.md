# Fix Report

Date: 2026-10-26

Task: mail-identities - Mail sending identities API and settings

Scope: current task; findings CR-1 to CR-4 of `reviews/mail-identities/review.md`. Changes stay within `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap`, its tests, and `web/src/app/features/settings/mail`. `mail/compose` was not changed (follow-up task `mail-compose-identity-fallback`).

## Fixed findings

### CR-1 — PATCH accepts an empty `email` and stores an identity without a From address

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailIdentityService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/MailIdentityWebTests.kt`

Problem:
`UpdateMailIdentityRequest.email` only had `@field:Email`, which accepts `""`, so `update` stored an empty From address.

Fix:
Added `checkEmail` next to `checkName` in `MailIdentityService`. `update` calls it for a present `email` (after the controller trims it) and throws `InvalidRequestException("email", "must not be blank")`, which is a 400 Problem Details with a field error. The annotations did not change, so neither `api/openapi.yaml` nor the generated client changed (`npm run api:check` passes). The test `an update cannot empty the address of an identity` was kept as it was and now passes. A whitespace-only case was added to `invalid requests are 400 with field errors`.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests '*MailIdentity*'` — Pass (MailIdentityWebTests: 19 tests, 0 failures)

### CR-2 — The settings hint says the account's address stays available

Files changed:
- `web/src/app/features/settings/mail/identities.html`
- `web/src/app/features/settings/mail/identities.scss`
- `web/src/app/features/settings/mail/identities.spec.ts`

Problem:
The hint said the account's address "is always available". In fact, once identities exist, new mail starts with the default identity, or with the first one when none is the default.

Fix:
Reworded the hint (`@@mailIdentities.hint`) to say this. Added a help line under the "Start new mail with this identity" toggle (`@@mailIdentities.field.defaultHint`): "Without a default, new mail starts with the first identity in the list." The toggle and the help are wrapped in a small grid (`.default-toggle`). There are no translation files to update. Added the test `says that new mail starts with the default or the first identity`.

Validation:
- `cd web && npm run check` — Pass

### CR-3 — A failed change does not reload the list, so a retry after 412 is stale

Files changed:
- `web/src/app/features/settings/mail/identities.ts`
- `web/src/app/features/settings/mail/identities.spec.ts`

Problem:
`run()` returned on error without `refresh()`, so retries kept sending the cached version.

Fix:
On an `ApiError` with status 412 or 404, `run()` now calls `refresh()` after it sets the error. A 412 here shows its own message, "It was changed elsewhere. The list now shows the current state; try again.", instead of the shared "Reload the page" text, because the list has already been reloaded. Updated the 412 test: it now expects the reload, and the retry sends `If-Match` with the new version. Added a 404 test: removing an identity that was already removed elsewhere reloads the list.

Validation:
- `cd web && npm run check` — Pass

### CR-4 — Edit/Add stay enabled while a save runs

Files changed:
- `web/src/app/features/settings/mail/identities.html`
- `web/src/app/features/settings/mail/identities.spec.ts`

Problem:
Edit, Add and Remove stayed clickable during a save. When the save finished, it closed any form opened in the meantime.

Fix:
`[disabled]="saving()"` on Edit and Remove, and `editing() === 'new' || saving()` on Add. Added the test `disables Add, Edit and Remove while a change is saving`, which also checks that the buttons are enabled again afterwards.

Validation:
- `cd web && npm run check` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintCheck test --tests '*MailIdentity*'` — Pass (19/19)
- `cd web && npm run check` (api:check, lint --max-warnings=0, e2e typecheck, Vitest, build) — Pass (175 files, 3652 tests)

Full test suite:
- Covered by `.taskcheck` below.

Lint/type-check/build:
- ktlintCheck — Pass; `ng lint --max-warnings=0` — Pass; `ng build` — Pass

`.taskcheck`:
- `sh scripts/check.sh` — see the fixer's final message to the parent worker for the result

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
