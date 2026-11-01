# Code Review

Date: 2026-11-01

Task: mail-delete-stale-trash - Mail actions: a message already gone from Trash counts as deleted, stale rows are cleaned up

Scope: The whole task diff, `git diff 8e0f00515..32f302f07` (six task commits on top of the base 8e0f005156e60f8ee3813dd1a45748afa70f0704). This third review also checks the fix for the previous review's CR-1 (commit 0742e2f8b) and its unit tests (commit 32f302f07). Only committed changes were reviewed, and the working tree was clean when the review started. Files the parallel test-writer may add later are not part of this review.

Requirements:
- tasks/mail-delete-stale-trash.md (acceptance criteria, notes, two recorded assumptions)
- AGENTS.md (project notes and conventions)
- Existing behavior of MailHeaderSyncService (`syncFolder` takes the per-folder lock and publishes the folder change event)
- Previous review (CR-1) and reviews/mail-delete-stale-trash/fixes.md

Git range:
- Branch: main
- HEAD: 32f302f07
- Base/start: 8e0f005156e60f8ee3813dd1a45748afa70f0704
- Commits reviewed: 6 (48aee449f, b91cda374, ff7a57fe7, ebef28e0e, 0742e2f8b, 32f302f07)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Checked against the criteria:
- AC1: `goneCountsAsDone` (MailActionService.kt:213) applies only to `Operation.Expunge`, which only a DELETE in a TRASH-role folder produces, and it requires a cached UIDVALIDITY. Before that, `remote()` compares the cached UIDVALIDITY with the server's (line 270) and returns `Remote.Invalidated` on a mismatch. A missing folder returns `Remote.Gone` (line 263). Both cases fail every id with NOT_FOUND and leave the rows as they are. Missing UIDs reach `updateCache`, then `applyToRows`, then `remove()`: a linked row is detached, any other row is deleted, and both are unindexed. The Trash `MAIL_FOLDER` event is published. When every UID is missing, `selected.isEmpty()` returns `Done(missing, ...)` without running a server command, and the rows are still removed.
- AC2: when any other operation has missing UIDs, those ids fail with NOT_FOUND and `resync.request(source.id)` is called (line 216). `MailFolderResync` runs `MailHeaderSyncService.syncFolder`, which takes the sync lock, on a virtual-thread executor, so the response does not wait for it. Requests for a folder whose sync is still pending are merged. A request made while a sync is running schedules another sync. Failures are logged at INFO/WARN with only the error enum or the exception class name, and a failure never reaches the action. MailFolderResyncTests covers merging, a request during a running sync, failures and shutdown.
- AC3: MailActionGreenMailTests contains (a), (b) and (c) under the names given in the task. Every test that triggers a background resync waits for it (`awaitUncached`, `awaitUidValidity`).
- AC4: MailActionServiceTests covers these outcomes: all gone, mixed, linked (detached), a mixed Trash/Inbox delete, changed UIDVALIDITY, missing folder, no cached UIDVALIDITY, resync limited to the affected folder, and no resync when every message is found.
- CR-1 fix: `updateCache` now passes a map from id to the UID the server command used (`expected`, line 356). `applyToRows` (line 382) only touches rows that are still in the source folder at exactly that UID. Effects on the other actions:
  - Expunge: rows that still have the expunged or missing UID are removed. A row a sync relocated to a new UID is left alone, and so is a row it already detached (its `uid` is null).
  - Move: `newUids[row.uid]` is now looked up with the same UID that was zipped with the COPYUID result in `move()`. So the COPYUID mapping is unchanged for normal rows, and a relocated row can no longer pick up another message's COPYUID entry.
  - Flags: they are only set on rows still at the commanded UID.
  - Detach of linked rows: still goes through `remove()`.
  - The change events are published as before.
  - Ids in one group cannot share a UID (one source folder, unique UID per folder), so `associate` loses nothing.
  - Commit 32f302f07 adds unit tests for these cases: a relocated row in Trash (gone and mixed), a detached row, a flag and a move.
- The API contract is unchanged. The KDoc of `MailAction.DELETE` and of `MailActionService` describes the final behavior. There are no secrets, no logging of message content and no new dependencies. `MailActionService` is constructed in only one test, and that test was updated.
- Evidence note (not a finding): the AC3 evidence in the task file was recorded at aedab811d, before the production change in 0742e2f8b. The parent worker says it re-runs the GreenMail suite and `./gradlew check` on the final code. That new run is the evidence to record for AC3 and AC4. I could not run Gradle in this review.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. The previous review's CR-1 is correctly fixed, and the fix does not change moves (COPYUID mapping), flag actions, expunges or detaching linked rows, apart from now skipping rows that a concurrent sync moved to a different UID.
