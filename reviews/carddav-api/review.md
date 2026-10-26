# Code Review

Date: 2026-10-26

Task: carddav-api - CardDAV accounts: REST API, web settings UI and backup

Scope: committed changes of task carddav-api, `git diff 728a43f4b..4a56280f3`. This is the final review of the whole task at revision 4a56280f3. Commit 4a56280f3 is the delta since the last review (revision caa413abf), and this review read it line by line: `git diff caa413abf..4a56280f3`. The delta changes:
- `web/src/locale/messages.xlf` and `messages.de.xlf` (fix for CR-7);
- `contact-detail.ts`: the error guard in `loadCardDavAccounts` (test-writer finding TW-1);
- `contact-detail.spec.ts` and `contacts-page.spec.ts`;
- `tasks/` and `reviews/` files only.

The generated files `api/openapi.yaml` and `web/src/app/api/generated/` were only checked against the code. CR-1 to CR-6 come from the first review (revision e84697ffd). CR-7 comes from the re-review (revision caa413abf).

Requirements:
- tasks/carddav-api.md acceptance criteria and its six recorded assumptions (two added since the last review: `ContactService.validateNew` outside `touches`, and the locale files outside `touches` for CR-7)
- AGENTS.md project notes and conventions
- docs/ARCHITECTURE.md conventions: i18n (lines 242-246) and modules
- Parent instructions: review the delta, confirm CR-7 is resolved, and give the final status for the whole task
- reviews/carddav-api/fixes.md (read as a claim, checked against the code)

Git range:
- Branch: main
- HEAD: 4a56280f381ac2bd862c2753c6243880954e6476
- Base/start: 728a43f4baff41729fbf439a0aa1aadc6ee3a17a
- Commits reviewed: 5 (0c47dc0f4, 61a4d3518, 55bf0b3c3, caa413abf, 4a56280f3)
- Uncommitted changes: none at the start of the review

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Status of the earlier findings, checked against the current code:
- CR-1 (no server tests for the REST endpoints): resolved, verified in the previous review. `CardDavAccountWebTests` has 19 tests covering:
    - 201 with Location on add;
    - 400 for missing or blank name and URL fields;
    - 404 on get, delete, sync and create-contact;
    - the NEVER, OK and ERROR state mapping with lastError;
    - 201 with ETag and Location when a contact is created;
    - 409 with the CardDAV detail;
    - no `password` key and no stored password in any response;
    - the redacting toString.
- CR-2 (a 409 for a new contact was shown as an edit conflict): resolved, verified in the previous review. `contact-detail.ts` takes the conflict branch only when `!this.isNew()`, and a spec covers it.
- CR-3 (backup secret path untested): resolved, verified in the previous review. `ContactsBackupContributorTests` covers:
    - export with and without the passphrase;
    - import with and without the passphrase;
    - no secret in the logs;
    - the restore warnings;
    - an account without links.

  `ContactsBackupFixture` is included in `BackupRoundTripTest`.
- CR-4 (no web specs for the panel and "Save to"): resolved, verified in the previous review, with specs in `carddav-accounts.spec.ts`, `contact-detail.spec.ts` and `contact-form.spec.ts`. The delta adds:
    - a `contacts-page.spec.ts` test that the panel renders on the page with the ERROR state and lastError;
    - two `contact-detail.spec.ts` tests for older account answers, one successful and one failed.
