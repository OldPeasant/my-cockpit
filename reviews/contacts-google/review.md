# Code Review

Date: 2026-10-22

Task: contacts-google - Google contacts sync

Scope: round 3 on HEAD 70429933. Reviewed `git diff e49088af..70429933`, excluding `tasks/` and `reviews/`. The task commits are dc2e4c63, 741ada94, 7b9940d7, bcec2ac8, 9d118bf2, 73b54f65, b0f820e3, 82963afd and 70429933. This round checks the round-2 fixes (CR-1 and CR-3), the round-3 fix TW-1 in `markSynced` (`reviews/contacts-google/fixes.md`), and looks for new issues across the whole task diff. The working tree has an uncommitted change to `GoogleContactsSyncTests.kt`, presumably from the test-writer running in parallel. This review covers committed code only and does not include that change.

Requirements:
- tasks/contacts-google.md: the two acceptance criteria and all ASSUMPTION notes, which are accepted decisions. These include Google wins on conflicts, push only changed field groups, tombstones, the derived display name, and round-2 CR-2 accepted as a known limitation.
- SPEC.md section 8.3 (GOOGLE contacts two-way via the People API)
- AGENTS.md "Project notes": module boundaries, Flyway location and naming, logging rules, fictitious test data
- docs/ARCHITECTURE.md and docs/QUALITY.md
- The existing contacts domain: `ContactService` (`apply`, `update`, derived display name, validation) and `Contact` (optimistic locking through `@Version` in `BaseEntity`)

Git range:
- Branch: main
- HEAD: 704299330c36de0e474cf435aa25a9ae4762074e
- Base/start: e49088af
- Commits reviewed: 9

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Verification of earlier fixes

- Round-2 CR-1 (a stale derived display name later pushed as a name): fixed. After a successful PATCH, `GooglePersonMapping.derivedNameAfterPush` (`GooglePersonMapping.kt:203-210`) returns a newly derived name only in this case: the contact has no given or family name, Google has no name, and the stored name equals the one derived from Google's person as it was before the write. `markSynced` (`GoogleContactsSync.kt:279-298`) stores that name. I checked that `displayName(ContactChanges)` uses the same derivation order as `derivedName(contact)`, and as `toChanges` and `ContactService.derivedName` on pull: name, then organization, then first email, then phone or "Unnamed contact". So an echo or pull produces the same name. The WireMock test `an organization-only contact edited twice in a row never gets its old organization as a name` covers this.
- Round-2 CR-3 (a full listing trashes a person the same run just created): fixed. `create()` adds the resource name to `created` only after the link was stored (`:189-196`), and `pull()` passes `seen + created` to `removeMissing` (`:388`). The test `a full listing after an expired token that lags behind does not trash the contact the push just created` covers this.
- Round-2 CR-2 (overflow entries beyond MAX_ENTRIES are dropped on an edit): recorded as an accepted assumption in the task file, so it is not reported again.
- Round-3 TW-1 (an edit made during the push keeps the old derived name): fixed. When the stored version differs from the pushed one, `markSynced` now renames the contact only if all of these hold: the contact is not trashed, it still has the pushed display name, and it has no given or family name. `syncedVersion` stays at the pushed version, so the next run pushes the edit made meanwhile. I traced the cases where the edit made meanwhile changes the name: an explicit display name, a given or family name added, or the organization changed again. In each case either no rename happens, or the next push sends only `organizations` and derives the name again. In no case is a name the user never gave written to Google. The tests `an organization-only contact whose note is edited while its new organization is pushed never gets the old one as a name` and `a contact moved to the Trash during the push of its change stays there and is deleted at Google by the next run` cover both branches.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A database error while storing a push result aborts the whole run, and on the next run Google's version overwrites the edit made meanwhile**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSync.kt:285-297` (`markSynced`), together with `:88-97` (`sync` catches only `GoogleContactsException`) and `:221`
    - Category: Correctness
    - Problem: After a successful PATCH, `markSynced` loads the contact and, since rounds 2 and 3, calls `service.update` in the same transaction to rename it. `Contact` uses optimistic locking (`@Version`). Suppose a user saves the contact after `contacts.findById` (`:290`) and before the flush in `service.update`. The flush then fails with an `ObjectOptimisticLockingFailureException`. Nothing in `update()`, `push()` or `sync()` catches it: `sync` handles only `GoogleContactsException`. As a result:
        1. The transaction rolls back, so the new etag from the PATCH response is not stored, and `lastError` and `lastSuccessAt` are not updated.
        2. The run stops. The remaining contacts are not pushed, and the pull is skipped.
        3. On the next run the contact is still `UPDATE`. The GET returns the PATCH's etag, which differs from the stored `link.etag`, so `googleWins` (`:221`) applies Google's person with `force = true`. This overwrites the user's concurrent edit, which was never pushed.

      Pulled persons are already protected this way: `apply` catches `RuntimeException` per person (`:419-426`). The push transactions (`create`, `markSynced`, `delete`, and `removedAtGoogle` called from `update`) are not.
    - Why it matters: A user edit can be silently lost, and one contact can stop the run for every other contact. The timing window is narrow (milliseconds while the sync stores one contact), hence P3.
    - Suggested fix: Catch `RuntimeException` around each item in `push()` the same way `apply` does. Log the contact id and the exception class only, and count the item as failed so the run continues and records `lastError`. Better still, keep the etag update separate from the rename: store the etag and `syncedVersion` first, then rename in a separate transaction, or skip the rename on a version conflict. Then the PATCH's etag is not lost, and the next run does not mistake the cockpit's own write for a change at Google.

## Review conclusion

No material issues found.

The round-2 fixes CR-1 and CR-3 and the round-3 fix TW-1 are correct and covered by WireMock tests. CR-1 above is a narrow race. It does not block the task. The parent can fix it or record it with `tasks.py assume`.

Checked and found sound, not findings:
- Acceptance criterion 1 in the code: `requestSyncToken` and `syncToken` are sent on every page. An expired token (400 `EXPIRED_SYNC_TOKEN` or 410) leads to one full listing. A failed page trashes nothing. Writes carry the etag read just before. A stale etag (400 `FAILED_PRECONDITION` or 412) lets Google win after a re-read. Echoes are skipped by etag. Recorded fixtures are in `src/test/resources/.../contacts/google/*.json`, with fictitious data.
- Tombstones (`on delete set null`), deletes, re-creation of restored contacts, and validation of resource names before they go into a URL.
- Logging: ids, statuses and exception class names only. No tokens and no contact data. The messages stored in `lastError` are safe to show.
- Module boundaries: `contacts/google` uses only `ContactService`, `ContactRepository` and the core OAuth domain services and event. The only change outside the task's touches is `ContactService.createSynced`, which an assumption records.
- The migration `db/migration/contacts/V202610221100__contacts_google_sync.sql` follows the naming rule. It is not on origin/main (`git ls-tree origin/main` has no contacts_google file), so editing it in place in an earlier round was safe.
- The test configuration turns the scheduler off (`cockpit.contacts.google.sync.scheduler.enabled=false`).

Evidence: Gradle was not run in this review. `python tasks.py verify contacts-google` shows both criteria still unverified. The parent must run `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.google.*'` on the final code and record the result for criteria 1 and 2.
