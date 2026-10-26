# Code Review

Date: 2026-10-26

Task: carddav-api - CardDAV accounts: REST API, web settings UI and backup

Scope: committed changes of task carddav-api, `git diff 728a43f4b..caa413abf`. The generated files `api/openapi.yaml` and `web/src/app/api/generated/` were only checked against the code. This is a re-review of the final revision after the fixes in `reviews/carddav-api/fixes.md`. The previous review (CR-1 to CR-6) was done on revision e84697ffd.

Requirements:
- tasks/carddav-api.md acceptance criteria and its four recorded assumptions
- AGENTS.md project notes and conventions
- docs/ARCHITECTURE.md conventions: i18n (lines 242-246) and modules
- Parent instructions: check each earlier finding, the fix commits (ContactService.validateNew, the order in CardDavContactsSync.createContact, the 409 handling in contact-detail.ts save(), the removal of the `changed` output) and the added tests

Git range:
- Branch: main
- HEAD: caa413abf2fc48e8c1adca816cf3f6862621412b
- Base/start: 728a43f4baff41729fbf439a0aa1aadc6ee3a17a
- Commits reviewed: 4 (0c47dc0f4, 61a4d3518, 55bf0b3c3, caa413abf)
- Uncommitted changes: none at the start of the review

Summary: 0 P0, 0 P1, 1 P2, 0 P3

Earlier findings, checked against the current code:
- CR-1 (no server tests for the REST endpoints): resolved. `CardDavAccountWebTests` (55bf0b3c3, caa413abf) has 19 tests covering:
    - 201 with Location on add;
    - 400 for missing or blank name and URL fields;
    - 404 on get, delete, sync and create-contact;
    - the NEVER, OK and ERROR state mapping with lastError;
    - 201 with ETag and Location when a contact is created;
    - 409 with the CardDAV detail;
    - no `password` key and no stored password in any response;
    - the redacting toString.
- CR-2 (409 for a new contact shown as an edit conflict): resolved. `contact-detail.ts:195` takes the conflict branch only when `!this.isNew()`. Otherwise the Problem Details detail is shown and nothing is reloaded. The spec "shows why the account could not take the contact (409) and keeps the form" asserts the message, that no GET of `/contacts/new` is sent, the route, and the typed value.
- CR-3 (backup secret path untested): resolved. `ContactsBackupContributorTests` now covers:
    - export without secrets, and with a passphrase (the password is only in the encrypted secrets);
    - import with and without the passphrase;
    - no secret in the logs;
    - restore warnings, singular and plural;
    - no files without accounts;
    - an account without links.

  `ContactsBackupFixture` seeds two accounts, a link and a tombstone. `BackupRoundTripTest` finds the fixture automatically by classpath scan.
- CR-4 (no web specs for the panel and "Save to"): resolved. Added:
    - `carddav-accounts.spec.ts` with 14 tests: load, load error with retry, the three states, sync now, remove with confirm, add with its first sync, validation, 400 field errors, busy;
    - `contact-detail.spec.ts`: posting to the chosen account, staying local, failed account load, the 409 case;
    - `contact-form.spec.ts`: the "Save to" select.
- CR-5 (validation after discovery): resolved. `CardDavContactsSync.createContact` (line 116) calls `service.validateNew(fields)` after the account lookup, so an unknown account is still a 404 first, and before `Run(account)`, which can run discovery. `validateNew` runs the same `apply` and `validate` as `create`, without saving. The regression test uses an account at an unreachable URL with no address book. It passes only if validation comes first: if discovery ran first, the answer would be 409, not 400.
- CR-6 (unused `changed` output): resolved. The output, its emit, the import and the misleading comment are gone. The spec asserts the rendered rows instead.

Also checked, no finding:
- The CardDavException messages that reach the 409 detail are fixed texts with HTTP status codes only (CardDavClient.kt, CardDavContactsSync.kt). They contain no credentials, URLs with user info, or contact data.
- `ContactService.validateNew` is outside the task's `touches` but inside the contacts module. It reuses the private validation rules rather than duplicating them. `createSynced` still validates on its own, so a photo deleted between the two checks still gives a 400.
- Web race handling:
    - `syncNow` and `remove` merge into the current list when they finish, so a late sync result does not bring back a removed account;
    - the per-account busy guard blocks a second sync or remove of the same account;
    - `loadCardDavAccounts` ignores results from an earlier route.
- The OpenAPI paths and operation ids, `writeOnly` on the password, and the generated client functions match the controller.
- The restore warnings follow the CalDAV convention in `CalendarBackupContributor`.
- `python tasks.py verify carddav-api` shows no evidence recorded yet for the three criteria. The parent worker records it after this review, so this is not a finding.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-7 | New UI strings were not extracted and have no German translations**
    - File: `web/src/app/features/contacts/carddav-accounts.html:1` (also `carddav-accounts.ts:20`, `contact-form.html:5`)
    - Category: Integration
    - Problem: the task adds 24 new message ids: 21 `@@contacts.carddav.*` and `@@contacts.saveTo`, `@@contacts.saveTo.here`, `@@contacts.saveTo.carddav`. None of them are in `web/src/locale/messages.xlf` or `web/src/locale/messages.de.xlf` (a grep finds 0 matches in each). The existing contacts messages, such as `contacts.created` and `contacts.form.*`, are present in both files, so the files are otherwise kept in sync. docs/ARCHITECTURE.md lines 242-246 require running `npm run i18n:extract` and adding each `<target>` to `messages.de.xlf` after adding a message. `angular.json` sets `"i18nMissingTranslation": "error"`, so `npm run build:de` fails on these ids. `messages-de.spec.ts` cannot catch this, because it compares only the two xlf files and `messages.xlf` was not re-extracted.
    - Why it matters: the German build breaks. It is not part of `.taskcheck`, so the gap goes unnoticed until someone builds or ships the German locale. Until then the new panel is English-only, against the "every user-facing string translated to German" convention.
    - Suggested fix: run `npm run i18n:extract` in `web/` and add informal German ("du") targets for the 24 new units to `src/locale/messages.de.xlf`. Then run `npx ng test --include='src/locale/**'` and `npm run build:de`. `web/src/locale/` is outside the task's `touches`. The convention requires the change, so the parent should record that with `tasks.py assume` or keep the edit small.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task.
