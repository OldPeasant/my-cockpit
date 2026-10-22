# Code Review

Date: 2026-10-22

Task: contacts-google - Google contacts sync

Scope: the contacts-google task commits c9dab9bcb (implementation) and 2393d1961 (tests), reviewed as `git diff 5708c4f25..2393d1961`. 5708c4f25 is the parent of c9dab9bcb. The parent named base e4b8b10d0, but the task commits were rebased. `git range-diff e4b8b10d0..ea5f42575 5708c4f25..2393d1961` shows the patches are identical to 14cf34e2b/ea5f42575 on local main. The working tree differs from 2393d1961 only in `tasks/contacts-google.md`. Reviewed revision: 2393d1961.

Requirements:
- tasks/contacts-google.md: acceptance criteria and the six ASSUMPTION notes
- SPEC.md section 8.3 (contacts, GOOGLE two-way via People API) and section 4.2 (Trash retention, optimistic locking)
- AGENTS.md "Project notes": module and migration conventions, logging rules, fictitious test data
- docs/ARCHITECTURE.md and docs/QUALITY.md
- Reference: server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google/, plus the existing contacts domain (ContactService, ContactTrashContributor, TrashService)

Git range:
- Branch: main
- HEAD: 2393d1961 (reviewed revision; local main HEAD ea5f42575 has the same patches)
- Base/start: 5708c4f25
- Commits reviewed: 2

Summary: 0 P0, 1 P1, 2 P2, 3 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Any local edit deletes Google-only data at Google (year-less birthdays, extra name/address/organization parts)**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GooglePersonMapping.kt:28`, `:96-143`. Used at `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSync.kt:183-186`.
    - Category: Correctness
    - Problem: `UPDATE_FIELDS` names `names,emailAddresses,phoneNumbers,addresses,organizations,birthdays,biographies`. For these fields `updateContact` replaces the whole list at Google. `toPerson` rebuilds each list only from the cockpit contact. The pull drops data the cockpit does not model: year-less birthdays (`birthday()` returns null when there is no year), `middleName`, `honorificPrefix/Suffix`, phonetic names, address `extendedAddress`/`poBox`, an address that only has `formattedValue`, organization `department`, extra names and organizations, and email `displayName`. So the first local edit of any field of such a contact, for example a note, deletes this data at Google. The recorded fixture already shows it: person `people/c1002` has the year-less birthday `{"month": 7, "day": 3}`. Editing that contact pushes `"birthdays": []`, and Google removes the birthday. The Google address book is also the user's phone address book, so the loss shows up outside the cockpit. The assumption says "birthdays only with a year" and "(an update replaces those lists at Google)". It does not say that existing Google data is erased when the user edits an unrelated field. A human has not confirmed that consequence.
    - Why it matters: Silent, permanent data loss at the provider, triggered by a normal edit in a two-way sync.
    - Suggested fix: Before an update, merge into the Person Google last sent instead of rebuilding it. Either store the last pulled Person JSON for each link, or GET the person with its etag before the PATCH. Overwrite only the sub-fields the cockpit models and keep the others (year-less birthdays, extra parts and entries). A simpler option is to send in `updatePersonFields` only the fields the local change touched, by comparing against a snapshot taken at the last sync. Add a test that edits the notes of `people/c1002` and asserts that the PATCH keeps the year-less birthday. Record the decision with `tasks.py assume` if it is kept as is.

## P2 Medium

- [ ] **CR-2 | Derived or synthetic display names are pushed to Google as the person's name**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GooglePersonMapping.kt:103-109` (together with `:71-77`)
    - Category: Correctness
    - Problem: When a contact has no given or family name, `toPerson` sends `displayName` as `names[0].unstructuredName`. That display name is often one the cockpit made up on pull: the first phone number (`people/c1002` becomes "+41 44 000 00 02"), the organization (`people/c1004` becomes "Fictional GmbH"), or `UNNAMED` ("Unnamed contact"). The first local edit of such a contact then gives the Google person a name it never had. A company-only contact gets its company as its person name, a phone-only contact is named after its number, and some contacts are literally named "Unnamed contact".
    - Why it matters: Changes the user's Google data in ways the user never asked for. The result is visible in Google Contacts and on the phone.
    - Suggested fix: Send a name only when the contact has a real name: given or family name, or a display name the user set that differs from the derived one. Otherwise send `names: []`, or leave `names` out of `updatePersonFields`. Add a test for an organization-only or phone-only contact.

