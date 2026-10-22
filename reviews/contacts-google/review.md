# Code Review

Date: 2026-10-22

Task: contacts-google - Google contacts sync

Scope: round 2 on HEAD dff44523b. Reviewed `git diff f558cf46a..dff44523b`, excluding `reviews/` and `tasks/`. The task commits are 2dd849d18, 369c64b4e, 0a8cd16a9, 2661e7ba2 and dff44523b. This round checks the fixes for the round 1 findings CR-1 to CR-6 (`reviews/contacts-google/fixes.md`) and looks for new issues. The working tree also has uncommitted changes to `GoogleContactsSyncTests.kt` (+165 lines, presumably from the test-writer running in parallel). Those changes are outside this committed-code review.

Requirements:
- tasks/contacts-google.md: the two acceptance criteria and the nine ASSUMPTION notes, including the round 1 decisions on CR-1, CR-2, CR-3 and CR-5
- SPEC.md section 8.3 (contacts, GOOGLE two-way via People API) and the backup notes (external data is re-synced)
- AGENTS.md "Project notes": module boundaries, Flyway location and naming, logging rules, fictitious test data
- docs/ARCHITECTURE.md and docs/QUALITY.md
- Existing contacts domain: ContactService (display name derivation, normalization, limits), ContactItems (purge), and RestoreService (database wipe by truncate)

Git range:
- Branch: main
- HEAD: dff44523b6c544b4e0d95580bf27faf038cebfd4
- Base/start: f558cf46a1923daf70c0c7e5d2d44ee774dc6761 (equal to `git merge-base HEAD origin/main`)
- Commits reviewed: 5

Summary: 0 P0, 0 P1, 1 P2, 2 P3

## Verification of the round 1 fixes

