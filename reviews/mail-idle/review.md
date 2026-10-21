# Code Review

Date: 2026-10-21

Task: mail-idle - IMAP IDLE push and polling (review round 2, final code)

Scope: `git diff 8dfa49e29096593b9a9e95c2c5f577eab718e2d7..c25536adfe381297928f10903b524722b34bac09` (7 commits). Reviewed: server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/{InboxWatcher,ImapIdleConnector,MailPushProperties,MailPushService,NewMailAnnouncer,MailHeaderSyncService,MailHeaderStore}.kt, every test in server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/ touched by the range, and server/src/test/resources/config/application.properties. The review reports in reviews/mail-idle/ were read for context only. No uncommitted changes in the working tree.

Requirements:
- tasks/mail-idle.md (3 acceptance criteria, 3 recorded assumptions)
- SPEC.md section 7.2 (IDLE on the Inbox of each account, reconnect with backoff, other folders polled every 5 min, new mail -> SSE event + optional per-account notification)
- AGENTS.md project notes (logging rules, module boundaries, touches)
- docs/ARCHITECTURE.md
- Round-1 review (reviews/mail-idle/review.md, previous version) and reviews/mail-idle/fixes.md
- Parent instructions (scope, focus areas, validation already run)

Git range:
- Branch: main
- HEAD: c25536adfe381297928f10903b524722b34bac09
- Base/start: 8dfa49e29096593b9a9e95c2c5f577eab718e2d7
- Commits reviewed: 7 (74e4b88c6, 02d36747f, 0e9f9dfff, df2ba5d67, a6d1fc4c4, 5da96d451, c25536adf)

Commands run by this reviewer (read-only):
- `git log --oneline 8dfa49e29..HEAD` -> 7 commits, as listed above
- `git diff --stat 8dfa49e29..HEAD` -> 20 files, 2227 insertions, 29 deletions
- `git diff 8dfa49e29..HEAD -- MailHeaderSyncService.kt MailHeaderStore.kt application.properties` -> reviewed hunks
- `git status --short` -> clean
- `grep` over SPEC.md section 7.2, `core/events` and api/openapi.yaml (how SSE notifications are documented)
- Tests were not re-run by this reviewer. The parent reported at c25536adf: `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sync.*' jacocoTestReport integrationTest --tests 'ch.sonensei.cockpit.mail.sync.*' ktlintCheck` -> all pass (InboxWatcherTests 13, MailPushServiceTests 17, NewMailAnnouncerTests 10, MailPushPropertiesTests 9, MailHeaderSyncServiceTests 27, MailHeaderStoreTests 22; MailIdleGreenMailTests 4, HeaderSyncGreenMailTests 14, HeaderSyncDovecotTests 2).

Summary: 0 P0, 1 P1, 0 P2, 1 P3

## Status of round-1 findings

