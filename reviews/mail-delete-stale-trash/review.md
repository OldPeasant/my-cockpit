# Code Review

Date: 2026-11-01

Task: mail-delete-stale-trash - Mail actions: a message already gone from Trash counts as deleted, stale rows are cleaned up

Scope: Task commit 673b91841 on top of base 337b68a09 (`git diff 337b68a09 673b91841 -- server`). The commit was later rebased to HEAD 0f65a1b04; `git diff 673b91841 0f65a1b04 -- server` is empty, so the reviewed code is the same. The test-writer's parallel additions are not part of this review.

Requirements:
- tasks/mail-delete-stale-trash.md (acceptance criteria, notes, recorded assumptions)
- AGENTS.md (project notes, conventions)
- docs/ARCHITECTURE.md (conventions; existing executor pattern in core/push/domain/PushDispatcher.kt)
- SPEC.md §7 (mail actions, header sync)

Git range:
- Branch: main
- HEAD: 0f65a1b0487cadfe66ae7704028f0e17109aacf2
- Base/start: 337b68a0994c419d32ba0f80360178059a10b465
- Commits reviewed: 1 (673b91841e015d8e21f07c1000dc77d3157ed8f2, the same code as 0f65a1b04)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

Checked against the criteria:
- AC1: `goneCountsAsDone` (MailActionService.kt:206) applies only to `Operation.Expunge`, which only a DELETE in a TRASH-role folder produces. `remote()` has already returned `Remote.Gone` when the folder is missing or its UIDVALIDITY differs (MailActionService.kt:255-263), so those cases still fail with NOT_FOUND. Missing rows go through `updateCache` -> `remove()` (detached when linked, else deleted, then unindexed), and the Trash `MAIL_FOLDER` event is published. This also works when every UID is missing, because `selected.isEmpty()` returns `Done(missing, ...)` and the rows still reach `updateCache`. A Trash folder with no cached UIDVALIDITY falls back to NOT_FOUND plus a resync, as the recorded assumption says.
- AC2: any other operation with missing UIDs reports NOT_FOUND for those ids and calls `MailFolderResync.request` after the cache update. The resync runs on a virtual-thread executor, the same pattern as PushDispatcher, and goes through `MailHeaderSyncService.syncFolder`, which takes the per-folder lock and publishes the folder event when it removed rows. Failures are caught and logged without server responses at INFO/WARN. Requests for the same folder merge correctly: the pending flag is cleared before the sync starts, so a request made during a sync is not lost.
- AC3: GreenMail tests (a), (b) and (c) are in MailActionGreenMailTests, plus a UIDVALIDITY-changed case. The fixture syncs every folder first (`syncAll`), so the incremental sync removes the stale row in (c).
- AC4: MailActionServiceTests covers the new outcomes: all gone, mixed, linked/detached, UIDVALIDITY changed, folder missing, no cached UIDVALIDITY, resync only for the affected folder. MailFolderResyncTests covers the executor wrapper.
- No API contract change. KDoc of `MailAction.DELETE` and `MailActionService` updated as the notes ask. No secrets and no logging of message content.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | GreenMail tests start background resyncs they never wait for**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailActionGreenMailTests.kt:257` and `:427`
    - Category: Tests
    - Problem: Two GreenMail tests now take the stale path and start a background resync of INBOX: `partial failures are reported per id` (FLAG on a message expunged on the server) and `an action on messages all gone from the server fails each with NOT_FOUND`. Neither waits for the resync. The virtual thread can still be syncing when the next test's `@BeforeEach` runs `delete from mail_account` and resets the GreenMail user. The leaked sync then races with the next test's setup and writes against rows that are being deleted.
    - Why it matters: Background work leaking across tests is a source of flaky integration runs and WARN noise. The second test also never checks that an all-gone non-Trash action starts the resync (the `selected.isEmpty()` path), although that is the AC2 behavior it exercises.
    - Suggested fix: In both tests, call `awaitUncached(...)` on the stale row, as the ARCHIVE test at line 423 already does. This fixes the isolation problem and covers AC2 on the all-gone path.

- [ ] **CR-2 | A changed UIDVALIDITY fails with NOT_FOUND but starts no resync**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt:201` (via `:263`)
    - Category: Correctness
    - Problem: When the source folder's UIDVALIDITY on the server differs from the cached value, `remote()` returns `Remote.Gone` and every id fails with NOT_FOUND, but no `resync.request` follows. In this state every cached UID of the folder is invalid. Until the next poll (5 minutes by default for non-Inbox folders), each action on any message in the folder fails the same way and the list keeps showing the rows. A `syncFolder` would run the full UIDVALIDITY resync and repair the folder right away.
    - Why it matters: The task's goal is that stale cache rows are cleaned up instead of failing repeatedly until the next poll. AC1 requires only that this case keeps NOT_FOUND, and AC2 talks about "messages missing in their source folder", so this is a gap in resilience, not a broken criterion.
    - Suggested fix: Let `remote()` return a separate result for a UIDVALIDITY mismatch, or flag it on `Remote.Gone`. Request a resync of the source folder in that case, but not for a missing folder: there `syncFolder` throws `RemoteFolderNotFoundException`, and the folder-list sync handles it.

## Review conclusion

No material issues found. Both findings are optional P3 improvements; CR-1 is cheap to fix and makes the integration suite more reliable.
