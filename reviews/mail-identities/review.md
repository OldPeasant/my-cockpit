# Code Review

Date: 2026-10-26

Task: mail-identities - Mail sending identities API and settings

Scope: committed changes of the task, `git diff e3b8c6af HEAD` (commits 20fb5d9a server identity API + openapi + generated client, 2371f113 web Settings → Mail identities UI). Uncommitted test edits in the working tree were not reviewed.

Requirements:
- tasks/mail-identities.md (acceptance criteria and the two recorded assumptions)
- SPEC.md §7.1 (identities), §4.2 (API conventions: Problem Details, ETag/If-Match)
- docs/ARCHITECTURE.md, AGENTS.md project notes, docs/QUALITY.md
- Existing conventions in `mail/imap/MailAccountController.kt`, `core/web/ETags.kt`, `settings/mail/*`

Git range:
- Branch: main
- HEAD: 2371f1139ffafb2f0eaa7383b0f6b0f7faefcaed
- Base/start: e3b8c6af437aaca389959eda5c797105efde7cb3
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 2 P2, 2 P3

Checked and found in order: the routes, operation ids, request/response fields, required fields and status codes (201/204/400/404/409/412/428) in `api/openapi.yaml` match the controller, and the generated web client matches the yaml. ETag on GET/POST/PATCH, `If-Match` checked through `ETags.checkIfMatch` (428 missing, 412 stale) on PATCH and DELETE. Errors are Problem Details. Account scoping works: an identity of another account is 404 through `takeIf { it.accountId == accountId }`. Writes per account run one at a time under a `PESSIMISTIC_WRITE` lock on the account. The default flag is cleared and flushed before the new default is set, which respects the `mail_identity_default` partial unique index. An `UPDATED` event goes out for the identity that loses the flag. `CREATED`/`UPDATED`/`DELETED` events use type `MAIL_IDENTITY`. Address uniqueness ignores case, matching the `lower(email)` index. Backup already covers `mail_identity`. The web side uses OnPush, signal inputs, `If-Match` from the shown version, a 409 message, and a load error with retry, and form controls are labelled.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | PATCH accepts an empty `email` and stores an identity without a From address**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailIdentityController.kt:237`
    - Category: Correctness
    - Problem: `UpdateMailIdentityRequest.email` has only `@field:Email`. Hibernate's `EmailValidator` treats `""` as valid. `MailIdentityService.update` (`MailIdentityService.kt:95`, `:98`) then checks uniqueness and sets `identity.email = ""`. Nothing else checks it: `name` has `checkName`, but `email` has no equivalent. The column is `not null` but accepts `''`. `DraftService.compose` would then build `From` from an empty address for this identity, and it may be the default.
    - Why it matters: one API call produces an identity that cannot send mail. The create path rejects the same value with `@NotBlank`, so the two paths are inconsistent. The current UI never sends this because its form requires the field, but the API contract (`UpdateMailIdentityRequest.email`, `format: email`) is supposed to be safe for any client (Android).
    - Suggested fix: reject a blank `email` on PATCH with a 400 field error, for example `@field:Pattern(regexp = ".*\\S.*")` or a `checkEmail` next to `checkName` that throws `InvalidRequestException("email", "must not be blank")`. Add a case to `invalid requests are 400 with field errors`.

- [ ] **CR-2 | The settings hint says the account's address stays available, but adding any identity changes the From of new mail**
    - File: `web/src/app/features/settings/mail/identities.html:3`
    - Category: Correctness
    - Problem: the hint reads "{{ account().emailAddress }} is always available". In `DraftService.identity` (`DraftService.kt:442`), a new draft with no `identityId` uses the default identity, or else the first identity. The compose page (`mail-compose-page.ts`) has no identity picker. Once one non-default identity exists, every new mail is sent from that identity, and the user cannot pick the account address in compose. The task's second assumption records this server behavior, but the UI text says the opposite, and the change happens silently when the user adds an alias.
    - Why it matters: a user who adds, say, an `info@` alias gets all new mail sent from it without being told, and the settings text says this does not happen.
    - Suggested fix: within this task, reword the hint (and the "Start new mail with this identity" toggle help) to describe the real behavior: with identities present, new mail starts with the default identity, or the first one when none is the default. Keep the follow-up from the assumption (compose falls back to the account address when no identity is the default) as a separate task, since `mail/compose` is outside this task's touches.

## P3 Low

- [ ] **CR-3 | A failed change does not reload the list, so retrying after a 412 sends the same stale version again**
    - File: `web/src/app/features/settings/mail/identities.ts:169`
    - Category: Quality
    - Problem: `run()` returns on error without calling `refresh()`. After a 412 (`errorMessage` shows "changed elsewhere, reload the page"), Save and Remove keep using the cached `version`, so each retry fails again. The only ways out are a full page reload or closing and reopening the Identities panel.
    - Why it matters: the screen does not recover after a concurrent change, even though the component already has a non-hiding `refresh()`.
    - Suggested fix: on 412 (and 404), call `refresh()` after setting the error, so the next attempt uses the current version.

- [ ] **CR-4 | Edit/Add stay enabled while a save runs; when the save finishes it closes a form opened in the meantime**
    - File: `web/src/app/features/settings/mail/identities.ts:131`
    - Category: Quality
    - Problem: only the submit and confirm-remove buttons are disabled while `saving()`. If the user clicks Edit on another identity (or Add) during a save, the save finishes with `this.editing.set(null)` and closes the form the user just opened. `refresh()` then replaces the list underneath it.
    - Why it matters: a small loss of the user's input, which happens on slow connections.
    - Suggested fix: disable Edit, Add and Remove while `saving()`, or clear `editing` only when it still equals the identity that was saved.

## Review conclusion

Material issues found; fix before completing the task.
