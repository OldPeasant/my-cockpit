# Fix Report

Date: 2026-10-22

Task: contacts-google - Google contacts sync

Scope: findings CR-1 to CR-6 in `reviews/contacts-google/review.md`, plus the failing test "a person restored at Google after it was deleted there comes back" (finding 0 from the parent). The parent approved all of them. Base: origin/main (0c5c6c405). The task commits were 1f552a462, dad0f2021 and e00e000cf (HEAD before the fixes). All changes stay inside the task's touches, plus the unpublished contacts migration that the parent allowed to be edited in place.

## Fixed findings

### 0 - A person restored at Google after it was deleted there did not come back (failing test)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSync.kt`

Problem:
Google listed the person again under its old resource name. `store()` then found the trashed contact that still had `externalId = people/c1003`. Because that contact had a link (with no resource name), the code did not adopt it, but it also did not clear its id. `createSynced` then failed on the unique external id.

Fix:
When no link has the resource name, the contact found by external id is handled in one of three ways:
- Active and unlinked (restored from a backup): it is adopted, as before.
- In the Trash, or linked to this connection: it gives up its external id (`updateExternalId(id, null)`), and the person gets a new contact.
- Linked to another connection: it keeps its id, and the person is skipped and counted, as before.

The parent's instruction said "linked" without saying which connection. Read literally, it would let one account take over the external id of another account's active contact. That breaks the existing, legitimate test "a person that cannot be stored is skipped and reported, the others are synced". So the give-up rule only applies to links of the same connection.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.contacts.google.*'` - Pass (the test above and the cross-account test both pass)

### CR-1 - A local edit deleted Google-only data at Google (P1)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GooglePersonMapping.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSync.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSyncTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/google/GooglePersonMappingTests.kt`

Problem:
`update()` sent every field group, rebuilt from the contact alone. Google replaces each group it receives, so data the cockpit does not model was erased: year-less birthdays, middle names, honorifics, P.O. boxes, organization departments, unreadable entries.

Fix:
- `update()` now reads the person first (`GET /v1/people/<id>?personFields=PERSON_FIELDS`):
  - 404: handled as removed at Google.
  - Other non-OK answer: a failed write for this contact.
  - Etag differs from `link.etag`: Google changed the person, so Google wins. The fetched person is applied with `force = true` and nothing is written.
- Otherwise the new `GooglePersonMapping.toUpdate(json, contact, current)` compares the contact with `toChanges(current)` group by group (names, emailAddresses, phoneNumbers, addresses, organizations, birthdays, biographies). Only the groups that differ go into the body and into `updatePersonFields`.
- If no group differs, the link is marked synced (`syncedVersion = contact.version`, etag kept) and no PATCH is sent.
- The 400 `FAILED_PRECONDITION` / 412 handling on the PATCH stays as a fallback: re-read, then Google wins, or 404 means removed.
- A sent group is merged into Google's entries instead of being rebuilt:
  - Names: the primary name is edited in place. `middleName`, `honorificPrefix` and similar parts are kept. Output-only `displayName`/`displayNameLastFirst` are removed, and `unstructuredName` is removed when given or family name is set.
  - Emails, phones and addresses: each value reuses Google's entry that reads the same, else the unused readable entry at the same position, else a new one. Only the parts that differ are changed. A changed number drops `canonicalForm`. A changed address drops `formattedValue`, and `countryCode` only when the country changed; `extendedAddress`/`poBox` are kept. Entries the cockpit cannot read (an invalid email, an address with only `formattedValue`) are kept unchanged.
  - Organizations and biographies: the primary entry is edited in place (an organization keeps its `department`), or dropped when cleared.
  - Birthdays: the full-date entry is replaced. If there is none, a year-less entry is replaced, because the user set a full date. A cleared birthday drops only the full-date entry. Other entries (year-less, text) stay.
- `UPDATE_FIELDS` was removed because it is no longer used.

Tests added or changed:
- Sync, new:
  - "editing only the note of a phone-only contact writes only the biography, not its year-less birthday or a name": `people/c1002` sends `updatePersonFields=biographies`, with no `birthdays`, `names` or `phoneNumbers`.
  - "a local change Google has no field for is marked synced without a write".
  - "when the person changed at Google since the last sync, reading it first shows it and Google's version wins": no PATCH is sent.
  - "when the person changes at Google between the read and the write, Google's version wins": 400 fallback, using a WireMock scenario.
  - "a contact deleted at Google between the read and the write goes to the Trash": PATCH 404.
  - "a person Google refuses to return before the write is reported, nothing is written".
