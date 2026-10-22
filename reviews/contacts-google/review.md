# Code Review

Date: 2026-10-22

Task: contacts-google - Google contacts sync

Scope: round 3, extended to HEAD f289af85. Reviewed `git diff c8f67f23..f289af85`, excluding `tasks/` and `reviews/`.

- **Base:** c8f67f23 is `git merge-base HEAD origin/main`. Compared with the earlier start point e49088af, it adds only one assumption line in `tasks/contacts-google.md`.
- **Commits:** the task commits were rebased and are now 57487c23, 98f6f50f, be0ce0c7, 65162a6b, 7007865d, 2aa35730, 1cb31d26, ac7e3f99, d5c8b17f and f289af85.
- **Production code:** `git diff 70429933 f289af85 -- server/src/main` is empty, so the production code is identical to the first round-3 review revision (70429933).
- **This extension:** it adds a review of the three rename-during-push tests that the test-writer committed in f289af85 (`git diff 70429933..f289af85 -- server`, 70 lines in `GoogleContactsSyncTests.kt`).
- **Round 3 as a whole:** it checks the round-2 fixes (CR-1 and CR-3) and the round-3 fix TW-1 in `markSynced` (`reviews/contacts-google/fixes.md`), and looks for new issues across the whole task diff.
- **Working tree:** clean apart from review reports. Only committed code was reviewed.

Requirements:
- tasks/contacts-google.md: the two acceptance criteria and all ASSUMPTION notes, which are accepted decisions. These include Google wins on conflicts, push only changed field groups, tombstones, the derived display name, round-2 CR-2 accepted as a known limitation, and round-3 CR-1 accepted as a known limitation.
- SPEC.md section 8.3 (GOOGLE contacts two-way via the People API)
- AGENTS.md "Project notes": module boundaries, Flyway location and naming, logging rules, fictitious test data
- docs/ARCHITECTURE.md and docs/QUALITY.md
- The existing contacts domain: `ContactService` (`apply`, `update`, derived display name, validation) and `Contact` (optimistic locking through `@Version` in `BaseEntity`)

Git range:
- Branch: main
- HEAD: f289af85d1bc6bc9872a78cac94e92ea75f39cb9
- Base/start: c8f67f2365a2993b5018dd1535e59f3417e41835
- Commits reviewed: 10

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Verification of earlier fixes

- **Round-2 CR-1 (a stale derived display name later pushed as a name): fixed.**
  - After a successful PATCH, `GooglePersonMapping.derivedNameAfterPush` (`GooglePersonMapping.kt:203-210`) returns a newly derived name in one case only. The contact has no given or family name, Google has no name, and the stored name equals the one derived from Google's person as it was before the write. `markSynced` (`GoogleContactsSync.kt:279-298`) then stores that name.
  - `displayName(ContactChanges)` uses the same derivation order as `derivedName(contact)`, and as `toChanges` and `ContactService.derivedName` on pull: name, then organization, then first email, then phone or "Unnamed contact". An echo or pull therefore produces the same name.
  - Covered by the WireMock test `an organization-only contact edited twice in a row never gets its old organization as a name`.
- **Round-2 CR-3 (a full listing trashes a person the same run just created): fixed.**
  - `create()` adds the resource name to `created` only after it stored the link (`:189-196`).
  - `pull()` passes `seen + created` to `removeMissing` (`:388`).
  - Covered by the test `a full listing after an expired token that lags behind does not trash the contact the push just created`.
- **Round-2 CR-2 (overflow entries beyond MAX_ENTRIES are dropped on an edit):** recorded as an accepted assumption in the task file, so it is not reported again.
- **Round-3 TW-1 (an edit made during the push keeps the old derived name): fixed.**
  - When the stored version differs from the pushed one, `markSynced` now renames the contact only if all of these hold: the contact is not in the Trash, it still has the pushed display name, and it has no given or family name.
  - `syncedVersion` stays at the pushed version, so the next run pushes the edit made meanwhile.
  - I traced each kind of name change made meanwhile: an explicit display name, an added given or family name, and the organization changed again. In each case either no rename happens, or the next push sends only `organizations` and derives the name again. A name the user never gave never reaches Google.
  - The tests `an organization-only contact whose note is edited while its new organization is pushed never gets the old one as a name` and `a contact moved to the Trash during the push of its change stays there and is deleted at Google by the next run` cover both branches.

## Review of the tests added in f289af85

- **Tests added:** three WireMock tests, each with a user edit during the push of the new organization:
  - `a display name the user gives while the new organization is pushed is kept and written to Google as the name`
  - `a given name the user adds while the new organization is pushed keeps the display name and is written to Google`
  - `a family name the user adds while the new organization is pushed keeps the display name and is written to Google`
  - They also add the helper `nextPushedName()`.
- **What they assert:** these are the branches of `markSynced` (`GoogleContactsSync.kt:292-296`) that must not rename the contact.
  - After the first run, the user's value is kept. The display name is "Fictional Holding AG", or it stays "Fictional GmbH" when a given or family name was added.
  - `syncedVersion` is below the contact version, so the edit is still unsynced.
  - The next run's PATCH includes `names`. In the display-name case the primary name carries `unstructuredName` "Fictional Holding AG". In the other cases it carries `givenName` or `familyName` and no `unstructuredName`.
