---
title: Mail actions: a message already gone from Trash counts as deleted, stale rows are cleaned up
status: in_progress
owner: dell26/agent-1
deps: [mail-actions-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/api,server/src/test/kotlin/ch/sonensei/cockpit/mail/api]
prio: 2
lease_until: 2026-11-01T08:45:48Z
releases: 1
extended: 
---
## Goal
Deleting messages from Trash fails with NOT_FOUND ("The message does not exist or is no longer on the server") for
messages the app still shows but that are already gone from the mail server, e.g. because another mail client
deleted them or the provider emptied Trash. Folders other than the Inbox are only polled (every 5 min by default), so
the cache can be that stale. The failed action also leaves the stale row in the cache, so the web list puts the message
back and shows the error until the next poll. Found by the owner on a real account (2026-10-30): deleting from Trash
works for some messages and fails for others.

## Acceptance criteria
- [x] `MailActionService`: DELETE on a message in the Trash folder whose UID is no longer in that folder on the server
      (the folder exists and its UIDVALIDITY matches) succeeds for that id: the outcome is ok, the cached row is removed
      the same way as after a permanent delete (detached when linked, else deleted; unindexed from search), and a
      `MAIL_FOLDER` change event is published for the folder. A changed UIDVALIDITY or a missing folder still fails
      with NOT_FOUND, because the message may still exist under another UID
  - verified (2026-11-01 09:30+0100 by dell26/agent-1): cd server && ./gradlew ktlintFormat check jacocoTestReport (at c63607475) -> BUILD SUCCESSFUL; MailActionServiceTests 45/0 failures incl. Trash all-gone/mixed/linked-detached/changed-UIDVALIDITY/missing-folder/no-UIDVALIDITY cases; GreenMail (a)(b) + Trash UIDVALIDITY test pass (integrationTest 20/0)
- [x] Any other action that finds some of its messages missing in their source folder still reports NOT_FOUND for
      those ids, and starts a sync of that folder in the background (not blocking the response, through
      `MailHeaderSyncService` so the sync lock applies). The stale rows then disappear from the cache, and the
      list refreshes through the folder's change event. A failing background sync is logged and does not fail the action
  - verified (2026-11-01 09:30+0100 by dell26/agent-1): MailActionServiceTests (NOT_FOUND + MailFolderResync.request for the affected folder only, partial MOVE) + MailFolderResyncTests 6/0 (background executor, failure logged, request during running sync) via ./gradlew check at c63607475 -> pass; GreenMail (c) move of a gone message -> NOT_FOUND and row dropped after background sync -> pass
- [x] GreenMail integration tests (`MailActionGreenMailTests`): (a) a message deleted from Trash by another IMAP client,
      then DELETE through the API -> ok, row gone, nothing left on the server; (b) a mix of present and already-gone
      messages in one Trash delete -> all ok; (c) a MOVE of a message deleted from its folder by another client ->
      NOT_FOUND, and after the background sync the row is no longer cached. Run with
      `cd server && ./gradlew integrationTest --tests '*MailActionGreenMailTests*'`
  - verified (2026-11-01 08:53+0100 by dell26/agent-1): cd server && ./gradlew integrationTest --tests '*MailActionGreenMailTests*' (GreenMail, at aedab811d) -> BUILD SUCCESSFUL, 20 tests 0 failures, incl. (a) 'a delete in Trash of a message another client already deleted there is ok and removes the row', (b) 'a delete in Trash of present and already gone messages reports all ok', (c) 'a move of a message another client deleted fails with NOT_FOUND and a background sync drops the row'
  - verified (2026-11-01 09:30+0100 by dell26/agent-1): cd server && ./gradlew integrationTest --tests '*MailActionGreenMailTests*' (GreenMail, at 32f302f07, production code identical to final c63607475) -> BUILD SUCCESSFUL, 20 tests 0 failures incl. (a), (b), (c)
- [ ] Unit tests in `MailActionServiceTests` for the new outcomes; `cd server && ./gradlew check` passes

## Notes
The API contract does not change (same request and result shape), so `api/openapi.yaml` and the clients stay as they
are; update the KDoc of `MailAction.DELETE` and of `MailActionService`. The web list needs no change: an ok outcome
already keeps the message removed. A related, unconfirmed suspect is a race between an action's cache update and a
concurrent header sync (the action does not take the sync lock); fix it here only if the tests show it.
- ASSUMPTION (2026-11-01 07:52+0100 by dell26/agent-2): The background resync runs on its own virtual-thread executor (new MailFolderResync in mail/api), merging requests for a folder whose sync is still pending; failures are logged at INFO/WARN without server responses.
- ASSUMPTION (2026-11-01 07:52+0100 by dell26/agent-2): A Trash folder without a cached UIDVALIDITY cannot prove the missing UID was deleted, so DELETE there keeps NOT_FOUND and starts a resync instead of counting it as deleted.
- release by dell26/agent-2: Implementation done and committed (Trash DELETE of gone UIDs counts as deleted; other actions NOT_FOUND + background resync via MailFolderResync; UIDVALIDITY change -> NOT_FOUND + resync; GreenMail tests a/b/c + unit tests). Second code-review (review.md) is committed: 0 P0-P2, 1 optional P3 (CR-1: Trash delete may remove a row a concurrent full sync just relocated; applyToRows should only remove rows whose uid equals the missing UID) - fix it or record it as deferred. LEFT: (1) decide on/fix the P3 (if code changes, re-run code-reviewer); (2) run test-writer on the final code -> reviews/mail-delete-stale-trash/tests.md is missing (last commit e5e847242 added one unit test from the interrupted test-writer run); (3) run ./gradlew check and integrationTest --tests '*MailActionGreenMailTests*' (passed 20/20 per fixes.md, re-run), record tasks.py verify 1-4; (4) write quality.json, sh scripts/check.sh, done.
