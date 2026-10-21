# Code Review

Date: 2026-10-21

Task: mail-idle - IMAP IDLE push and polling (review round 3, final code)

Scope: `git diff 8dfa49e29096593b9a9e95c2c5f577eab718e2d7..71457b4c82a8247c861859e69062d7d68cb92c50`, with focus on the changes since round 2 (`git diff c25536adf 71457b4c8 -- server`: new `MailIdlePushGreenMailTests.kt`, changed `MailPushPropertiesTests.kt` and `MailPushServiceTests.kt`). Production code is unchanged since round 2 (`git diff --stat c25536adf 71457b4c8 -- server/src/main` is empty). The production files `InboxWatcher.kt`, `MailPushService.kt`, `ImapIdleConnector.kt` and `MailPushProperties.kt` were re-read in full to check the new tests against them. No uncommitted changes in the working tree.

Requirements:
- tasks/mail-idle.md (3 acceptance criteria, 3 recorded assumptions)
- SPEC.md section 7.2 (IDLE on the Inbox, reconnect with backoff, other folders polled every 5 min, new mail events)
- AGENTS.md project notes, docs/ARCHITECTURE.md
- Round-2 review (previous version of this file), reviews/mail-idle/tests.md, reviews/mail-idle/fixes.md
- Parent instructions (scope, CR-5 check, CR-6 decision, validation already run)

Git range:
- Branch: main
- HEAD: 71457b4c82a8247c861859e69062d7d68cb92c50
- Base/start: 8dfa49e29096593b9a9e95c2c5f577eab718e2d7
- Commits reviewed: 8 (74e4b88c6, 02d36747f, 0e9f9dfff, df2ba5d67, a6d1fc4c4, 5da96d451, c25536adf, 71457b4c8); round 3 adds 71457b4c8

Commands run by this reviewer (read-only):
- `git diff --stat c25536adf 71457b4c8` -> 5 files: 3 test files under server/src/test, reviews/mail-idle/{review,tests}.md; no production file
- `git diff c25536adf 71457b4c8 -- server` -> reviewed in full
- `grep` for `@Scheduled`, `scheduleWith*`, `scheduleAtFixedRate`, `SmartLifecycle`, `syncFolder`, `syncAccount` in server/src/main -> the only background Inbox syncs are `InboxWatcher` (catch-up and post-await) and `MailPushService.poll`; `MailTestSupportController.syncAccount` is only called explicitly
- `grep` for application-context `stop()`/`start()` calls in server/src -> none
- `python3 tasks.py verify mail-idle` (read-only listing) -> criteria #1-#3 not recorded yet
- `git status --short` -> clean
- Tests were not re-run by this reviewer. The parent reported at 71457b4c8: `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sync.*' jacocoTestReport integrationTest --tests 'ch.sonensei.cockpit.mail.sync.*' ktlintCheck` -> all pass, 0 failures (InboxWatcherTests 13, MailPushServiceTests 20, MailPushPropertiesTests 11, NewMailAnnouncerTests 10, MailHeaderSyncServiceTests 27, MailHeaderStoreTests 22, ImapHelpersTests 33, MailThreadingTests 35; MailIdlePushGreenMailTests 1, MailIdleGreenMailTests 4, HeaderSyncGreenMailTests 14, HeaderSyncDovecotTests 2).

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Status of earlier findings

- CR-1 (P1, round 1): resolved (round 2). The new tests add coverage for the `restart` guard while not running, the `!alive && !unsupported` restart in `reconcile`, and the "no Inbox" path.
- CR-2, CR-3, CR-4 (P2, round 1): resolved (round 2); production code unchanged since.
- CR-5 (P1, round 2, criterion 3 test passed without IDLE push): resolved. `MailIdlePushGreenMailTests` sets `re-idle-interval=PT1H` and `poll-interval=PT1H`. The re-IDLE task is scheduled with an initial delay equal to the interval (`InboxWatcher.kt:86-101`) and the poll with `scheduleWithFixedDelay(::poll, interval, interval, ...)` (`MailPushService.kt:75-76`), so neither has a first run within the 10 s limit. The catch-up sync runs before `idling = true` (`InboxWatcher.kt:103-105`), and `setUp` waits for `idling` before sending, so the initial sync cannot pick up the message. No other component syncs the Inbox in the background. Within a healthy connection, only `await()` returning on the server's notification can bring the message in. One narrow path remains, reconnect after a failed IDLE. It is reported as CR-7 (P3) and does not block criterion 3.
- CR-6 (P3, round 2, `MailPushService` cannot start again after `stop()`): the parent accepted it without a fix. I agree. Nothing in server/src calls `stop()`/`start()` on the application context, and `@DirtiesContext` closes the context instead of restarting it. Spring therefore never restarts this bean in this application. The finding stays open as a known limitation. Low risk.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-6 | `MailPushService` cannot be started again after `stop()` (accepted, not fixed)**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailPushService.kt:76`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailPushService.kt:82-83`
    - Category: Correctness
    - Problem: The executors are created once and shut down in `stop()`. A later `start()` would throw `RejectedExecutionException`.
    - Why it matters: Only an explicit context stop/start cycle hits this, and the application has none. The parent's decision to accept it is reasonable.
    - Suggested fix: None required now. If a lifecycle restart is ever introduced, create the executors in `start()`.

