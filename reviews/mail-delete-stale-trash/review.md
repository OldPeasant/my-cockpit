# Code Review

Date: 2026-11-01

Task: mail-delete-stale-trash - Mail actions: a message already gone from Trash counts as deleted, stale rows are cleaned up

Scope: The three task commits b01db003f, 015993440 and 9d0a1dc48 on top of the base 02d78cc95 (`git merge-base HEAD origin/main`), reviewed with `git diff 02d78cc95 9d0a1dc48`. Reviewed revision: 9d0a1dc48663edacb4f7feda07e84b0101f5c3d8. Committed changes only. Uncommitted test files the parallel test-writer may add are not part of this review. This review also checks the fixes for the earlier findings CR-1 and CR-2 (fixes.md).

Requirements:
- tasks/mail-delete-stale-trash.md (acceptance criteria, notes, two recorded assumptions)
- AGENTS.md (project notes and conventions)
- docs/ARCHITECTURE.md conventions, and the existing executor pattern in core/push/domain/PushDispatcher.kt
- Existing behavior of MailHeaderSyncService (sync lock, full and incremental sync, change events)

Git range:
- Branch: main
- HEAD: 9d0a1dc48663edacb4f7feda07e84b0101f5c3d8
- Base/start: 02d78cc953a87ede453087bb14ad640fce3ceb54
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Checked against the criteria:
- AC1: `goneCountsAsDone` (MailActionService.kt:213) applies only to `Operation.Expunge`, and only a DELETE in a TRASH-role folder produces that operation. It also requires a cached UIDVALIDITY. `remote()` has already compared the cached UIDVALIDITY with the server's (line 270) and returned early on a mismatch (`Remote.Invalidated`) or a missing folder (`Remote.Gone`, line 263). Both early returns fail every id with NOT_FOUND and keep the rows. Missing rows go through `updateCache` -> `applyToRows` -> `remove()`: detached when linked, otherwise deleted, unindexed in both cases. The Trash `MAIL_FOLDER` event is published. When every UID is missing, the `selected.isEmpty()` path still returns `Done(missing, ...)`, so the rows reach `updateCache` and no server command runs. A Trash folder without a cached UIDVALIDITY falls back to NOT_FOUND plus a resync, as the recorded assumption says.
- AC2: when another operation has missing UIDs, it reports NOT_FOUND for those ids. After the cache update it calls `MailFolderResync.request(source.id)` (line 216). `MailFolderResync` runs `MailHeaderSyncService.syncFolder` on a virtual-thread executor, the same pattern as PushDispatcher, so the action's response does not wait. `syncFolder` takes the per-folder lock and publishes the folder event when it removed rows. The incremental sync detects vanished UIDs on all three paths (QRESYNC, CONDSTORE, flag scan). Failures are caught and logged at INFO/WARN with only the error enum or exception class name, so no server responses reach those levels. Requests for the same folder merge. Because `pending` is cleared before the sync starts, a request made during a running sync schedules a second sync and is not lost. MailFolderResyncTests covers this case.
- AC3: MailActionGreenMailTests contains (a) `a delete in Trash of a message another client already deleted there is ok and removes the row`, (b) `a delete in Trash of present and already gone messages reports all ok`, and (c) `a move of a message another client deleted fails with NOT_FOUND and a background sync drops the row`. The tests also cover Trash with a changed UIDVALIDITY. The fixture syncs every folder first (`syncAll`), so in (c) the incremental sync of Projects finds the vanished UID.
- AC4: MailActionServiceTests covers these outcomes: all gone, mixed, linked (detached), changed UIDVALIDITY (NOT_FOUND plus resync), missing folder (NOT_FOUND, no resync), no cached UIDVALIDITY, resync limited to the affected folder, and no resync when every message is found.
- Earlier fixes: CR-1 is fixed correctly. Every GreenMail test that triggers a background resync now waits for it, with `awaitUncached` or `awaitUidValidity`. The missing-folder GreenMail test starts no resync, so it has nothing to wait for. CR-2 is fixed correctly. `Remote.Invalidated` is separate from `Remote.Gone`: a changed UIDVALIDITY now requests a resync, which `full()` repairs by detaching the rows and finding them again by Message-ID. The GreenMail test checks that READ on the same id works again afterwards. A folder missing on the server still requests no resync, because `syncFolder` would throw `RemoteFolderNotFoundException` there.
- The API contract is unchanged. The KDoc of `MailAction.DELETE` and of `MailActionService` is updated, and it describes the final behavior, including the UIDVALIDITY resync. No secrets, no logging of message content, and no new dependencies.
- No criterion had recorded evidence in the task file at review time (all four are still unchecked). The parent worker still has to record it with `tasks.py verify`. I did not run the suites myself, so that the parallel test-writer's Gradle runs are not disturbed.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A Trash delete can remove a row that a concurrent full sync just relocated to a new UID**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt:378` (with `:182` and `:213-215`)
    - Category: Correctness
    - Problem: `applyInFolder` takes a row's UID from `locator.current` (line 151) and reads the source folder afterwards (line 182). Suppose a full sync of Trash after a UIDVALIDITY change commits between those two reads: it detaches the rows, finds them again by Message-ID under new UIDs and stores the new UIDVALIDITY. The action then compares the new cached UIDVALIDITY with the server, finds a match, and looks up the old UID, which is missing. Because of the new `goneCountsAsDone` rule, the old UID now counts as deleted. `applyToRows` then removes every row that has the requested id and is still in the folder (`it.folderId == source.id && it.uid != null`), and it does not check that the row still has the UID the server reported missing. So the freshly relocated row of a message that still exists is deleted, or detached when linked. A later incremental sync does not bring it back, because its UID is below the cached UIDNEXT. Before this task, a missing UID in a Trash delete left its row untouched.
    - Why it matters: In a narrow race, a message that is still on the server disappears from the cache. It requires a UIDVALIDITY change on Trash at the same time as a user's delete there, so it is unlikely. The task notes say to fix a sync/action race only if tests show it, which is why this is P3.
    - Suggested fix: Pass the missing UIDs (or a map from id to expected UID) to `applyToRows`. For rows counted as gone, remove only those whose `uid` still equals the UID the server reported missing. Alternatively, read the source folder before locating the messages.

## Review conclusion

No material issues found. The earlier findings CR-1 and CR-2 are correctly fixed. The single remaining finding is an optional P3 hardening against an unlikely race.
