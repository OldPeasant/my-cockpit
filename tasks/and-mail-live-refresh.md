---
title: Android: mail views reload when new mail syncs (New, Mail list, Today), not only on resume
status: in_progress
owner: dell26/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/newitems,android/app/src/main/java/ch/sonensei/cockpit/mail/list,android/app/src/main/java/ch/sonensei/cockpit/dashboard,android/app/src/main/java/ch/sonensei/cockpit/gtd/data/GtdRefresh.kt,android/app/src/test/java/ch/sonensei/cockpit/newitems,android/app/src/test/java/ch/sonensei/cockpit/mail/list,android/app/src/test/java/ch/sonensei/cockpit/dashboard]
prio: 2
lease_until: 2026-11-14T20:16:51Z
---
## Goal
Owner report (2026-11-14): with the Android New view open (filter All, GTD Inbox or Mail, switching makes no
difference), a newly arrived mail does not appear, not even after more than 5 minutes. It shows only after leaving New
and opening it again. Whatever mail view is open should update when the server's mail sync (every 5 min, or IMAP IDLE)
brings new mail. SPEC.md §2 says Android uses "SSE while the app is in the foreground".

Cause (diagnosis by an interactive session):
- The server already announces new mail: `NewMailAnnouncer.announce` publishes an `EMAIL` `CREATED` change event per
  arrived message over `GET /api/v1/events`, and `MailHeaderSyncService` publishes `MAIL_FOLDER` `UPDATED` when a
  folder sync changed or loaded anything (deleted, moved or read elsewhere). Nothing to change on the server.
- On Android only the GTD views listen to that stream: `GtdRefreshTriggers.on(types)` (`gtd/data/GtdRefresh.kt`) over
  `GtdChangeStream.signals`. The New view's GTD Inbox part therefore updates live, but its mail part does not.
- `NewViewModel` loads mail only in `refresh()`, which `NewScreen` calls from `LifecycleResumeEffect` (screen shown or
  app back in the foreground). Changing the filter only filters the rows already loaded. So nothing reloads mail while
  the screen stays open.
- Same gap in `MailListViewModel` / `MailScreen` (also only `LifecycleResumeEffect`), and in the Today dashboard's
  unread-mail count (`DashboardRepository`, loaded on resume).

## Acceptance criteria
- [x] While the New view is shown, an `EMAIL` or `MAIL_FOLDER` change event, or a reconnect of the change stream (it
      does not replay events missed meanwhile), reloads the first mail page the way `refresh()` does (pages already
      loaded behind it stay, scroll position kept, a load in progress is not started twice). This works for every
      filter. Use the existing change stream: a mail flavour of `GtdRefreshTriggers` (e.g. `on("EMAIL", "MAIL_FOLDER")`
      without the GTD sync scheduling, or a small shared trigger next to it). No second SSE connection.
  - verified (2026-11-14 20:58+0100 by dell26/agent-1): android testDebugUnitTest (via sh scripts/check.sh, exit 0; test-writer full run on same code) -> NewMailLiveRefreshTest 14/14 pass (EMAIL/MAIL_FOLDER/reconnect reload first page, pages kept, no double load), MailRefreshTriggersTest 7/7 pass
- [x] Same for the Mail list (`MailListViewModel`, current mailbox; the search results are not reloaded) and for the
      Today dashboard's unread-mail count.
  - verified (2026-11-14 20:58+0100 by dell26/agent-1): android testDebugUnitTest (via sh scripts/check.sh) -> MailListLiveRefreshTest 16/16 (current mailbox, search not reloaded), DashboardMailRefreshTest 8/8 (Inbox widget unread count) pass
- [x] Bursts are coalesced: a sync that brings N mails (N `EMAIL` events plus `MAIL_FOLDER`) triggers about one reload,
      not N (e.g. debounce ~1 s or conflate while a load runs). Events only reload while the screen is shown (collected
      with the lifecycle); nothing polls in the background.
  - verified (2026-11-14 20:58+0100 by dell26/agent-1): android testDebugUnitTest (via sh scripts/check.sh) -> burst/quiet-time tests in NewMailLiveRefreshTest, MailListLiveRefreshTest, DashboardMailRefreshTest, MailRefreshTriggersTest pass; FollowMailChangesTest 2/2 (repeatOnLifecycle STARTED gating) pass
- [x] A reload triggered by an event that fails offline or with an error keeps the shown mails and does not raise an
      error notice or banner on top of what is already there beyond what a manual refresh shows today.
  - verified (2026-11-14 20:58+0100 by dell26/agent-1): android testDebugUnitTest (via sh scripts/check.sh) -> offline/failed event reload tests (New, Mail list, Dashboard: shown mails kept, no banner, no spinner) pass
- [ ] Unit tests: a `MAIL_FOLDER`/`EMAIL` signal reloads the New view's mail part (new mail at the top, with filter
      All and Mail); a burst gives one reload; a `TASK` signal does not reload mail; the same for the Mail list.
- [~] Manually verified on an emulator or device against a running server: New view open, a mail delivered to the
      account, it appears without leaving the view (record what you ran); if no emulator or device is available, skip
      with reason and assume what you could not see
  - skipped (2026-11-14 20:29+0100 by dell26/agent-1): no Android emulator/device or mail server delivery available in this agent environment
- [ ] `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-14 from the owner's bug report. Android only; the server already sends the events. Keep it small: no
new periodic mail polling, no changes to the push (UnifiedPush) handling. If the web New view or mail list has the same
gap, note it as a separate task rather than fixing it here.
- ASSUMPTION (2026-11-14 20:29+0100 by dell26/agent-1): Event-triggered mail reloads are quiet: no loading indicator, and an offline/failed reload leaves the shown mails and existing flags/messages unchanged (no new banner); a manual refresh still shows them as before.
- ASSUMPTION (2026-11-14 20:29+0100 by dell26/agent-1): Coalescing: MailRefreshTriggers debounces EMAIL/MAIL_FOLDER/Reconnected signals by 1 s, and a signal arriving while any load (incl. load more) runs is dropped rather than queued; the next event or resume catches up.
- ASSUMPTION (2026-11-14 20:29+0100 by dell26/agent-1): Events are collected per screen with repeatOnLifecycle(STARTED) (new FollowMailChanges composable in mail/list); the Today dashboard reloads only its Today widget (the one with the unread count) on mail events.
- ASSUMPTION (2026-11-14 20:29+0100 by dell26/agent-1): Not seen on a device: assumed the server's EMAIL CREATED / MAIL_FOLDER UPDATED events reach the shared GtdChangeStream on Android as the GTD views' TASK events do (same SSE endpoint, type filter only).
- ASSUMPTION (2026-11-14 20:47+0100 by dell26/agent-1): Supersedes earlier assumptions: the dashboard's unread-mail count is in the Inbox widget, so mail events reload only that widget (not Today); a mail event during a running first-page load is deferred (one follow-up quiet reload when it ends), during paging it reloads at once.
