---
title: Moved, archived or deleted mail must not come back in the source list until the next 5-min sync
status: in_progress
owner: felix/agent-1
deps: []
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt,server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailFolderResync.kt,server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt,server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderStore.kt,server/src/test/kotlin/ch/sonensei/cockpit/mail,web/src/app/features/mail/list/mail-list-store.ts,web/e2e/mail-list]
prio: 2
lease_until: 2026-11-14T20:24:14Z
releases: 1
extended: 
---
## Goal
Owner report (2026-11-14, web app, real mail account): after moving, archiving or deleting a mail, it stays in (or comes
back to) the list it was acted on from until the next 5-minute sync. The owner wants the result to be visible at once.

What an interactive session found (2026-11-14):
- Client side looks right. `MailListStore` removes the mail optimistically. The server publishes `MAIL_FOLDER` for
  the source and target after the action (`MailActionService.updateCache`), and the store refetches on it. So a mail
  that "stays" or "comes back" means the server's **cache** still lists it in the source folder when the list is
  refetched. Reloading the page would show it too.
- **Not reproduced against GreenMail.** A temporary Playwright probe on the e2e stack archived and deleted from the
  Mail list (Messages and Conversations view) and archived from the read view. 8 s later, after a reload, on IMAP and
  in `GET /api/v1/mail/messages`, all three mails were gone from the Inbox. So it depends on the real server's timing
  or capabilities (IDLE notifications arriving during the action, MOVE/UIDPLUS/QRESYNC support).
- Candidate causes to check (pick by evidence, not by guess):
  1. `updateCache` gives up after `CACHE_ATTEMPTS` conflicts ("left to the next sync") and only publishes
     `MAIL_FOLDER`. The refetch then shows the stale row until the 5-min poll.
  2. Race with a concurrent `syncFolder` of the source. The action does not take the header-sync folder lock. The IMAP
     server's EXPUNGE notification makes the `InboxWatcher` sync the Inbox while the action runs. If that sync read
     the folder state before the move and finishes after it, it can store a `HIGHESTMODSEQ`/`uidNext` that already
     covers the expunge while keeping or re-adding the row. Its next QRESYNC/CONDSTORE run then never reports the
     message as vanished.
  3. Rows of a conversation (Conversations view) acted on by thread, where some messages of the thread are not in the
     group the cache update handles.

## Acceptance criteria
- [ ] Find the actual cause. Write a server test that reproduces the stale source row: e.g. a GreenMail test that runs
      `syncFolder` of the source concurrently with a MOVE/ARCHIVE/DELETE (latch-controlled interleaving), or the
      cache-conflict path. It must fail before the fix. If none of the candidates reproduces, record that with
      `tasks.py assume` and still do the next criterion.
- [ ] Belt and braces: after every move/archive/delete (and spam) the server immediately resyncs the source folder,
      and for a move the target folder too, in the background (`MailFolderResync.request`, no wait in the request).
      That sync uses vanished detection that does not depend on a modseq the racing sync may have advanced, e.g. a
      UID-set comparison like the non-CONDSTORE path for this resync. It announces the folders with `MAIL_FOLDER`, so
      open clients converge within seconds and never wait for the 5-min poll. Fix the root cause found above as well.
- [ ] A sync of a folder never brings back a row the action removed for a message that is no longer in that folder
      on the server (regression test for the found race).
- [ ] Web: `mail-list` e2e checks that the archived/deleted/moved mail is still gone after the change-event refetch
      and after a page reload (not only right after the click). Same for archiving from the read view and going back.
- [ ] `cd server && ./gradlew check`, `cd web && npm run check`, `sh scripts/check.sh` pass; the server GreenMail
      tests that touch mail actions/sync pass (record which you ran).

## Notes
Planned 2026-11-14 from the owner's report; diagnosis by an interactive session (probe specs were temporary, not
committed). Android shares the server; its lists remove acted mails optimistically, so it profits from the server
fix without client changes. If the owner's server log is needed (mail provider, capabilities), record an assumption
instead of waiting.
- ASSUMPTION (2026-11-14 20:54+0100 by felix/agent-1): Root cause not reproduced against GreenMail by the earlier probe; most plausible real-server cause is candidate 1/2 (cache update giving up after conflicts with a concurrent IDLE-triggered Inbox sync, or a QRESYNC sync whose stored HIGHESTMODSEQ hides the expunge). Fixed generically: after MOVE/ARCHIVE/SPAM/DELETE the source (and target) folder is resynced in the background via MailHeaderSyncService.verifyFolder, which detects vanished UIDs by UID-set comparison independent of modseq. The verify resync publishes MAIL_FOLDER only when it changed rows (the action already announced the folders).
- release by felix/agent-1: Done: verified UID-set resync of source+target after move/archive/spam/delete (MailActionService.settle -> MailFolderResync.requestVerified -> MailHeaderSyncService.verifyFolder); unit tests in MailFolderResyncTests/MailHeaderSyncServiceTests; new settle tests in MailActionServiceTests (NOT RUN: Gradle denied to helpers); e2e mail-list extended (delete, archive from read view, check after refetch+reload) - not yet run to completion. Review (reviews/mail-action-settle/review.md) P1 CR-1: 4 existing MailActionServiceTests (lines ~521,556,619,676) still expect resync.request on move/delete paths -> update to requestVerified. CR-3: replace 'plain QRESYNC sync keeps a row' (locks in bug) by a proper fail-before-fix repro (cache-conflict path is unit-reproducible). P3: CR-4 extra UID search in verify mode on CONDSTORE/plain paths; CR-5 stale log/KDoc wording. Left: run fixer, gradle tests + GreenMail tests (MailActionGreenMailTests, HeaderSyncGreenMailTests, MailIdle*), npm run e2e -- mail-list mail-read, rerun test-writer+code-reviewer on final code, quality.json, verify criteria, done.