- CR-1 (Google-only data erased by an edit): fixed. `update()` reads the person first, and `GooglePersonMapping.toUpdate` sends only the field groups that differ. Each sent group is merged into Google's entries, so middle names, year-less birthdays, P.O. boxes, departments and unreadable entries stay. Mapping and WireMock tests cover this. One remaining edge case is CR-3 below.
- CR-2 (derived display names pushed as names): fixed for the cases tested (first push of phone-only and organization-only contacts, and creates). A related path is still open; see CR-1 below.
- CR-3 (purged contact comes back): fixed. The link is now a tombstone (`on delete set null`). The push deletes the person at Google, the pull skips it, and `removedAtGoogle` clears it. A backup restore wipes the database by truncate, so it does not create tombstones. The migration was edited in place; it is not on origin/main (`git ls-tree origin/main` has no contacts_google file).
- CR-4 (resource name validation): fixed in `apply`, `create`, `update` and `delete`. Each is a failure for that contact only, with tests.
- CR-5: handled by the recorded assumption (the display name of a named contact is derived by Google).
- CR-6: the tests now cover the 412 PATCH, the re-read 404, the GET 404 before a write, skipped persons and the `syncAll` filter (`GoogleContactsSyncUnitTests`).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A display name that went stale after a local field edit is later pushed to Google as the person's name**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GooglePersonMapping.kt:185-211` (`namesChanged`, `mergeNames`), together with `server/src/main/kotlin/ch/sonensei/cockpit/contacts/domain/ContactService.kt:182-183`
    - Category: Correctness
    - Problem: Take a GOOGLE contact without given or family name whose display name the cockpit derived: the organization, the first email, or the phone number. `ContactService.apply` keeps the stored display name when an update does not send one, so the name is not derived again. Example with organization-only `people/c1004` ("Fictional GmbH"):
        1. The user changes the organization to "Other GmbH". The first push compares against Google's current person, which still has "Fictional GmbH", so `names` is not sent. This is the case the test `a contact whose organization names it sends no name when the organization changes` covers.
        2. The PATCH echo is skipped on pull (same etag), so the contact keeps the display name "Fictional GmbH".
        3. On the next unrelated local edit (for example a note), `toChanges(current)` gives organization "Other GmbH". `namesChanged` then compares "Fictional GmbH" with "Other GmbH" and returns true.
        4. In `mergeNames`, `keep` is true because "Fictional GmbH" differs from `derivedName(contact)` = "Other GmbH". The PATCH therefore includes `names: [{"unstructuredName": "Fictional GmbH"}]`.

      The same happens to a phone-only contact after its number changes (its old number becomes its Google name) and to an email-only contact after its first email changes.
    - Why it matters: This is the round 1 CR-2 defect again (a name the user never gave is written to Google, and it shows in Google Contacts and on the phone), reached through a two-step path the current tests do not exercise.
    - Suggested fix: Do not treat a display name as user-given when it equals a name the cockpit derived. For a contact without given or family name, compare the display name with both `derivedName(contact)` and the name derived from `base` (Google's current organization, email or phone), and only then decide `namesChanged` / `keep`. An alternative is to re-derive the stored display name when the sync sees that it equals the previously derived value. Add a WireMock or mapping test with two consecutive edits: change the organization (or phone), then edit the note, and assert that `updatePersonFields` does not contain `names`.

## P3 Low

- [ ] **CR-2 | Editing a field group of a person with more than MAX_ENTRIES entries deletes the overflow entries at Google**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GooglePersonMapping.kt:258-289` (`merge`, line 288), with `limit` at `:430-439`
    - Category: Correctness
    - Problem: On pull, `toChanges` caps emails, phones and addresses together at `ContactService.MAX_ENTRIES` (100). `merge` keeps only the entries matched to a contact value plus the entries that cannot be read (`read[it] == null`). A readable entry that `limit()` dropped matches no value, so it is left out of the PATCH. Example: a person with 60 emails and 50 phones is pulled with 40 phones. Changing any phone sends 40 phones, and Google deletes the other 10.
    - Why it matters: Silent data loss at Google. It only affects unusually large persons, hence P3.
    - Suggested fix: In `merge`, also keep readable entries beyond the ones `toChanges` read. For example, pass the readable entries the limit dropped through unchanged, the same way unreadable entries are kept. Add a mapping test with more than 100 entries.

- [ ] **CR-3 | A full listing right after a push can trash a contact the same run just created at Google**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSync.kt:352`, `:456-462`, with `create()` at `:162-188`
    - Category: Correctness
    - Problem: A run pushes first, then pulls. When the sync token has expired (or is null), the pull lists everything, and `removeMissing` trashes every link with a resource name that was not listed. That includes the link `create()` just stored for a contact restored from the Trash. Google documents that writes can take several minutes to show up in `people.connections.list` sync requests ("Incremental syncs are not intended for read-after-write use cases"). A person created seconds earlier may therefore be missing from the listing. The cockpit then moves the restored contact back to the Trash and clears its resource name, while the person still exists at Google. The next full listing imports that person as a new contact.
    - Why it matters: It undoes the user's restore and can create a duplicate. This needs an expired token and a create in the same run, hence P3.
    - Suggested fix: Leave out of `removeMissing` the resource names that this run's push created (or updated). Collect them in `push()` and pass them to `pull()`. Add a test: an expired token, a restored contact whose create succeeds, and a full listing without the new person; assert that the contact stays active and linked.

## Review conclusion

Material issues found; fix before completing the task.

CR-1 is a real correctness defect in the push path, the same kind round 1 asked to fix. CR-2 and CR-3 are edge cases. The parent can fix them or record them with `tasks.py assume`.

Notes, not findings: checked and found sound.
- Sync token handling: the token is sent on every page, an expired token (400 `EXPIRED_SYNC_TOKEN` or 410) triggers one full listing with no retry loop, and a failed page does not trash anything.
- Etag writes: a read before the write, Google wins on a stale etag (400 `FAILED_PRECONDITION` or 412, with a re-read), and echo suppression by etag.
- Deletes and tombstones, and per-contact transactions with HTTP calls kept outside them.
- Logging uses ids and statuses only: no tokens, no contact data, and the messages stored in `lastError` are safe.
- Module boundaries: contacts/google uses ContactService, ContactRepository and the core OAuth domain services only.
- Migration path and name (`db/migration/contacts/V202610221100__contacts_google_sync.sql`).
- Tests use PostgresTestBase, WireMock and recorded fixtures with fictitious data.

Gradle was not run in this review: sub-agents cannot run Gradle here. `python tasks.py verify contacts-google` shows both criteria still unverified, so the parent must run `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.google.*'` and record the evidence.
