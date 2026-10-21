# Fix Report

Date: 2026-10-21

Task: contacts-api - Contacts API

Scope: current task. Findings CR-1, CR-2 and CR-3 from `reviews/contacts-api/review.md`, plus the unfinished test-writer file `server/src/test/kotlin/ch/sonensei/cockpit/contacts/ContactEdgeCaseTests.kt` (compile and pass, and report or fix the production bugs it reveals).

## Fixed findings

### CR-1 — `q` filter matches JSON keys and labels, not only email addresses

Files changed:
- `server/src/main/resources/db/migration/contacts/V202610211010__contacts_email_search.sql` (new)
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/domain/ContactService.kt`

Problem:
The filter ran `lower(text(emails)) like %q%` on the whole jsonb document. So `q=address`, `q=label`, `q=null` or a label value such as `work` matched every contact that had an email.

Fix:
A new Flyway migration adds an immutable SQL function `contacts_email_matches(emails jsonb, pattern text)`. It checks `exists (select 1 from jsonb_array_elements(emails) e where lower(e->>'address') like pattern escape '\')`. The JPA specification calls it through `cb.function` (the Criteria API cannot express `jsonb_array_elements`). The display-name match and the escaping of the like pattern are unchanged. The migration is new and the applied one was not edited. Its version sorts after `V202610211000__contacts_model.sql`.

Validation:
- `ContactEdgeCaseTests` "q matches email addresses but not the labels or JSON keys around them" (`q=address`, `q=label`, `q=work` -> no match; `q=ULLA@EXAMPLE` -> match). It failed before the fix and passes now.
- `./gradlew test --tests 'ch.sonensei.cockpit.contacts.*'` — Pass

### CR-2 — Any PATCH fails with 400 once the contact's photo file is no longer active

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/domain/ContactService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/ContactEdgeCaseTests.kt`

Problem:
`validate` checked `photoFileId` against the drive on every update, even when the PATCH did not touch it. A contact whose photo had been moved to the Trash could not be edited at all.

Fix:
`validate(contact, checkPhoto)` now checks the photo only when the request contains `photoFileId` (`fields.photoFileId != null`, on both create and update). Setting a missing file explicitly is still a 400.

Validation:
- New test "a contact whose photo went to the Trash can still be changed": upload a file, create a contact with it, DELETE the file (204), PATCH `title` -> 200. A PATCH that sets the trashed file again -> 400 `photoFileId`. Pass.

### CR-3 — Photo file-name sanitizing is narrower than `FileNames.validate`

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/domain/VCardService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/ContactEdgeCaseTests.kt`

Problem:
`\p{Cntrl}` does not cover the C1 controls U+0080-U+009F, which `FileNames.validate` rejects. A card with such a character in FN and an embedded photo was skipped.

Fix:
The name now replaces `/`, `\` and every `Char.isISOControl()` character with `_`, the same rule as `FileNames`. The `UNSAFE_NAME` regex was removed. `FileNames` itself is not called because it belongs to the files module's domain, and contacts uses files only through `FilesService`.

Validation:
- New test "a C1 control character in the name does not cost the card its import": FN `Paul<U+0085>Next` with a PNG photo is imported with no skipped cards, and the file is named `Contact photo Paul_Next.png`. Pass.

### TW-1 — Export fails with 500 when a label contains `"` (found by ContactEdgeCaseTests)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/domain/VCardService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/ContactEdgeCaseTests.kt` (assertion added)

Problem:
The existing test-writer test "export keeps a free-text label with characters vCard parameters cannot hold" failed with 500. Labels are exported as TYPE parameters, and ez-vcard (vinnie `VObjectWriter.validate`) throws `IllegalArgumentException` for `"`, CR or LF in a parameter value. The API accepts such free-text labels, so `GET /contacts/export` could not export these contacts. This is in scope (vCard export criterion).

Fix:
`labelTypes` replaces `"` with `'` and CR/LF with spaces before the label is written as vCard types.

Validation:
- The test passes. It now also asserts the exported line `TEL;TYPE="office 'zurich': main":1`. ez-vcard writes types in lower case, as for every exported label.

### ContactEdgeCaseTests.kt — unfinished test-writer file

The file compiled. Of its tests, only the two above failed, and both failures were production bugs (CR-1 and TW-1), not test problems. No existing assertion was weakened or removed. Two tests were added (CR-2, CR-3) and one assertion (TW-1).

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` — Pass. Formatting changes were applied to the changed files only.
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.*'` — Pass: 45 tests (ContactEdgeCaseTests 34, ContactWebTests 11), 0 failures. Postgres via Testcontainers.

Full test suite:
- `cd server && ./gradlew check` — Pass: 208 test classes with no failures or errors, and the lint/static checks passed.

Lint/type-check/build:
- Included in `./gradlew check`. Pass.

`.taskcheck`:
- Not run. The parent worker runs `sh scripts/check.sh` before `done`. The web and Android parts are not changed by these fixes.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
