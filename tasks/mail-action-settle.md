---
title: Moved, archived or deleted mail must not come back in the source list until the next 5-min sync
status: in_progress
owner: Peter Gürtler
deps: []
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt,server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailFolderResync.kt,server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt,server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderStore.kt,server/src/test/kotlin/ch/sonensei/cockpit/mail,web/src/app/features/mail/list/mail-list-store.ts,web/e2e/mail-list]
prio: 2
lease_until: 
releases: 0
extended: 
hold: 
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
- [x] Belt and braces: after every move/archive/delete (and spam) the server immediately resyncs the source folder,
      and for a move the target folder too, in the background (`MailFolderResync.request`, no wait in the request).
      That sync uses vanished detection that does not depend on a modseq the racing sync may have advanced, e.g. a
      UID-set comparison like the non-CONDSTORE path for this resync. It announces the folders with `MAIL_FOLDER`, so
      open clients converge within seconds and never wait for the 5-min poll. Fix the root cause found above as well.
  - verified (2026-11-14 22:01+0100 by felix/agent-1): cd server && ./gradlew test --tests '*MailActionServiceTests' --tests '*MailFolderResyncTests' --tests '*MailHeaderSyncServiceTests' at d1c6b807c -> 107 passed (settle -> requestVerified for source+target on move/archive/spam, source on delete; verifyFolder UID-set comparison)
- [ ] A sync of a folder never brings back a row the action removed for a message that is no longer in that folder
      on the server (regression test for the found race).
- [x] Web: `mail-list` e2e checks that the archived/deleted/moved mail is still gone after the change-event refetch
      and after a page reload (not only right after the click). Same for archiving from the read view and going back.
  - verified (2026-11-14 22:01+0100 by felix/agent-1): cd web && npm run e2e -- e2e/mail-list/ --project=chromium (docker-compose.e2e stack via run-e2e.mjs, GreenMail 2.1.5) at d1c6b807c -> 36 passed; list.spec.ts checks deleted/archived/moved mail gone after 3s refetch wait, reload and API, incl. read-view archive + in-app back (a.back)
- [ ] `cd server && ./gradlew check`, `cd web && npm run check`, `sh scripts/check.sh` pass; the server GreenMail
      tests that touch mail actions/sync pass (record which you ran).

## Notes
Planned 2026-11-14 from the owner's report; diagnosis by an interactive session (probe specs were temporary, not
committed). Android shares the server; its lists remove acted mails optimistically, so it profits from the server
fix without client changes. If the owner's server log is needed (mail provider, capabilities), record an assumption
instead of waiting.
- ASSUMPTION (2026-11-14 20:54+0100 by felix/agent-1): Root cause not reproduced against GreenMail by the earlier probe; most plausible real-server cause is candidate 1/2 (cache update giving up after conflicts with a concurrent IDLE-triggered Inbox sync, or a QRESYNC sync whose stored HIGHESTMODSEQ hides the expunge). Fixed generically: after MOVE/ARCHIVE/SPAM/DELETE the source (and target) folder is resynced in the background via MailHeaderSyncService.verifyFolder, which detects vanished UIDs by UID-set comparison independent of modseq. The verify resync publishes MAIL_FOLDER only when it changed rows (the action already announced the folders).
- release by felix/agent-1: Done: verified UID-set resync of source+target after move/archive/spam/delete (MailActionService.settle -> MailFolderResync.requestVerified -> MailHeaderSyncService.verifyFolder); unit tests in MailFolderResyncTests/MailHeaderSyncServiceTests; new settle tests in MailActionServiceTests (NOT RUN: Gradle denied to helpers); e2e mail-list extended (delete, archive from read view, check after refetch+reload) - not yet run to completion. Review (reviews/mail-action-settle/review.md) P1 CR-1: 4 existing MailActionServiceTests (lines ~521,556,619,676) still expect resync.request on move/delete paths -> update to requestVerified. CR-3: replace 'plain QRESYNC sync keeps a row' (locks in bug) by a proper fail-before-fix repro (cache-conflict path is unit-reproducible). P3: CR-4 extra UID search in verify mode on CONDSTORE/plain paths; CR-5 stale log/KDoc wording. Left: run fixer, gradle tests + GreenMail tests (MailActionGreenMailTests, HeaderSyncGreenMailTests, MailIdle*), npm run e2e -- mail-list mail-read, rerun test-writer+code-reviewer on final code, quality.json, verify criteria, done.
- release by felix/agent-1: Done: review findings CR-1..5 fixed (1f4e045f8). Re-review (review.md): 0 P0/P1; open P2 CR-1: web/e2e/mail-list/list.spec.ts:143-157 archives from read view and then page.goto()s the folder instead of in-app back navigation -> change to in-app back (e.g. back button/history) and assert gone; P3 CR-2 (assert requestVerified not called on flag paths, MailActionServiceTests ~566,633), CR-3 (stale 'left to next sync' KDoc/log MailActionService.kt:368,426), CR-4 (rewrap KDoc MailFolderResync.kt:17). Test-writer re-run (tests.md, e92099f82 tightened one test): focused unit tests 107/107 pass, coverage 94% instr. GreenMail ran by me at 1f4e045f8: ./gradlew integrationTest --tests MailActionGreenMailTests(20) HeaderSyncGreenMailTests(15) MailIdleGreenMailTests(4) MailIdlePushGreenMailTests(1) MailTaskArchiveGreenMailTests(4) -> all pass. Left: fixer for review CR-1..4, run npm run e2e mail-list (+ mail-read), rerun test-writer+code-reviewer if code changes, ./gradlew check, npm run check, scripts/check.sh, verify criteria 1-5, quality.json, done. Note: don't run gradle concurrently with helpers in same build dir (EOFException).
- release by felix/agent-1: Done at d1c6b807c+: round-2 review fixes; final code-reviewer (0 P0-P2, 1 optional P3: list.spec.ts:163 refetch step is a fixed 3s wait, reload+API checks still cover it -> disposition: accepted, not fixed) and test-writer (107/107, 94% instr) reports committed for d1c6b807c (fresh). Verified #2, #4 (e2e mail-list 36 passed by fixer). web npm run check passed this session. Left: verify #1/#3 (point to MailActionServiceTests cache-conflict repro + MailHeaderSyncServiceTests regression tests, see review.md), #5: run ./gradlew check and integrationTest GreenMail suites SEPARATELY (combined run exceeded 10-min tool timeout); last GreenMail run at 1f4e045f8 all passed (Action 20, HeaderSync 15, Idle 4, IdlePush 1, TaskArchive 4). Then scripts/check.sh, write quality.json (reviewed_revision d1c6b807c, fixer round 2 in fixes.md), done.