- [ ] **CR-3 | A contact purged from the Trash before the next push stays at Google and comes back as a new contact**
    - File: `server/src/main/resources/db/migration/contacts/V202610221100__contacts_google_sync.sql:20`. See also `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSync.kt:118-133` and `server/src/main/kotlin/ch/sonensei/cockpit/contacts/infra/ContactItems.kt:61-66`.
    - Category: Correctness
    - Problem: A trashed GOOGLE contact is deleted at Google only by the next push, which finds it through its `contacts_google_link` row. `contacts_google_link.contact_id` is `on delete cascade`, so purging the contact deletes the link. A purge can be the user's "delete permanently", or the 30-day `TrashPurgeJob` while syncs fail (for example "has to be connected again"). After that, `deleteContact` is never sent. The People API also lists everything again once the sync token expires (Google documents that sync tokens expire 7 days after the full sync) or when the person changes. At that point `store()` finds no link and no contact with that `externalId`, and `createSynced` imports the person again. A contact the user permanently deleted comes back.
    - Why it matters: Deleted data reappears, and the local delete never reaches Google. In a two-way sync this breaks the user's delete intent.
    - Suggested fix: Make sure the Google delete happens or is remembered when a linked contact is purged. Options: keep a tombstone row (connection_id and resource_name, with no contact FK) that the next push deletes at Google; or listen for the purge or do the `deleteContact` before the purge; or keep `resource_name` in a separate pending-delete table. Add a test: trash, purge, sync, then assert `DELETE ...:deleteContact`.

## P3 Low

- [ ] **CR-4 | Resource names from the pull are not validated, unlike those from create; one bad name stops every later run**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSync.kt:161-162`, `:304`, `:338`, `:178-183`
    - Category: Quality
    - Problem: The comment at line 161 says the name is "Checked now, so a later push or pull never puts an odd resource name into a URL". But `apply`/`store` save any `resourceName` from a listing without calling `GoogleContactsClient.resourcePath`. If such a name is pushed later, `resourcePath` throws `GoogleContactsException` inside `push()`. That aborts the whole run, pull included, on every scheduled run. The URL stays safe because the check still happens before the call. The problem is that one contact stops the account's sync for good instead of being skipped. On create, the check runs after Google has already created the person. If it throws, no link is stored, and the next run creates the person again.
    - Why it matters: Resilience. The run fails instead of skipping one contact, and the code comment is misleading.
    - Suggested fix: Validate in `apply()` (skip and count the person), and validate in `create()` before `return rejected`. In `update`/`delete`, treat an invalid stored name as a failed write for that contact, not a run-level exception.

- [ ] **CR-5 | A display name set by the user for a named contact is never pushed and is overwritten by the next Google change**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GooglePersonMapping.kt:104-109`, `:71-72`
    - Category: Correctness
    - Problem: If the contact has a given or family name, only those are sent, and `names.displayName` is output-only at Google. A local display name such as "Bobby" for Robert Smith never reaches Google. The pull then sets `displayName` back to Google's derived one the next time the person changes there.
    - Why it matters: A local edit is silently lost later, with no conflict at all.
    - Suggested fix: Map a custom display name to a Google field (for example `nicknames`), or record with `tasks.py assume` that the display name of a named GOOGLE contact is derived from Google.

- [ ] **CR-6 | Untested paths: `syncAll` connection filter, 412 stale etag, re-read 404, and contacts skipped on pull**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSyncTests.kt` (the tests call only `sync(CONNECTION)`)
    - Category: Tests
    - Problem: No test covers `syncAll()` choosing GOOGLE + CONNECTED + contacts-scope connections (`GoogleContactsSync.kt:63-68`). Also untested: the HTTP 412 branch of `isStaleEtag`, the 404 on the re-read after a stale etag (`:207`), and a Google person that fails validation on pull being skipped and counted (`:309-316`).
    - Why it matters: The scope and status filter decides which accounts are synced, and the failure branches are the ones most likely to regress.
    - Suggested fix: Add focused tests. Stub `OAuthConnectionService.list()`, or use a second connection without the contacts scope, and assert that only the eligible connection is synced. Add WireMock cases for the 412, the GET 404, and a person with more than 100 entries or an overlong value.

## Review conclusion

Material issues found; fix before completing the task.

Notes, not findings: the parts that worked as they should were checked against the code.
- People API paths, methods and parameters (`connections.list` with `requestSyncToken`/`syncToken`/`pageToken`, `:updateContact` with etag, `:createContact`, `:deleteContact`).
- Handling of an expired token (400 `EXPIRED_SYNC_TOKEN` or 410, with no retry loop).
- Echo suppression by etag, and version bookkeeping with no push loops.
- HTTP calls kept outside transactions, a per-contact transaction, and per-connection locking.
- Query values encoded through URI template variables, and safe messages for errors and logs (ids and statuses only).
- Migration location and naming.
- Postgres via `PostgresTestBase`. Gradle results are the parent's run at 2393d1961, not rerun here.

`tasks.py verify contacts-google` shows both criteria unverified so far; the parent still has to record that evidence.