- Sync, changed. These tests now stub the GET the push makes first:
  - "a local change is written to Google with the person's etag and not again on the next run": now expects `updatePersonFields=names`, a body with only `etag` and `names`, and the name merged.
  - "a contact deleted at Google before the push goes to the Trash without a write": now the GET returns 404, with no write.
  - "a write Google refuses is skipped and reported, the rest of the run goes on".
  - "a person deleted at Google after a stale etag goes to the Trash" and "a person Google does not return after a stale etag is reported, the local change is pushed again later": these now use the read, stale write, re-read sequence.

  These changes follow the new contract (GET before PATCH, only the changed groups). No assertion was weakened.
- Mapping, new `toUpdate` unit tests:
  - An unchanged contact sends no field.
  - A changed family name keeps middle name and honorific.
  - A changed note sends only the biography.
  - A changed address keeps the extended address, P.O. box, country code and the address that has only `formattedValue`.
  - Changed emails keep the entry's `displayName` and the invalid entry.
  - A changed number drops `canonicalForm`.
  - A changed title keeps the department, and a cleared organization drops its entry.
  - A full birthday replaces the year-less one and keeps the text entry; a cleared birthday drops only the full date.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.contacts.google.*'` - Pass

### CR-2 - Derived or synthetic display names were pushed as the person's name (P2)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GooglePersonMapping.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSyncTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/google/GooglePersonMappingTests.kt`

Problem:
A contact without given or family name sent its display name as `unstructuredName`, even when the cockpit had derived that name from the phone, the organization or "Unnamed contact".

Fix:
- Update: names are compared, not rebuilt. A derived display name equals what `toChanges` produces, so `names` is sent only when the user changed the given or family name, or changed the display name of a contact without them. When it is sent and the display name is again the derived one, the primary name entry is dropped instead of being written as `unstructuredName`.
- Create (`toPerson`, used for a restored contact): `names` is empty when the contact has no given or family name and its display name is the derived one (organization, else email, else phone, else `UNNAMED`).

Tests added:
- Sync: "editing the note of an organization-only contact does not give it the organization as a name" (`people/c1004`). The phone-only test above (`people/c1002`) asserts that no `names` are sent.
- Mapping:
  - "a derived display name is not sent as a name, a name the user gave is".
  - "a contact whose organization names it sends no name when the organization changes".
  - "a contact named after its phone number or without any field is created without a name".

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.contacts.google.*'` - Pass

### CR-3 - A contact purged from the Trash before the next push stayed at Google and came back (P2)

Files changed:
- `server/src/main/resources/db/migration/contacts/V202610221100__contacts_google_sync.sql` (edited in place: not on origin/main yet, as the parent allowed)
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsState.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSync.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSyncTests.kt`

Problem:
The link row was deleted together with the contact (`on delete cascade`). After that, `deleteContact` was never sent, and a later full listing imported the person again.

Fix:
- Schema: `contacts_google_link` now has its own `id uuid primary key` (a UUIDv7 generated in the entity). `contact_id` is nullable and unique, with `on delete set null`. The partial unique index on `(connection_id, resource_name)` is unchanged.
- Entity: `GoogleContactLink.contactId` is `UUID?` and `updatable = false`, and `id` was added. The repository gained `findByContactId`.
- Push:
  - A link with no contact but with a resource name (a tombstone) sends `DELETE …:deleteContact`. On success or 404 the link row is deleted. A refused delete is counted and retried by the next run.
  - A link with neither a contact nor a resource name is deleted.
- Pull:
  - `store()` skips a person whose link is a tombstone, so it is not imported again.
  - `removedAtGoogle()` deletes a tombstone. This covers a deletion at Google and a person missing from a full listing.
- Disconnect still deletes every link of the connection.
- Link lookups in create, update and delete now use `links.findById(link.id)`.

Tests added:
- "a contact purged from the Trash before the push is still deleted at Google": trash, then `ContactTrashContributor.purge`, then sync. Asserts the DELETE was sent, the link is gone and the contact was not re-created.
- "a purged contact whose delete Google refuses is not imported again, and the delete is retried": the delete returns 400, and Google still lists the person with a changed etag. The person is not imported and the tombstone is kept. The next run gets 404, and the tombstone is removed.
- "a contact purged after its person was deleted at Google leaves no link behind".

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.contacts.google.*'` - Pass