- [ ] **CR-7 | The push test cannot tell a push from a reconnect's catch-up sync**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailIdlePushGreenMailTests.kt:39-40`, `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailIdlePushGreenMailTests.kt:97-99`; `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/InboxWatcher.kt:68-75,103-105`
    - Category: Tests
    - Problem: The test uses `initial-backoff=PT0.2S` and `max-backoff=PT1S`. Suppose `await()` failed instead of returning on the server's notification (for example `folder.idle(true)` throwing, or the connection being dropped when the message arrives). The watcher would then reconnect after 0.2 s and run its catch-up sync, which brings the message in well within 10 s. That catch-up sync also resets `failures` to 0 and sets `idling` back to true. So the final assertions `idling shouldBe true` and `failures shouldBe 0` do not detect a reconnect, and no assertion counts connections. A mutant such as `await() { Thread.sleep(1000); throw MailConnectionException(...) }` would most likely still pass.
    - Why it matters: This is a much narrower gap than CR-5, because it needs the IDLE connection itself to fail. Still, the test KDoc says "only the server's IDLE response can bring a delivered message in", and the test does not fully guarantee that.
    - Suggested fix: Set the backoff far above the arrival limit for this class (for example `initial-backoff=PT1H`, `max-backoff=PT1H`). The initial connect in `setUp` does not need a retry. Any reconnect would then miss the 10 s limit. Alternatively, assert that the watcher made exactly one connection, for example by checking that GreenMail shows a single IMAP session.

## Review of the new tests

- `MailIdlePushGreenMailTests`: correct and non-vacuous for its main purpose (see CR-5). Test data is fictitious. The account is deleted, and `push.reconcile()` stops its watcher in `tearDown`. `@DirtiesContext` keeps its push-enabled context from leaking into other tests. The `Thread.sleep(500)` after `idling` is a timing allowance. If too short, it can only cause a timeout (a false failure), never a false pass.
- `MailPushPropertiesTests`: the defaults are now read through Spring binding (`ApplicationContextRunner` with `MailPushConfiguration`), so the test checks the real `@DefaultValue` annotations (29m, 5m, 5s, 5m, enabled). The override test checks relaxed-duration binding and the backoff cap. The invalid-settings test relies on the `init` `require` failing binding (initial 10m > max 1m), and `startupFailure` is non-null exactly then.
- `MailPushServiceTests`:
    - "account events before start or after stop start no watcher" asserts `existsById` is never called. That kills a mutant that drops the `running` check in `restart` (`MailPushService.kt:148`).
    - "a watcher that died from an error is restarted by the next poll" uses an `Error` that `InboxWatcher.run` does not catch (`catch (e: Exception)`), so the thread really dies. It then asserts a new instance and exactly two connects. Non-vacuous.
    - "an account without an Inbox ..." checks that the connector is never invoked, that the watcher stays alive in backoff (10 min in this fixture), and that the poll syncs only the Archive without replacing the live watcher. Non-vacuous.

## Notes for the parent

- `tasks.py verify mail-idle` shows no criterion recorded yet. Criterion 3's evidence should name the `MailIdlePushGreenMailTests` run (`integrationTest`, re-IDLE and poll at PT1H), not `MailIdleGreenMailTests`, which re-IDLEs every second.

## Review conclusion

No material issues found.

CR-5 is resolved. CR-6 is accepted as a known limitation with evidence that it cannot occur in this application. CR-7 is an optional hardening of the push test (P3) and does not block completion.
