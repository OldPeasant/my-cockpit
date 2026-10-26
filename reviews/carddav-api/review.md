# Code Review

Date: 2026-10-26

Task: carddav-api - CardDAV accounts: REST API, web settings UI and backup

Scope: committed changes of task carddav-api, `git diff 143022400..e84697ffd` (generated files `api/openapi.yaml` and `web/src/app/api/generated/` only checked against the code). An untracked `web/src/app/features/contacts/carddav-accounts.spec.ts` appeared during the review (test-writer work in progress). It is not committed, so it was not reviewed.

Requirements:
- tasks/carddav-api.md acceptance criteria and recorded assumptions
- SPEC.md §4.2, §8.3, §10.2, §14 (as summarized in AGENTS.md "Project notes")
- docs/ARCHITECTURE.md conventions (i18n, coverage), AGENTS.md conventions
- Parent instructions (focus areas: password handling, backup restore, Problem Details, OpenAPI, web correctness, tests)

Git range:
- Branch: main
- HEAD: e84697ffd6a839ce0c761c4d149467607441b1f0
- Base/start: 143022400e113435ccc2bd2da43ffe9da5c62db8
- Commits reviewed: 2 (2c6ee03c2, e84697ffd)

Summary: 0 P0, 1 P1, 3 P2, 2 P3

Verified as correct (no finding):
- Password is write-only. `AddCardDavAccountRequest.password` is `writeOnly` in OpenAPI and masked in `toString`. `CardDavAccountResponse` only exposes `hasPassword`. Nothing logs it.
- Backup: passwords are decrypted only when `writer.includesSecrets`, written only through `writer.secret(PASSWORDS, ...)` and re-encrypted with the target install's cipher on import. The accounts JSON has no password field.
- Restore order is FK-safe. Contacts are inserted first, then accounts, then links. Tombstone links (`contact_id` null) are kept. The restore wipe truncates with cascade.
- The generated OpenAPI paths, schemas, status codes and the web client match the controller annotations.

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | No server tests for the new REST endpoints (criterion 1)**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavAccountController.kt:40`
    - Category: Tests
    - Problem: no test under `server/src/test` exercises `/api/v1/contacts/carddav-accounts`. A search for `carddav-accounts` or `CardDavAccountController` finds nothing. `CardDavAccountServiceTests` predates the task and does not cover the new `get`. These behaviors are untested: 201 with `Location` on add; 400 Problem Details for a missing or blank name or a bad URL; 404 on get, delete, sync and create-contact for an unknown id; `sync.state`/`lastError` mapping (NEVER/OK/ERROR); 201 with ETag and `Location` on `POST /{id}/contacts`; 409 when discovery fails; that no response body ever contains the password.
    - Why it matters: criterion 1 ("password write-only", "status with lastError") cannot be verified by evidence, and a later DTO change could start returning the password with no test failing.
    - Suggested fix: add a MockMvc/web test class for the controller covering the cases above, including an assertion that the serialized JSON has no `password` key and does not contain the stored password.

## P2 Medium

- [ ] **CR-2 | A failed "Save to CardDAV account" shows a false "changed elsewhere" message and requests GET /contacts/new**
    - File: `web/src/app/features/contacts/contact-detail.ts:193`
    - Category: Correctness
    - Problem: by the recorded assumption, `POST /carddav-accounts/{id}/contacts` answers 409 when the account's address book cannot be discovered (`CardDavAccountController.kt:156`). In `save()`, `isConflict(error)` treats every 409 as an optimistic-lock conflict, even for a new contact. The user is told "it was changed elsewhere in the meantime... Save again to replace that version". Then `reloadContact()` calls `service.get('new')`, which the server answers with 400. That error is swallowed. The actual CardDAV problem in the Problem Details `detail` is never shown.
    - Why it matters: the user gets wrong advice and cannot see why saving to the CardDAV account fails. Saving again fails the same way. This affects criterion 2 ("new contact can target a CardDAV account") in exactly the failure case the 409 was designed for.
    - Suggested fix: apply the conflict branch only when `!this.isNew()`. For a new contact, show `message(error)` (the server's detail). Add a spec for createIn success and the 409 failure.

- [ ] **CR-3 | Backup secret path and restore warnings are untested**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/backup/CardDavBackup.kt:34`
    - Category: Tests
    - Problem: the round-trip test (`BackupRoundTripTest`) exports without a passphrase, so it only covers the case where the password is omitted. Nothing tests:
        - export with a passphrase: the password appears only in the secrets, and is in neither `contacts_carddav_accounts.json` nor the ciphertext;
        - import with the passphrase: the password is restored and usable after re-encryption, and `hasPassword` is true;
        - import without the passphrase: the password is null;
        - `restoreWarnings` (with or without secrets, singular or plural);
        - an install without accounts writes neither file.

      `ContactsBackupContributorTests` was not changed. The sibling modules have exactly these tests (`CalendarBackupContributorTests` "with a passphrase the credentials are only in the encrypted secrets..." and "import restores credentials with the passphrase...", and the same in `MailBackupContributorTests`).
    - Why it matters: criterion 3 names "password encrypted or omitted". The encrypt/decrypt path, which handles a secret, has no verification. A regression that leaks the plaintext into `data/` would go unnoticed.
    - Suggested fix: add tests to `ContactsBackupContributorTests` that mirror the calendar and mail credential tests.

- [ ] **CR-4 | New web panel and the "Save to" choice have no committed tests**
    - File: `web/src/app/features/contacts/carddav-accounts.ts:36`
    - Category: Tests
    - Problem: the committed diff adds no spec for `CardDavAccounts`: load and load error with retry; add including the client-side and server 400 field messages; the automatic first sync after add; sync now; confirm/cancel remove; per-account error; busy guard. `contact-detail.spec.ts` only asserts that `.save-to` is absent with no accounts. Choosing an account and saving through `createIn` is untested. An untracked `carddav-accounts.spec.ts` exists (test-writer in progress) and was not reviewed.
    - Why it matters: criterion 2 is web behavior. Without these specs, regressions such as CR-2 go unnoticed.
    - Suggested fix: commit the component spec, and add a contact-detail spec that selects an account and expects `POST /api/v1/contacts/carddav-accounts/{id}/contacts` (success and 409).

## P3 Low

- [ ] **CR-5 | Contact data is validated only after network discovery**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavAccountController.kt:154`
    - Category: Correctness
    - Problem: `sync.createContact` builds the `Run` first, which may run address book discovery over HTTP for an account not synced yet. Only then does `service.createSynced` validate the fields (for example, an empty contact). An invalid body for such an account first contacts the CardDAV server, and if discovery fails it answers 409 instead of 400.
    - Why it matters: the wrong status for invalid input, and a needless remote call within the request.
    - Suggested fix: validate the changes before discovery. For example, expose a validation step on `ContactService`, or call `createSynced` validation first. Or document the precedence.

- [ ] **CR-6 | `changed` output of CardDavAccounts is never used**
    - File: `web/src/app/features/contacts/carddav-accounts.ts:39`
    - Category: Quality
    - Problem: the component emits `changed` on every list update, and its doc comment says this is "so the page can offer them as a place for new contacts". But `contacts-page.html:64` uses `<app-carddav-accounts />` without binding it. `ContactDetail` loads the accounts on its own.
    - Why it matters: dead API with a misleading comment.
    - Suggested fix: remove the output and the comment, or bind it if it is needed.

## Review conclusion

Material issues found; fix before completing the task.
