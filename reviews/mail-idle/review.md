# Code Review

Date: 2026-10-21

Task: mail-idle - IMAP IDLE push and polling

Scope: `git diff 9ad7341cc..3ea43689d` (3 commits), limited to server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/{InboxWatcher,ImapIdleConnector,MailPushProperties,MailPushService,NewMailAnnouncer,MailHeaderSyncService,MailHeaderStore}.kt, the tests in server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/ and server/src/test/resources/config/application.properties. The current HEAD (342f59f4b, rebased) has identical content for these paths. Untracked test files in the working tree (FakeIdleSession.kt, InboxWatcherTests.kt, MailPushPropertiesTests.kt) are not committed and were not reviewed.

Requirements:
- tasks/mail-idle.md (acceptance criteria and recorded assumptions)
- SPEC.md section 7.2 (sync model: IDLE on INBOX, reconnect with backoff, other folders polled every 5 min, new mail -> SSE event + optional per-account notification)
- AGENTS.md project notes (logging rules, module boundaries, shared files)
- docs/ARCHITECTURE.md
- Parent instructions (focus areas, evidence already run)

Git range:
- Branch: main
- HEAD: 342f59f4b7aae9d4102fe992dda11574243edf0c (reviewed revision 3ea43689dc407d83a9894a06e0aca73c7cdbe34c, same content in scope)
- Base/start: 9ad7341cc03d9d2a1b2b9ed47f4b952b5c386619
- Commits reviewed: 3

Summary: 0 P0, 1 P1, 3 P2, 0 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Reconnect/backoff and the 5-min polling of acceptance criterion 1 have no committed test**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailPushService.kt:84`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/InboxWatcher.kt:59`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/NewMailAnnouncer.kt:53`
    - Category: Tests
    - Problem: The only new test is `MailIdleGreenMailTests`. It covers IDLE delivery, re-IDLE (1 s interval), notifyNewMail on/off and deletion. At the reviewed revision nothing exercises: the retry after a failed connection or sync and the exponential backoff (`InboxWatcher.run`, `MailPushProperties.backoff`); the "server has no IDLE" path (`unsupported`); `MailPushService.poll` (other folders synced, Inbox skipped only while `idling`, Inbox polled while the IDLE is down or unsupported, a failing account skipped); restarting a watcher on `MailAccountConnectionChanged`; or the filters in `NewMailAnnouncer` (seen messages excluded, no notification outside the Inbox, at most 10 summaries, newest first). The untracked `InboxWatcherTests`/`MailPushPropertiesTests` in the working tree may cover part of the first two points, but they are not committed. Nothing covers `poll` or the announcer filters.
    - Why it matters: Criterion 1 names "reconnect/backoff" and "other folders polled every 5 min". With no test that runs these paths, there is no evidence to record for them, and a regression (for example, the Inbox never polled while IDLE is down) would not be caught.
    - Suggested fix: Commit unit tests for `InboxWatcher` (fake `IdleSession`/connector: failure -> backoff -> reconnect, `failures` reset, unsupported ends the watcher, stop during await/backoff), for `MailPushProperties.backoff` (doubling, cap), for `MailPushService.poll` with mocks (Inbox skipped only while `idling`, other selectable folders synced, `\Noselect` skipped, an exception for one account does not stop the others) and for `NewMailAnnouncer` (seen/non-Inbox/limit/order). Then record them as evidence for criteria 1 and 2.

## P2 Medium

- [ ] **CR-2 | A silently dropped IDLE connection leaves the Inbox unsynced for about 30 minutes or more, with no fallback poll**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailPushService.kt:94`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/ImapIdleConnector.kt:54`
    - Category: Correctness
    - Problem: `poll()` skips the Inbox while `watcher.idling` is true. `idling` only becomes false when `await()` throws. The IDLE socket's read timeout is `reIdleInterval + readTimeout` (29 min + 60 s by default). When the connection dies without a FIN/RST (NAT or router timeout, network change, server failover), the watcher notices only when the re-IDLE NOOP gets no answer or the 30-min read timeout expires. Angus also ignores socket timeouts while in IDLE state, so detection can take even longer. Until then new mail is neither pushed nor polled. The KDoc ("a dead connection is still detected") overstates what the timeout gives.
    - Why it matters: SPEC section 7.2 promises near-real-time mail with reconnect. In the common silent-drop case, the Inbox is checked less often than the 5-min poll that other folders get.
    - Suggested fix: Also sync the Inbox in `poll()` while it IDLEs, as a cheap CONDSTORE/QRESYNC safety net. Alternatively, bound detection with a shorter liveness check (for example, refresh/NOOP every few minutes, or TCP keepalive on the IDLE socket), and fix the KDoc.

- [ ] **CR-3 | Stopping or refreshing a watcher can block for up to the 30-minute IDLE socket timeout**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/InboxWatcher.kt:53`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailPushService.kt:54`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailPushService.kt:131`
    - Category: Correctness
    - Problem: `InboxWatcher.stop()` calls `session?.close()` synchronously before the bounded `join(timeout)`. `IMAPStore.close()` ends the IDLE (DONE) and logs out, and it waits for server responses on a socket whose read timeout is about 30 min. On a half-open connection, `stop()` therefore blocks for that long, despite its 5 s `timeout` parameter. This runs:
        - on the HTTP request thread that updated or deleted the account (`onAccountChanged` -> `restart`, after commit), while holding the `MailPushService` monitor, which also blocks `reconcile()` and so `poll()`;
        - in `SmartLifecycle.stop()`, which delays application shutdown.

      The same applies to `refresh()`: the NOOP waits for the IDLE to end and for a reply. All accounts share one `mail-re-idle` thread, so one dead connection delays the re-IDLE of every other account.
    - Why it matters: A user's account update request, or a server shutdown, can hang for tens of minutes because of one unresponsive mail server. Other accounts' IDLEs then run past the 29-min re-IDLE interval that RFC 2177 asks for.
    - Suggested fix: Close sessions off the caller's thread (fire-and-forget on a daemon thread or executor) so `stop()` is bounded by its timeout. Run `refresh()` so that one blocked account cannot starve the others, for example on the watcher's own thread or on a small pool or virtual threads. Keep the `@Synchronized` section free of network I/O.

