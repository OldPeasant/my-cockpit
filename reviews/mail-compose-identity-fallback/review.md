# Code Review

Date: 2026-10-26

Task: mail-compose-identity-fallback - New mail starts From the account address unless an identity is default

Scope: `git diff 4c3b0c95d..ee86fc7b0` (task commit only, committed changes)

Requirements:
- tasks/mail-compose-identity-fallback.md (acceptance criterion 1)
- SPEC.md (mail accounts/identities, compose sections)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- Existing code: DraftService, DraftController, MailIdentityController, web settings identities page

Git range:
- Branch: main
- HEAD: ee86fc7b05cc476b97050891c3f1835378e55cfd
- Base/start: 4c3b0c95d405b6e825f2dfd11a232b717b9eb851
- Commits reviewed: 1 (ee86fc7b0 "mail: new draft is From the account address unless an identity is default")

Summary: 0 P0, 0 P1, 1 P2, 1 P3

Correctness check: `DraftService.identity` now returns the default identity or null when no
`identityId` is given; `compose` maps null to `MailAddress(account.emailAddress, account.displayName)`
(DraftService.kt:338-341), and the Reply-To of an identity is not applied in that case. An explicit
`identityId` from another account is still rejected (DraftWebTests covers it). `respond` keeps
preferring the identity the original was addressed to, then falls through to the same default/account
address logic. The new GreenMail test (DraftComposeGreenMailTests.kt:396-414) covers both branches of
the criterion, including the actual From header of the sent message. The class is tagged `external`, so
it runs in `./gradlew integrationTest`, not in `check`; the criterion's evidence must name that run (no
evidence was recorded yet at the time of review).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Settings UI still tells the user new mail starts with the first identity**
    - File: `web/src/app/features/settings/mail/identities.html:4-6`, `web/src/app/features/settings/mail/identities.html:173` (asserted in `web/src/app/features/settings/mail/identities.spec.ts:318-327`)
    - Category: Integration
    - Problem: The hint reads "Once there is one, new mail starts with the default identity, or with the first one when none is the default, instead of {{ account().emailAddress }}" and the default toggle hint reads "Without a default, new mail starts with the first identity in the list." After this change, without a default identity new mail is From the account address, so both texts now describe the removed behavior.
    - Why it matters: Users get told the opposite of what the server does, exactly for the case this task fixed (mail-identities CR-2). The file is outside this task's `touches`, so it will not be corrected unless someone tracks it.
    - Suggested fix: Update both texts (and the spec assertions) to say new mail starts with the default identity, else the account's own address; either in this task after noting the extra touch, or via a follow-up task (`tasks.py new ... --touches web/src/app/features/settings`).

## P3 Low

- [ ] **CR-2 | API descriptions of the identityId default omit the account-address fallback**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftController.kt:385`, `:447`, `:210`, `:224`, `:238`
    - Category: Quality
    - Problem: The schema descriptions say "default: the account's default identity" and "else the default"; the KDoc of `respond` was updated to "else the account address", but the OpenAPI-facing descriptions were not. With no default identity (now the normal case once a non-default identity exists) the result is the account address, with `identityId: null`.
    - Why it matters: `api/openapi.yaml` is the contract clients are generated from; it should state the null/account-address case now that it is the defined behavior.
    - Suggested fix: Append ", else the account address" to these descriptions and regenerate `api/openapi.yaml` (per `api/README.md`).

## Review conclusion

Material issues found; fix before completing the task. The server change satisfies the acceptance
criterion and is correctly tested; CR-1 (stale user-facing text contradicting the new behavior) should
be fixed or tracked as a follow-up task before completion, CR-2 is optional cleanup.
