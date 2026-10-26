# Fix Report

Date: 2026-10-26

Task: carddav-sync — CardDAV contacts sync

Scope: current task; findings CR-2, CR-4 and (from the re-review, committed at e7b33c006) CR-5 of `reviews/carddav-sync/review.md` (base d6a1773e14b8e11dd032faac22eb397789050106, reviewed HEAD b6de0272b). CR-1 was addressed by earlier test commits; CR-3 is accepted as an assumption by the parent worker and was not in this fixer's scope.

## Fixed findings

### CR-2 — Redundant sync-collection REPORT on every run without a token

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavContactsSync.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavContactsSyncUnitTests.kt`

Problem:
`pull` used `syncCollection(token) ?: syncCollection(null) ?: propfindListing()`. With no token (always the case for a server without sync-collection), the same failing token-less REPORT was sent twice before the PROPFIND.

Fix:
The REPORT with the token runs only when a token exists: `(if (token != null) syncCollection(token) else null) ?: syncCollection(null) ?: propfindListing()`. A refused token is still retried once without a token.

Tests added:
- `without a token a server that refuses sync-collection gets one REPORT, then PROPFIND`: exactly one sync-collection REPORT (with an empty token), then one PROPFIND.
- `a refused token is retried once without a token`: a 409 on the old token leads to exactly one token-less REPORT and no PROPFIND. The token is updated.

Validation:
- Gradle could not be run by the fixer (permission). The parent worker runs it: see "Validation summary".

### CR-4 — A card re-created under the same href while its contact is in the Trash is ignored, and is lost after a purge

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavContactsSync.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavContactsSyncUnitTests.kt`

Problem:
After `removedAtServer`, the link keeps its href with `etag = null` and the contact is moved to the Trash. If a card reappeared under that href, `store` saw the trashed contact and returned without recording anything. A later purge made `push` drop the link (`contact == null && etag == null`). Incremental pulls never list the unchanged card again, so the card stayed at the server but was missing from the cockpit, and no error was shown.

Fix:
In `store`, when the linked contact is in the Trash and the link has no ETag, the card's new ETag is recorded on the link (`if (link.etag == null) link.etag = etag`). The state is then the same as for a trashed contact whose card exists: the next push deletes the card (`contact.isDeleted && link.etag != null -> DELETE`), or, after a purge, the tombstone path deletes it. Restoring the contact before that push writes it back with `If-Match` against the recorded ETag.

Why this option rather than releasing the link and importing a new contact:
- It matches the existing design. "A contact in the Trash is deleted at the server" (class KDoc). A card that changes at the server while its contact is in the Trash (ETag stored, new ETag) is already left for the next push to delete. This fix extends the same rule to the card that comes back.
- `CardDavLink.contactId` is `val` and `updatable = false`. Releasing the link would mean deleting the link and inserting a new one with the same `(account_id, href)` in one transaction. Hibernate flushes inserts before deletes, so this would need an extra explicit flush. It would also leave the trashed contact as a CARDDAV contact with no link that no longer syncs when restored.
- Trade-off: the content of the card that came back is not imported. The card is deleted at the server, consistent with "Trash here wins". The trashed contact keeps the earlier data and can still be restored, which writes the card again. The card is no longer silently dropped from tracking.

Tests added:
- `a card back under the href of a contact trashed after its removal gets its ETag, and the next push deletes it`:
  - First run: the link gets `"e-ada.vcf"`. No contact is created or updated, and no DELETE is sent.
  - Second run: one DELETE is sent to the card, and the link's ETag is cleared.

Validation:
- Gradle could not be run by the fixer (permission). The parent worker runs it: see "Validation summary".

Superseded: the re-review (CR-5) found that this fix deletes server data, so the behaviour was changed again. See CR-5 below. The CR-4 test was replaced.

### CR-5 — The CR-4 fix deletes a card that came back at the server

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavContactsSync.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavContactsSyncUnitTests.kt`

Problem:
With the CR-4 fix, a card that came back at the server (undeleted or imported again there) under the href of a contact the sync itself had trashed got its ETag recorded. The next push then sent a DELETE for it, so data that existed only at the server was lost.

Fix:
In `store`, when the linked contact is in the Trash:
- If the link has an ETag (the user trashed the contact here while the card exists), nothing changes: the card is left for the next push to delete.
- If the link has no ETag (the card was gone at the server and is now back), the contact is restored from the Trash and the card is applied like any other server change:
  - The restore uses the same steps as the contacts Trash restore (`ContactTrashContributor.restore`): `contact.restore()` and `contacts.saveAndFlush(contact)`.
  - The card is then applied through the normal update path: `service.update`, which re-indexes the contact and publishes the change. The link gets the card's ETag and the contact's version.

Nothing is deleted at the server, the link and the contact id are kept, and no new row is needed. The no-link fallback (the old contact gives up its external id and the card becomes a new contact) was not needed, because restoring is possible inside the contacts module. A failure while storing the card rolls back the restore with the rest of the transaction.

A note for the parent worker: the link also has no ETag when the user trashed the contact here and a push already deleted the card. If a card is later created at the server under that same href, the contact now comes back too (the newer change at the server wins). This seems right, but it is a behaviour a human may want to confirm with `tasks.py assume`.

Tests:
- Replaced the CR-4 test with `a card back under the href of a contact the sync trashed restores the contact with the card, and nothing is deleted`. It checks:
  - The contact is no longer deleted and was saved.
  - The card's content ("Ada Back") is applied to the same contact, and no new contact is created.
  - The link keeps the contact, gets the card's ETag, and its synced version is the contact's version.
  - Over two runs, no DELETE or PUT is sent.
- Added `a card that changes at the server while the user has its contact in the Trash is left for the push to delete`. It covers the unchanged case where the link has an ETag:
  - Neither `update` nor `createSynced` is called, the contact stays in the Trash, and the link keeps its old ETag.
  - The push sends the DELETE.

Validation:
- Gradle could not be run by the fixer (permission). The parent worker runs it: see "Validation summary".

## Not fixed

### CR-3 — Cards that fail to store are dropped from later runs

Status:
Out of scope

Reason:
The parent worker accepted the current behaviour (parity with `GoogleContactsSync`) as a recorded assumption and excluded it from this fix round.

## Validation summary

Focused tests:
- Not run by the fixer: Gradle is not permitted in the fixer's sandbox. The parent worker runs `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.carddav.CardDavContactsSyncUnitTests'`, then `./gradlew ktlintCheck`.

Full test suite:
- Not run by the fixer. The parent worker runs `cd server && ./gradlew check`.

Lint/type-check/build:
- Not run by the fixer (see above). The changes follow the existing tab and line-length style.

`.taskcheck`:
- Not run by the fixer. The parent worker runs it (`sh scripts/check.sh`).

Remaining unresolved findings:
0 of the findings in scope (CR-3 is excluded as an accepted assumption)

## Final status

Fixes are complete. Validation has not been run yet: the parent worker must run the Gradle commands above before `done`.
