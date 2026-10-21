# Fix Report

Date: 2026-10-22

Task: mail-search - Mail search

Scope: current task; findings CR-1, CR-2, CR-3 of `reviews/mail-search/review.md` (base 2c4f1d63, task commits 98edcafe, b3788244, 2ba84ed7, a4b387ce)

## Fixed findings

### CR-1 — Messages whose folder was deleted are returned by search and make the response fail with 500

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/search/MailSearchIndex.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/search/MailSearchService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailFolderService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/MailFolderSyncTests.kt`

Problem:
Search, the stale-document delete and the missing-document query only checked `m.uid is not null`. Rows left behind by a deleted folder (`folder_id` set null, `uid` still set) matched account-scoped and unscoped searches, and mapping them hit `checkNotNull(folderId)`, so the response was a 500. Their documents also stayed in the global search.

Fix:
Added the shared predicate `MailSearchIndex.LOCATED` (`m.folder_id is not null and m.uid is not null`, the same as `MailMessage.isLocated`). It is now used by `localIds` and by both reconcile queries, so the reconciler removes those documents. Also added `MailSearchIndex.unindexFolder(folderId)`, which `MailFolderService.store` calls just before it deletes a vanished folder. That drops the documents right away instead of waiting for the next reconcile. The hook is one call, the same kind of index hook as the ones the task's recorded assumption already covers in mail/sync and mail/api.
Regression tests: the test-writer had already added `MailSearchWebTests` "messages of a folder deleted from the cache are not found" (account-scoped and unscoped search, both must answer 200 without the message) and "reconcile drops documents of messages whose folder was deleted and does not count them as added". I extended `MailFolderSyncTests` "a vanished folder is removed but its cached messages stay without a folder and leave the search index" to assert that the folder sync removes the document.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.*'` — Pass

### CR-2 — Reconciler counts unindexable rows as added and selects them again in every batch

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/search/MailSearchIndex.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/search/MailSearchReconciler.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/search/MailSearchReconcilerTests.kt`

Problem:
`reconcile` returned the number of selected rows as "added", including rows it then unindexed, and it ran the unbounded orphan delete on every batch.

Fix:
Split the work into `removeStale()`, which returns the documents removed, and `indexMissing(batchSize)`, which returns the documents actually indexed (`count { it.isLocated }`). Thanks to the CR-1 predicate the selection only holds located rows. `MailSearchReconciler.run` now calls `removeStale()` once per run, then `indexMissing` in batches while a batch was full, still capped at 200 batches. `reconcile(batchSize)` stays as a single repair pass (removeStale + one batch) and the Postgres tests use it. I rewrote the reconciler unit tests for the new API: removeStale runs exactly once per run, the batching and bound behave as before, and a failure is caught.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.*'` — Pass

### CR-3 — GreenMail test does not prove the UTF-8 IMAP SEARCH path

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/search/MailSearchGreenMailTests.kt`

Problem:
The `müsli` server search ran after the message was already cached, and the test did not assert `server.failures`, so a rejected `CHARSET UTF-8` search would still have passed.

Fix:
The `müsli` call now asserts `searchedFolders == 1` and `failures` empty. I added a third message older than the sync window ("Recipe card", body "Bircher-Müesli wie in Zürich."). A local search for `zürich` finds nothing. With `server=true` it returns that message with `loaded == 1`, `searchedFolders == 1` and no failures, so this result can only come from the server-side UTF-8 match.

Validation:
- `cd server && ./gradlew integrationTest --tests '*MailSearchGreenMailTests'` — Pass (1 test)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` — no remaining changes
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.*'` — BUILD SUCCESSFUL, 0 failures
- `cd server && ./gradlew integrationTest --tests '*MailSearchGreenMailTests'` — BUILD SUCCESSFUL, 1 test, 0 failures

Full test suite:
- `cd server && ./gradlew check` — BUILD SUCCESSFUL (ktlint main/test/script + test)

Lint/type-check/build:
- ktlint is part of `./gradlew check` — Pass

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("all checks passed")

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