Note:
A developer database that already applied the old version of this migration will report a Flyway checksum mismatch and must be reset. The Testcontainers databases are fresh, so the tests are not affected.

### CR-4 - Resource names from the pull were not validated, and one bad name stopped every run (P3)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsClient.kt` (adds `isResourceName`, which `resourcePath` now uses)
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSync.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSyncTests.kt`

Fix:
- `apply()`: a pulled person whose resource name is not `people/<id>` is skipped and counted as failed. The log names only the connection id.
- `create()`: the name is checked before it is stored. An invalid one is a rejected create for that contact, not a run-level exception.
- `update()` and `delete()`: an invalid stored name is a failed write for that contact (`invalidName`, the log has only the id). The run goes on, and the sync token still advances.

Tests:
- New: "a stored resource name that is not people slash id fails only that contact, not the run".
- New: "a pulled person with a resource name that is not people slash id is skipped and reported".
- Renamed and updated: "a created person with a resource name that is not people slash id is reported and not linked, the run goes on". It previously expected a run-level failure. It now expects `1 contacts could not be synced with Google`, and the sync token advances.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.contacts.google.*'` - Pass

### CR-6 - Untested paths (P3)

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSyncTests.kt`

Fix:
- `syncAll()` filtering (GOOGLE, CONNECTED, contacts scope): already covered by `GoogleContactsSyncUnitTests` "syncAll syncs every connected Google connection that granted the contacts scope, and only those". That test was added by the test-writer after the review and passes. No new test was needed.
- 412 on the PATCH: "412 for the etag also means Google's version wins". This now really exercises the PATCH 412: GET with the stored etag, then PATCH 412, then re-read.
- GET 404 after a stale etag: "a person deleted at Google after a stale etag goes to the Trash" (WireMock scenario: read, stale write, re-read 404). GET 404 before the write is covered too.
- A pulled person that cannot be stored is skipped and counted:
  - "a person that cannot be stored is skipped and reported, the others are synced" (unique external id collision).
  - The new invalid-resource-name pull test.

  A person that fails `ContactService` validation cannot be produced: `toChanges` cuts values, limits entries and drops invalid ones before validation runs. So the skip path is tested through these two causes instead.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.contacts.google.*'` - Pass

## Not fixed

### CR-5 - A user-set display name of a named contact is not pushed

Status:
Requires decision. The parent decided it and records the assumption.

Reason:
As instructed, no `nicknames` mapping was added. The parent records the assumption that the display name of a named GOOGLE contact is derived from Google. In line with that, a local display-name-only change on a named contact sends no PATCH. The contact is marked synced, as the test "a local change Google has no field for is marked synced without a write" shows.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat ktlintCheck test --tests 'ch.sonensei.cockpit.contacts.google.*'` (final code): BUILD SUCCESSFUL. 102 tests, 0 failures:
  - GoogleContactsSyncTests: 43
  - GooglePersonMappingTests: 37
  - GoogleContactsClientTests: 16
  - GoogleContactsSyncUnitTests: 6
- `cd server && ./gradlew compileKotlin compileTestKotlin --rerun`: no compiler warnings in `contacts/google`.

Full test suite:
- `cd server && ./gradlew check` (ktlint and all server tests, Postgres via Testcontainers): BUILD SUCCESSFUL in 5m 39s. This and `.taskcheck` below ran before the last test was added ("a pulled person with a resource name that is not people slash id is skipped and reported"). That change touches only tests, and the focused run plus `ktlintCheck` above cover it.

Lint/type-check/build:
- ktlint is part of `./gradlew check`: Pass.

`.taskcheck`:
- `sh scripts/check.sh`: Pass ("all checks passed": server, web, android).

Remaining unresolved findings:
0. CR-5 is handled by the parent's assumption and needs no code change.

## Final status

All validated findings fixed and validation passed.
