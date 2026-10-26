# Fix Report

Date: 2026-10-26

Task: carddav-api - CardDAV accounts: REST API, web settings UI and backup

Scope: findings CR-2, CR-5 and CR-6 of reviews/carddav-api/review.md, as handed over by the parent worker. For CR-6, the coordinator decided to remove the output and adapt the spec. The parent worker handles CR-1, CR-3 and CR-4 (missing tests) with the test-writer's files.

## Fixed findings

### CR-2 — A failed "Save to CardDAV account" shows a false "changed elsewhere" message and requests GET /contacts/new

Files changed:
- `web/src/app/features/contacts/contact-detail.ts`

Problem:
In `save()`, every 409/412 was handled as an optimistic-lock conflict. This included a new contact created through `POST /api/v1/contacts/carddav-accounts/{id}/contacts`, which answers 409 when the account's address book cannot be found. The user got the "changed elsewhere" advice, `reloadContact()` requested `GET /api/v1/contacts/new`, and the server's reason was never shown.

Fix:
The conflict branch now runs only when `!this.isNew()`. For a new contact, the error goes through the existing `message(error)` path, so the Problem Details `detail` is shown. The form stays open and nothing is reloaded. Editing an existing contact behaves as before.

Validation:
- `npx ng test --watch=false --include='src/app/features/contacts/**/*.spec.ts'` (in web) — Pass, 7 files, 124 tests. This includes the existing spec "shows why the account could not take the contact (409) and keeps the form", which was not edited.
- `npm run lint` (in web) — Pass

### CR-5 — Contact data is validated only after network discovery

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/domain/ContactService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavContactsSync.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavAccountWebTests.kt` (regression test added)

Problem:
`CardDavContactsSync.createContact` built the `Run`, which can run address book discovery over HTTP, before `ContactService.createSynced` validated the fields. For an account that had not been synced yet, invalid input caused a remote call and, if discovery failed, a 409 instead of a 400.

Fix:
- Added `ContactService.validateNew(fields)` (read-only transaction). It applies the changes to a transient contact and runs the existing private `validate`, so the rules are the same as for `create`/`createSynced`.
- `createContact` calls it after looking up the account and before building the `Run`. An unknown account is still a 404 first, so the unit test that calls `createContact(unknown, ContactChanges())` keeps its behavior. `createSynced` still validates as before.
- KDoc: `createContact` documents the InvalidRequestException.
- Regression test "POST contacts checks the fields before looking up the address book, so invalid input is 400, not 409": an account with an unreachable URL and no address book, `{}` gives 400 on displayName, a bad email gives 400 on emails[0].address. No contact is stored and `addressbook_url` stays null.

Note on scope: `ContactService.kt` is in `contacts/domain`, not in the task's `touches` list. It stays inside the contacts module, as the parent allowed. The private validation rules are not exposed any other way, so this was the smallest change that does not duplicate them.

Validation:
- `./gradlew test --offline --tests 'ch.sonensei.cockpit.contacts.carddav.CardDavAccountWebTests' --tests 'ch.sonensei.cockpit.contacts.carddav.CardDavContactsSyncUnitTests'` (in server) — Pass. CardDavAccountWebTests 19 tests including the new one, CardDavContactsSyncUnitTests 17.
- `./gradlew test --offline --tests 'ch.sonensei.cockpit.contacts.*' --tests 'ch.sonensei.cockpit.backup.*'` (in server) — Pass, 510 tests, 0 failures, 0 errors.
- `./gradlew ktlintCheck --offline` (in server) — Pass

### CR-6 — `changed` output of CardDavAccounts is never used

Files changed:
- `web/src/app/features/contacts/carddav-accounts.ts`
- `web/src/app/features/contacts/carddav-accounts.spec.ts`

Problem:
The component emitted `changed` on every list update. Its doc comment said this was so the page could offer the accounts for new contacts. But `contacts-page.html` uses `app-carddav-accounts` without binding the output, and `ContactDetail` loads the accounts itself.

Fix:
- Removed the `changed` output, its `emit` in `show()`, the unused `output` import, and the misleading sentence of the doc comment.
- The test-writer spec subscribed to the output. The coordinator decided to remove it anyway, so I adapted the spec. The subscription and the `emitted` variable are gone. A new helper, `ids()`, reads the `data-id` of the rendered `li.account` rows in order. The four assertions on emitted values now assert the rendered list instead:
  - after the load: HOME, WORK, OPEN;
  - after sync now: WORK, OPEN, with the synced account replaced in place;
  - after the removal: OPEN;
  - after the add and its first sync: HOME, NEW.
- No test was removed or skipped, and the test count is unchanged.

Validation:
- `npx ng test --watch=false --include='src/app/features/contacts/**/*.spec.ts'` (in web) — Pass, 7 files, 124 tests
- `npm run lint` (in web) — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- web: `npx ng test --watch=false --include='src/app/features/contacts/**/*.spec.ts'` — 124 passed
- server: CardDavAccountWebTests (19) and CardDavContactsSyncUnitTests (17) — passed

Full test suite:
- server: contacts and backup packages, 510 tests passed. The full `./gradlew check` was not run.
- `sh scripts/check.sh` — Not run (the permission was denied in this sub-agent)

Lint/type-check/build:
- `npm run lint` (in web) — Pass
- `./gradlew ktlintCheck --offline` (in server) — Pass. The compiler warnings about unnecessary `!!` in CardDavContactsSync.kt were there before this fix.

`.taskcheck`:
- Not run (permission denied for `sh scripts/check.sh`; the parent worker must run it)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed. The parent worker still has to run `.taskcheck`.
