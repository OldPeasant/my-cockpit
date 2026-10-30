---
title: Mail actions: a message already gone from Trash counts as deleted, stale rows are cleaned up
status: todo
owner:
deps: [mail-actions-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/api,server/src/test/kotlin/ch/sonensei/cockpit/mail/api]
prio: 2
---
## Goal
Deleting messages from Trash fails with NOT_FOUND ("The message does not exist or is no longer on the server") for
messages the app still shows but that are already gone from the mail server, e.g. because another mail client
deleted them or the provider emptied Trash. Folders other than the Inbox are only polled (every 5 min by default), so
the cache can be that stale. The failed action also leaves the stale row in the cache, so the web list puts the message
back and shows the error until the next poll. Found by the owner on a real account (2026-10-30): deleting from Trash
works for some messages and fails for others.

## Acceptance criteria
- [ ] `MailActionService`: DELETE on a message in the Trash folder whose UID is no longer in that folder on the server
      (the folder exists and its UIDVALIDITY matches) succeeds for that id: the outcome is ok, the cached row is removed
      the same way as after a permanent delete (detached when linked, else deleted; unindexed from search), and a
      `MAIL_FOLDER` change event is published for the folder. A changed UIDVALIDITY or a missing folder still fails
      with NOT_FOUND, because the message may still exist under another UID
- [ ] Any other action that finds some of its messages missing in their source folder still reports NOT_FOUND for
      those ids, and starts a sync of that folder in the background (not blocking the response, through
      `MailHeaderSyncService` so the sync lock applies). The stale rows then disappear from the cache, and the
      list refreshes through the folder's change event. A failing background sync is logged and does not fail the action
- [ ] GreenMail integration tests (`MailActionGreenMailTests`): (a) a message deleted from Trash by another IMAP client,
      then DELETE through the API -> ok, row gone, nothing left on the server; (b) a mix of present and already-gone
      messages in one Trash delete -> all ok; (c) a MOVE of a message deleted from its folder by another client ->
      NOT_FOUND, and after the background sync the row is no longer cached. Run with
      `cd server && ./gradlew integrationTest --tests '*MailActionGreenMailTests*'`
- [ ] Unit tests in `MailActionServiceTests` for the new outcomes; `cd server && ./gradlew check` passes

## Notes
The API contract does not change (same request and result shape), so `api/openapi.yaml` and the clients stay as they
are; update the KDoc of `MailAction.DELETE` and of `MailActionService`. The web list needs no change: an ok outcome
already keeps the message removed. A related, unconfirmed suspect is a race between an action's cache update and a
concurrent header sync (the action does not take the sync lock); fix it here only if the tests show it.