- [ ] **CR-4 | The mail poll runs on Spring's single shared scheduling thread and delays reminders and other jobs**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailPushService.kt:84`
    - Category: Integration
    - Problem: `poll()` is a `@Scheduled` method. No `spring.task.scheduling.pool.size`, scheduler bean or virtual threads are configured (checked server/src/main/resources and all `@EnableScheduling`/`SchedulingConfigurer` uses), so it shares Spring Boot's default single-thread scheduler with `ReminderScheduler` (every 15 s), `CalendarSyncScheduler` (30 s), `ImapConnectionPool` eviction, the tickler and trash purge. One poll syncs the folder list and every selectable folder of every account in sequence over IMAP. That includes full 12-month header syncs of folders not yet cached, and it waits up to the connect/borrow/read timeouts for unreachable servers.
    - Why it matters: During a long poll, due reminders (a user-visible, time-critical feature) and calendar syncs do not fire until the poll ends.
    - Suggested fix: Run the poll on its own executor. For example, schedule it on the service's own `ScheduledExecutorService` (already present) or a dedicated scheduler instead of `@Scheduled`, or hand the work off from the `@Scheduled` method to a dedicated single-thread executor.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task.

Notes on what was checked and found sound:
- Logging: only account/folder ids and exception class names reach INFO/WARN. Details are logged at DEBUG only. No credentials or mail content are logged.
- The `mail` SSE notification carries from, subject and date but never a body, consistent with `ClientNotification`.
- Spring wiring is correct: `@ConditionalOnProperty` defaults on, the test config turns it off, `MailPushConfiguration` enables properties and scheduling, and `SseEventHub` uses `fallbackExecution = true`, so events from the non-transactional watcher thread reach clients.
- Account create, update and delete events start, restart and stop watchers.
- The watcher's start/stop ordering is race-free: the volatile `session` is assigned before the `isStopped` re-check.
- The re-IDLE interval stays below the socket timeout.
- Arrivals are announced only for incremental syncs.
