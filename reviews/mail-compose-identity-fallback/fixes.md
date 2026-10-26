# Fix Report

Date: 2026-10-26

Task: mail-compose-identity-fallback - New mail starts From the account address unless an identity is default

Scope: current task; findings CR-1 and CR-2 from `reviews/mail-compose-identity-fallback/review.md`
(base 360aa341d, task commits 97ddd5d2b and 6305ce9df). Touches outside the task's `touches`
(web settings, API descriptions, generated files) were approved by the parent worker and recorded as
an assumption in the task file.

The parent worker had already drafted the edits for both findings in the working tree. The fixer
checked them against the server behavior (`DraftService`: the default identity, else the account's
display name + address; `respond`: the identity the original was addressed to, else the default,
else the account address). It then regenerated the contract and the client with the documented
commands and confirmed the regeneration left no diff beyond the draft. The fixer made no further
code changes.

## Fixed findings

### CR-1 - Settings UI still tells the user new mail starts with the first identity

Files changed:
- `web/src/app/features/settings/mail/identities.html`
- `web/src/app/features/settings/mail/identities.spec.ts`

Problem:
The `@@mailIdentities.hint` and `@@mailIdentities.field.defaultHint` texts said that without a
default, new mail starts with the first identity. That was the behavior this task removed.

Fix:
The hint now reads "New mail starts with the default identity, or with {{ account().emailAddress }}
when none is the default." The default toggle hint now reads "Without a default, new mail starts with
the account's own address." The spec asserts both new texts and checks that "first one" and
"first identity" no longer appear. No other "first identity/one" wording is left under
`web/src/app/features/settings/mail/`. These i18n ids appear in no translation (`.xlf`/`.json`) files.

Validation:
- `cd web && npm run check` - Pass (175 test files passed, lint clean, build OK)

### CR-2 - API descriptions of the identityId default omit the account-address fallback

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftController.kt`
- `api/openapi.yaml` (generated)
- `web/src/app/api/generated/fn/mail-compose/forward-mail-message.ts` (generated)
- `web/src/app/api/generated/fn/mail-compose/reply-all-to-mail-message.ts` (generated)
- `web/src/app/api/generated/fn/mail-compose/reply-to-mail-message.ts` (generated)
- `web/src/app/api/generated/models/create-draft-request.ts` (generated)
- `web/src/app/api/generated/models/draft-content-request.ts` (generated)

Problem:
The OpenAPI descriptions of `identityId` (create draft, `DraftContentRequest`,
`CreateDraftRequest`, reply/reply-all/forward) did not mention the account-address fallback.

Fix:
", else the account address" was appended to these descriptions: the create-draft operation
description, both request `identityId` fields, and the three respond `identityId` query parameters.
`api/openapi.yaml` and the web client were then regenerated per `api/README.md`.

Validation:
- `cd server && UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'` - Pass. The
  regenerated `api/openapi.yaml` matched the draft exactly (`git diff --stat` unchanged: 16 lines).
- `cd web && npm run api:gen` - Pass. The regenerated client matched the draft exactly (same 5 files, 1 line each).
- `cd server && ./gradlew test --tests '*OpenApiContractTest' --tests '*DraftWebTests*'` - Pass
- `cd server && ./gradlew ktlintCheck` - Pass
- `cd web && npm run check` (includes `api:check`, the generated-client freshness check) - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests '*OpenApiContractTest' --tests '*DraftWebTests*'` - Pass
- `cd web && npm run check` (api:check, lint, e2e typecheck, unit tests, build) - Pass; 175 test files passed

Full test suite:
- Server full `./gradlew check`: not run by the fixer. Only description strings changed; ktlint and the
  contract/draft web tests were run.

Lint/type-check/build:
- `cd server && ./gradlew ktlintCheck` - Pass
- `cd web && npm run check` - Pass. It printed the existing initial-bundle budget warning, which this change does not affect.

`.taskcheck`:
- Not run by the fixer; the parent worker runs it before `done`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
