# Code Review

Date: 2026-11-01

Task: mail-delete-stale-trash - Mail actions: a message already gone from Trash counts as deleted, stale rows are cleaned up

Scope: The whole task diff, `git diff 8e0f00515..c63607475` (seven task commits on top of the base 8e0f005156e60f8ee3813dd1a45748afa70f0704). This third review also checks the fix for the previous review's CR-1 (commit 0742e2f8b), its unit tests (commit 32f302f07), and the unit test the test-writer added in c63607475. Commit c63607475 changes only `MailActionServiceTests.kt` and reports under `reviews/`, not production code. Only committed changes were reviewed.

Requirements:
- tasks/mail-delete-stale-trash.md (acceptance criteria, notes, two recorded assumptions)
- AGENTS.md (project notes and conventions)
- Existing behavior of MailHeaderSyncService (`syncFolder` takes the per-folder lock and publishes the folder change event)
- Previous review (CR-1) and reviews/mail-delete-stale-trash/fixes.md

Git range:
- Branch: main
- HEAD: c63607475ac02cae3f31999bea8ee1b22990758e
- Base/start: 8e0f005156e60f8ee3813dd1a45748afa70f0704
- Commits reviewed: 7 (48aee449f, b91cda374, ff7a57fe7, ebef28e0e, 0742e2f8b, 32f302f07, c63607475)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Checked against the criteria:
- AC1: `goneCountsAsDone` (MailActionService.kt:213) applies only to `Operation.Expunge`, which only a DELETE in a TRASH-role folder produces, and it requires a cached UIDVALIDITY. Before that, `remote()` compares the cached UIDVALIDITY with the server's (line 270) and returns `Remote.Invalidated` on a mismatch. A missing folder returns `Remote.Gone` (line 263). Both cases fail every id with NOT_FOUND and leave the rows as they are. Missing UIDs reach `updateCache`, then `applyToRows`, then `remove()`: a linked row is detached, any other row is deleted, and both are unindexed. The Trash `MAIL_FOLDER` event is published. When every UID is missing, `selected.isEmpty()` returns `Done(missing, ...)` without running a server command, and the rows are still removed.
- AC2: when any other operation has missing UIDs, those ids fail with NOT_FOUND and `resync.request(source.id)` is called (line 216). `MailFolderResync` runs `MailHeaderSyncService.syncFolder`, which takes the sync lock, on a virtual-thread executor, so the response does not wait for it. Requests for a folder whose sync is still pending are merged. A request made while a sync is running schedules another sync. Failures are logged at INFO/WARN with only the error enum or the exception class name, and a failure never reaches the action. MailFolderResyncTests covers merging, a request during a running sync, failures and shutdown.
- AC3: MailActionGreenMailTests contains (a), (b) and (c) under the names given in the task. Every test that triggers a background resync waits for it (`awaitUncached`, `awaitUidValidity`).
- AC4: MailActionServiceTests covers these outcomes: all gone, mixed, linked (detached), a mixed Trash/Inbox delete, changed UIDVALIDITY, missing folder, no cached UIDVALIDITY, resync limited to the affected folder, no resync when every message is found, and (new in c63607475) a MOVE where some messages are gone.
- CR-1 fix: `updateCache` now passes a map from id to the UID the server command used (`expected`, line 356). `applyToRows` (line 382) only touches rows that are still in the source folder at exactly that UID. Effects on the other actions:
  - Expunge: rows that still have the expunged or missing UID are removed. A row a sync relocated to a new UID is left alone, and so is a row it already detached (its `uid` is null).
  - Move: `newUids[row.uid]` is now looked up with the same UID that was zipped with the COPYUID result in `move()`. So the COPYUID mapping is unchanged for normal rows, and a relocated row can no longer pick up another message's COPYUID entry.
  - Flags: they are only set on rows still at the commanded UID.
  - Detach of linked rows: still goes through `remove()`.
  - The change events are published as before.
  - Ids in one group cannot share a UID (one source folder, unique UID per folder), so `associate` loses nothing.
  - Commit 32f302f07 adds unit tests for these cases: a relocated row in Trash (gone and mixed), a detached row, a flag and a move.
- New test in c63607475 (`a move with a message gone from its folder moves the present one by its COPYUID and fails the gone one with a resync`):
  - The setup is realistic. The server returns `[null, message]` for UIDs 7 and 8, and `moveUIDMessages` is stubbed only for the present message.
  - It checks the risky part. If the COPYUID list were zipped with all requested UIDs instead of the present ones (`remote()` passes `present` to `move()`), UID 7 would get 41 and the present row would have no mapping and be removed. The test would then fail on `present.uid shouldBe 41` and on `messages.delete`.
  - It also checks that the gone row stays in place, that only the source folder is resynced, that nothing is deleted, and that the destination folder event is published.
  - It tests behavior through the public `apply` method and the mocked IMAP boundary, like the surrounding tests.
- The API contract is unchanged. The KDoc of `MailAction.DELETE` and of `MailActionService` describes the final behavior. There are no secrets, no logging of message content and no new dependencies. `MailActionService` is constructed in only one test, and that test was updated.
- Evidence on the final code, as reported by the parent worker (Gradle cannot run in this review):
  - `cd server && ./gradlew ktlintFormat check jacocoTestReport` -> BUILD SUCCESSFUL, MailActionServiceTests 45 tests, 0 failures.
  - `cd server && ./gradlew integrationTest --tests '*MailActionGreenMailTests*'` -> 20 tests, 0 failures.

  These runs replace the earlier AC3 evidence recorded at aedab811d, which was taken before the production change in 0742e2f8b. They should be recorded with `tasks.py verify`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. This covers the final revision c63607475. The previous review's CR-1 is correctly fixed. The fix leaves moves (COPYUID mapping), flag actions, expunges and detaching linked rows unchanged, except that rows a concurrent sync moved to a different UID are now skipped. The unit test added in c63607475 is meaningful and correct, and it does not change production code.