- CR-5 (validation ran after discovery): resolved, verified in the previous review. `CardDavContactsSync.createContact` calls `service.validateNew(fields)` after the account lookup and before discovery. A regression test covers this.
- CR-6 (unused `changed` output): resolved, verified in the previous review.
- CR-7 (new UI strings missing from the locale files): resolved in 4a56280f3. Verified:
    - `messages.xlf` now contains all 24 new ids: the 21 `contacts.carddav.*` ids, `contacts.saveTo`, `contacts.saveTo.here` and `contacts.saveTo.carddav`. Apart from these, the only changes in that file are moved units and new context line numbers. One source text changed, for `contacts.retry`, described below.
    - `messages.de.xlf` has a unit with `state="translated"` for each of the 24 ids. Each source matches `messages.xlf` exactly, including leading and trailing spaces. The German is informal ("Gib einen Namen ... ein", "Füge eines hinzu"). Every `INTERPOLATION` placeholder is kept, including the `equiv-text`.
    - fixes.md reports that `npm run build:de` and `messages-de.spec.ts` pass. This review did not re-run them.
    - The last part of the fix changed the `contacts.retry` source and leaves a fragile spot. See CR-8.
- TW-1 (an older failed accounts request cleared the current list): resolved. In `contact-detail.ts:264`, the catch branch now sets the list to empty only when `seq === this.loadSeq`, the same check as the success path at line 261. `load()` (line 124) increments `loadSeq` on every route load, so a stale request cannot write to the list, whether it succeeds or fails. The new spec "ignores an older accounts request that fails after the current one answered" covers this:
    - the stale request is kept, while a navigation to an existing contact and back starts a newer one;
    - the newer request answers first;
    - the spec then fails the stale request with a 500 and asserts the select still offers both accounts.

  "ignores an older accounts list that answers after the current one" covers the success path.

Also checked, no finding (carried over from the previous review and still valid at 4a56280f3, because the delta does not change these files):
- The CardDavException messages that reach the 409 detail are fixed texts with HTTP status codes only. They contain no credentials, URLs with user info, or contact data.
- `ContactService.validateNew` reuses the private validation rules rather than duplicating them. `createSynced` still validates on its own. It is now recorded as an assumption.
- Web race handling:
    - `syncNow` and `remove` merge into the current list;
    - a per-account busy guard blocks repeated actions on the same account;
    - `loadCardDavAccounts` ignores results from an earlier route, on both its success and error paths.
- The OpenAPI paths and operation ids, `writeOnly` on the password, and the generated client functions match the controller.
- The restore warnings follow the CalDAV convention in `CalendarBackupContributor`.
- `python tasks.py verify carddav-api` evidence is the parent worker's job and is not reviewed as a finding here.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-8 | `@@contacts.retry` now has two different source texts, so the locale check depends on extraction order**
    - File: `web/src/app/features/contacts/carddav-accounts.html:22`
    - Category: Quality
    - Problem: the new panel uses `i18n="@@contacts.retry"` with the inline text `Try again`. The three existing uses wrap the text across lines, so after whitespace collapsing their text is ` Try again `:
        - `contacts-page.html:120`;
        - `contact-detail.html:19`;
        - `contact-detail.html:164`.

      One custom id now has two different contents. fixes.md (CR-7 section) confirms that `npm run i18n:extract` warns about a duplicate message. The extractor took `Try again` from `carddav-accounts.html` because that file comes first, and the German source was changed to match. `messages-de.spec.ts` compares the sources without trimming them: `text()` at line 21 and the check at line 88. If the extraction order changes, for example because a file is added or renamed, `messages.xlf` gets ` Try again ` back and the locale spec fails on an id nobody touched. At runtime, translations are looked up by id, so German users still see "Erneut versuchen". There is no visible defect today.
    - Why it matters: this task introduced a fragile spot in the i18n check and an extractor warning. It can break a later, unrelated task's `.taskcheck` in a confusing way.
    - Suggested fix: use a separate id for the panel's button (for example `@@contacts.carddav.retry`) with its own German unit, and restore the `contacts.retry` source in both xlf files to the extracted ` Try again `. Alternatively, write the panel's text with the same surrounding whitespace in a form Prettier keeps. Then re-run `npm run i18n:extract` and check that it no longer warns about a duplicate message.

## Review conclusion

No material issues found. CR-1 to CR-7 and TW-1 are resolved in revision 4a56280f3. The only open item is the low-priority CR-8. It causes no visible defect, and the parent worker may fix it or record it as accepted.