- CR-1 (P1, no tests for reconnect/backoff, poll, restart, announcer filters): resolved. InboxWatcherTests cover the retry with doubling backoff, the `failures` reset, reconnect after an IDLE failure, a failing catch-up sync, the no-IDLE path, stop during backoff, stop during connect, re-IDLE scheduling and cancellation, and swallowed NOOP failures. MailPushPropertiesTests cover `backoff`. MailPushServiceTests cover poll (all selectable folders, `\Noselect` skipped, Inbox included, failing account or vanished folder isolated, exceptions contained), watcher start, restart and stop on account events, and the poll schedule. NewMailAnnouncerTests cover the seen, non-Inbox, limit, order and per-account filters.
- CR-2 (P2, silently dropped IDLE left the Inbox unsynced for about 30 min): resolved. `MailPushService.pollAccounts` (MailPushService.kt:103) syncs every selectable folder, the Inbox included. Concurrent Inbox syncs from the poll and the watcher are serialized by the per-folder lock in `MailHeaderSyncService.syncFolder` (MailHeaderSyncService.kt:95), so arrivals are neither stored nor announced twice. The KDocs no longer overstate dead-connection detection.
- CR-3 (P2, stop/refresh could block for the 30-min socket timeout): resolved. `InboxWatcher.stop` closes the session on a virtual thread and joins with a bound (InboxWatcher.kt:51-56). The re-IDLE task only starts a virtual thread for the NOOP (InboxWatcher.kt:86-101). The `@Synchronized` sections of `MailPushService` do no IMAP I/O. Regression tests with a hanging session exist (InboxWatcherTests.kt:91-138).
- CR-4 (P2, poll on Spring's shared scheduler): resolved. The poll runs on a dedicated `mail-poll` single-thread executor with `scheduleWithFixedDelay`, and `poll()` contains all exceptions so the schedule is not cancelled (MailPushService.kt:61-101). `@EnableScheduling` is still declared by the other modules' schedulers.

## P0 Critical

None.

## P1 High

- [ ] **CR-5 | The GreenMail test for criterion 3 also passes without IDLE push, because a 1-second re-IDLE triggers a sync every second**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailIdleGreenMailTests.kt:53`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/InboxWatcher.kt:106-108`
    - Category: Tests
    - Problem: The class sets `cockpit.mail.push.re-idle-interval=PT1S` for all its tests. Every re-IDLE NOOP ends `await()`, and the watcher then runs `sync()` (`current.await(); if (!isStopped) sync()`). So the Inbox is synced every second whether or not the server's IDLE notification ever ends the wait. `a delivered message appears within seconds without polling ...` allows 10 s (`ARRIVAL_LIMIT`). It would still pass if `folder.idle(true)` never returned on EXISTS, or if `ImapIdleSession.await` were replaced by a sleep. The 1-hour `poll-interval` turns off only the 5-min poll, not this effective 1-second poll.
    - Why it matters: Criterion 3 asks for evidence that a delivered message appears "within seconds without polling", i.e. through IDLE push. The current test cannot tell push apart from a 1-second re-IDLE poll, so the recorded evidence for criterion 3 does not show what the criterion names. The real-server IDLE path (`ImapIdleSession.await` on Angus) has no other test.
    - Suggested fix: Run the delivery test with a re-IDLE interval much longer than the arrival limit (for example PT10M), in its own test class or a nested `@SpringBootTest` with different properties. Keep the 1-second interval only for `mail keeps arriving across re-IDLEs`. Optionally assert that no refresh happened before the arrival. Then re-record the criterion 3 evidence from that run.

## P2 Medium

None.

## P3 Low

- [ ] **CR-6 | `MailPushService` cannot be started again after `stop()`**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailPushService.kt:76`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailPushService.kt:82-83`
    - Category: Correctness
    - Problem: The `scheduler` and `poller` executors are created once in the constructor, and `stop()` calls `shutdownNow()` on both. A later `SmartLifecycle.start()` (for example `ConfigurableApplicationContext.stop()` followed by `start()`) then throws `RejectedExecutionException` from `poller.scheduleWithFixedDelay`. Watchers that `reconcile()` started just before would fail on `scheduler.scheduleAtFixedRate` and retry with backoff indefinitely.
    - Why it matters: The lifecycle contract of `SmartLifecycle` allows stop/start cycles. Normal application startup and shutdown are not affected, so the impact is low.
    - Suggested fix: Create the executors in `start()` (and null or replace them in `stop()`), or document and enforce single use, for example by making `start()` a no-op with a warning once the service was stopped.

## Review conclusion

Material issues found; fix before completing the task.

CR-5 must be fixed (or criterion 3 re-verified with a test that isolates IDLE push) before criterion 3 can count as verified. CR-6 is optional cleanup.

Also checked and found sound:
- Threading and lifecycle: `session` is published before the `isStopped` re-check, so a stop during connect closes the new connection without syncing. The re-IDLE task is scheduled only after that check and cancelled in `finally`. `running` is checked inside the `@Synchronized` `reconcile`/`restart`, so no watcher survives `MailPushService.stop()`. A watcher whose close hangs ends on its own once the socket fails, because `isStopped` is set first.
- Reconnect/backoff: `failures` resets only after a successful catch-up sync. The backoff doubles and is capped (`MailPushProperties.backoff`). The property validation rejects non-positive intervals and an initial backoff above the maximum.
- Re-IDLE: 29 min by default, below the socket read timeout (`reIdleInterval + readTimeout`). Each refresh is followed by a sync, so arrivals consumed by the NOOP are not lost.
- Events: only incremental syncs report `arrived` ids (`createdIds` from `MailHeaderStore.upsert`; reattached rows count as updates). Each arrival gets an `EMAIL` CREATED change event. The `mail` notification is limited to unread Inbox arrivals of accounts with `notifyNewMail`, with at most 10 summaries and no body. The SSE hub forwards events from the non-transactional watcher and poll threads.
- Security/logging: INFO/WARN logs carry only account/folder ids and exception class names. Exceptions and details go to DEBUG. No credentials, bodies or subjects are logged. Test data is fictitious. Credentials are fetched again on every reconnect (fresh OAuth tokens).
- Scope: changes stay within the task's `touches`, plus the test property that the recorded assumption explains.