- **Matches the code:**
  - `namesChanged` returns true in all three cases: the given or family name differs from Google's, or the display name differs from the derived "Other GmbH".
  - `mergeNames` keeps the name. Google's stubbed person has no `names`, so `writeName` writes a new entry, which explains the missing `unstructuredName` assertions.
- **Quality:**
  - The tests assert observable behavior (stored contact, link state, request query and body), not implementation details.
  - They reuse the existing `companyWithNewOrganization` and `duringWrite` helpers. Test data is fictitious.
  - The new `JsonNode` import is used. `shouldContain` on the split field list resolves to the collection matcher, which was already imported.
- **Gaps:** I found nothing to add. Together with the round-3 tests above, every branch of `markSynced` is now covered:
  - unchanged version;
  - changed version with the old derived name;
  - changed version with a user-given name;
  - trashed meanwhile.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A database error while storing a push result aborts the whole run, and on the next run Google's version overwrites the edit made meanwhile**
    - Status: the parent accepted this as a known limitation with `tasks.py assume` (the task file entry of 2026-10-22 06:23+0200 by dell26/agent-2). It is kept here for the record.
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSync.kt:285-297` (`markSynced`), together with `:88-97` (`sync` catches only `GoogleContactsException`) and `:221`
    - Category: Correctness
    - Problem: After a successful PATCH, `markSynced` loads the contact and, since rounds 2 and 3, calls `service.update` in the same transaction to rename it. `Contact` uses optimistic locking (`@Version`).

      Suppose a user saves the contact after `contacts.findById` (`:290`) and before the flush in `service.update`. The flush then fails with an `ObjectOptimisticLockingFailureException`, and nothing in `update()`, `push()` or `sync()` catches it (`sync` handles only `GoogleContactsException`). As a result:
        1. The transaction rolls back. The new etag from the PATCH response is not stored, and `lastError` and `lastSuccessAt` are not updated.
        2. The run stops: the remaining contacts are not pushed, and the pull is skipped.
        3. On the next run the contact is still `UPDATE`. The GET returns the PATCH's etag, which differs from the stored `link.etag`. `googleWins` (`:221`) then applies Google's person with `force = true` and overwrites the user's concurrent edit, which was never pushed.

      Pulled persons are already protected: `apply` catches `RuntimeException` per person (`:419-426`). The push transactions (`create`, `markSynced`, `delete`, and `removedAtGoogle` called from `update`) are not.
    - Why it matters: A user edit can be silently lost, and one contact can stop the run for all the others. The timing window is narrow (milliseconds while the sync stores one contact), hence P3.
    - Suggested fix:
      - Catch `RuntimeException` around each item in `push()`, as `apply` does. Log only the contact id and the exception class, and count the item as failed, so the run continues and records `lastError`.
      - Better still, keep the etag update separate from the rename: store the etag and `syncedVersion` first, then rename in a separate transaction, or skip the rename on a version conflict. The PATCH's etag is then not lost, and the next run does not mistake the cockpit's own write for a change at Google.

## Review conclusion

No material issues found.

The round-2 fixes CR-1 and CR-3 and the round-3 fix TW-1 are correct and covered by WireMock tests. The three tests added in f289af85 are meaningful and match the code. The only finding, CR-1 (P3), is a narrow race that is now accepted as an assumption. It does not block the task.

Checked and found sound (not findings):
- **Acceptance criterion 1, in the code:**
  - `requestSyncToken` and `syncToken` are sent on every page.
  - An expired token (400 `EXPIRED_SYNC_TOKEN` or 410) leads to one full listing, and a failed page trashes nothing.
  - Writes carry the etag read just before. A stale etag (400 `FAILED_PRECONDITION` or 412) lets Google win after a re-read.
  - Echoes are skipped by etag.
  - The recorded fixtures are in `src/test/resources/.../contacts/google/*.json`, with fictitious data.
- **Data handling:** tombstones (`on delete set null`), deletes, re-creation of restored contacts, and validation of resource names before they go into a URL.
- **Logging:** ids, statuses and exception class names only, with no tokens and no contact data. The messages stored in `lastError` are safe to show.
- **Module boundaries:** `contacts/google` uses only `ContactService`, `ContactRepository` and the core OAuth domain services and event. The only change outside the task's touches is `ContactService.createSynced`, which an assumption records.
- **Migration:** `db/migration/contacts/V202610221100__contacts_google_sync.sql` follows the naming rule. It is not on origin/main, so editing it in place in an earlier round was safe.
- **Scheduler:** the test configuration turns it off (`cockpit.contacts.google.sync.scheduler.enabled=false`).

Evidence: Gradle was not run in this review, and `python tasks.py verify contacts-google` showed both criteria still unverified when checked. The parent must run `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.google.*'` on f289af85 (or a later patch-identical revision) and record the result for criteria 1 and 2.
