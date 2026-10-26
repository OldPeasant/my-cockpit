# Code Review

Date: 2026-10-26

Task: mail-compose-identity-fallback - New mail starts From the account address unless an identity is default

Scope: `git diff 18921d4a9927f61a8d1dc0594620846472d5b247..0d764f4f0b7bc4d528daf07ae7b9cf35a791a75e` (committed changes only; final code including the fixes for the earlier review's CR-1/CR-2)

Requirements:
- tasks/mail-compose-identity-fallback.md (acceptance criterion 1, goal, recorded assumption approving the edits to web/src/app/features/settings/mail, api/openapi.yaml and the generated web client)
- SPEC.md (mail accounts/identities and compose sections)
- docs/ARCHITECTURE.md, AGENTS.md project notes (generated API files, i18n, lint conventions)
- Existing code: DraftService, DraftController, web compose page, web settings identities page

Git range:
- Branch: main
- HEAD: 0d764f4f0b7bc4d528daf07ae7b9cf35a791a75e
- Base/start: 18921d4a9927f61a8d1dc0594620846472d5b247
- Commits reviewed: 3 (756865aac, ee58a978d, 0d764f4f0)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Verification notes:
- Correctness: `DraftService.identity` (DraftService.kt:437-445) now returns the default identity or
  null when no `identityId` is given; `compose` (DraftService.kt:338-341) maps null to
  `MailAddress(account.emailAddress, account.displayName)`, so no identity's Reply-To leaks. An explicit
  `identityId` of another account is still rejected with a 400. `respond` keeps preferring the identity
  the original was addressed to and then falls through to the same default/account-address logic.
- The web compose page has no client-side identity preselection (it only passes back the draft's saved
  `identityId`), so no client path reintroduces the "first identity" fallback. A repository-wide search
  found no remaining "first identity" wording in server, API, web or Android sources.
- Earlier review CR-1 (settings hint text) is fixed in identities.html, and identities.spec.ts asserts
  the new wording and the absence of the old one. CR-2 (API descriptions) is fixed in DraftController.
  `api/openapi.yaml` and the generated web client match the new annotation text exactly.
- Tests: `a new draft is From the account address unless an identity is default` covers both halves of
  the criterion, including the From header of the actually sent message. `a reply or forward addressed
  to no identity ...` covers `respond`, including that no Reply-To is set. Both are in
  DraftComposeGreenMailTests, which runs in `./gradlew integrationTest` (GreenMail + Testcontainers), not
  in `./gradlew check`. When this review was written, `tasks.py verify mail-compose-identity-fallback`
  showed criterion 1 as unverified. The parent must record the integrationTest run as evidence, and
  tests.md must cover the final revision, which includes the web spec change from 0d764f4f0.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
