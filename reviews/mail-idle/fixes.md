# Fix Report

Date: 2026-10-21

Task: mail-idle - IMAP IDLE push and polling

Scope: current task, findings CR-1..CR-4 of `reviews/mail-idle/review.md`, limited to
`server/src/{main,test}/kotlin/ch/sonensei/cockpit/mail/sync`.

Git range:
- Base (merge-base with origin/main): 8dfa49e29096593b9a9e95c2c5f577eab718e2d7
- Task commits: 74e4b88c6, 02d36747f, 0e9f9dfff, df2ba5d67, 5da96d451 (HEAD)
- Fixes for the findings: df2ba5d67 (production code, by the parent worker), 5da96d451 (unit tests, by the parent
  worker), plus uncommitted working-tree changes by this fixer (listed below; the parent commits them)

## Fixed findings

### CR-1 - Reconnect/backoff and the 5-min polling have no committed test (P1)

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/FakeIdleSession.kt` (parent, 5da96d451)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/InboxWatcherTests.kt` (parent, 5da96d451; 2 tests added by this fixer)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailPushPropertiesTests.kt` (parent, 5da96d451)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailPushServiceTests.kt` (parent, 5da96d451)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/NewMailAnnouncerTests.kt` (parent, 5da96d451)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncServiceTests.kt` (parent, 5da96d451)

Problem:
No committed test exercised the retry/backoff, the "no IDLE" path, `poll`, the watcher restart on
`MailAccountConnectionChanged`, or the `NewMailAnnouncer` filters.

Fix:
Checked every path the finding names against the tests committed by the parent in 5da96d451:
- retry after failed connection/sync with doubling backoff, `failures` reset, reconnect after an IDLE failure:
  `InboxWatcherTests` ("a failed connection is retried with a doubling backoff ...", "a failing catch-up sync ...",
  "a connection that fails while idling reconnects ...");
- unsupported ends the watcher: "a server without IDLE ends the watcher as unsupported without syncing";
- stop during await/backoff: "stop closes the connection ...", "stop during the backoff wait ...",
  "a connection that opens after stop ...";
- `MailPushProperties.backoff` doubling and cap: `MailPushPropertiesTests` (9 tests);
- `poll`: other selectable folders synced, Inbox synced (also while idling, after CR-2), Inbox synced when IDLE is
  down or unsupported, a failing account or a vanished folder does not stop the others, poll scheduled on its own
  thread: `MailPushServiceTests` (17 tests);
- restart on `MailAccountConnectionChanged`, created/deleted accounts: `MailPushServiceTests`;
- announcer filters (seen excluded, non-Inbox not notified, at most 10, newest first, per-account switch):
  `NewMailAnnouncerTests` (10 tests).

All named paths are covered. The one gap found was that nothing tested the CR-3 behavior (a hanging close or NOOP
must not block the caller or the shared scheduler); this fixer added two tests for it (see CR-3).

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sync.*' ktlintCheck` - Pass (run by this fixer)

### CR-2 - A silently dropped IDLE connection leaves the Inbox unsynced for about 30 minutes (P2)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailPushService.kt` (parent, df2ba5d67; KDoc rewrap by this fixer)
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailPushProperties.kt` (parent, df2ba5d67)
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/ImapIdleConnector.kt` (parent, df2ba5d67)
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/InboxWatcher.kt` (parent, df2ba5d67)

Problem:
`poll()` skipped the Inbox while `watcher.idling` was true, so a silently dropped IDLE connection was not covered
by the 5-min poll; the connector KDoc overstated dead-connection detection.

Fix (parent, df2ba5d67):
`poll()` now syncs every selectable folder including the Inbox (the `idling` filter is gone), as a CONDSTORE/QRESYNC
safety net. The KDocs of `ImapIdleConnector`, `MailPushProperties.pollInterval`, `InboxWatcher.idling` and
`MailPushService` were corrected. Verified at HEAD: no remaining use of `idling` in production filtering, no
remaining "dead connection is still detected" claim. This fixer only rewrapped one overlong KDoc line in
`MailPushService` (no content change). Covered by `MailPushServiceTests` "poll syncs the folder list and every
selectable folder, the Inbox too while its IDLE runs".

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sync.*' ktlintCheck` - Pass

### CR-3 - Stopping or refreshing a watcher can block for up to the 30-minute IDLE socket timeout (P2)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/InboxWatcher.kt` (parent, df2ba5d67)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/InboxWatcherTests.kt` (this fixer, uncommitted)

Problem:
`InboxWatcher.stop()` closed the session synchronously (on the HTTP request thread under the `MailPushService`
monitor, or during shutdown), and the re-IDLE NOOP ran on the one `mail-re-idle` thread shared by all accounts.

Fix (parent, df2ba5d67):
`stop()` closes the session on a virtual thread and then waits at most `timeout` for the watcher thread; the
scheduled re-IDLE task only starts a virtual thread that runs `refresh()`. Verified at HEAD: the `@Synchronized`
`reconcile()`/`restart()` sections do no IMAP I/O any more (repository reads, thread start, and `stop()` which is
bounded by its 5 s join); the shared scheduler task returns immediately.

Fix (this fixer): added two regression tests to `InboxWatcherTests` with a `HangingIdleSession` fake whose
`await`/`refresh`/`close` block like a half-open socket (bounded at 3 s so a regression fails instead of hanging):
- "stop returns within its timeout when closing a half-open connection hangs";
- "a re-IDLE NOOP that hangs does not block the scheduler shared by all accounts".
Mutation check: with `stop()` temporarily reverted to `session?.close()` the first test failed; with the re-IDLE task
temporarily running `refresh()` inline the second test failed. Production code was restored after each check (the
final diff contains no production change in `InboxWatcher.kt`).

Remaining note (not a defect against the finding): `MailPushService.stop()` stops watchers one after another, so
shutdown with several dead connections waits up to 5 s per such account. This is bounded and was left unchanged.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sync.InboxWatcherTests'` - Pass (13 tests)
- same command with each mutation applied - 1 failure each, as expected

### CR-4 - The mail poll runs on Spring's single shared scheduling thread (P2)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailPushService.kt` (parent, df2ba5d67)

Problem:
`poll()` was `@Scheduled` and shared Spring's default single scheduling thread with reminders, calendar sync and
other jobs.

Fix (parent, df2ba5d67):
`@Scheduled` and `@EnableScheduling` were removed from the mail module; `start()` schedules `poll` with
`scheduleWithFixedDelay` on a dedicated single-thread `mail-poll` executor, which `stop()` shuts down. `poll()` catches
every exception so a failure does not cancel the fixed-delay schedule. Verified at HEAD that removing
`@EnableScheduling` here does not disable other jobs: it is still declared in `ReminderScheduler`, `TrashPurgeJob`,
`GtdSchedulingConfiguration` and `CalendarSyncScheduler`. Covered by `MailPushServiceTests` "start schedules the poll
every poll interval on its own thread" and "an exception outside the per-account handling does not escape the poll".

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sync.*' ktlintCheck` - Pass

## Not fixed

None.

## Changes by this fixer (uncommitted, for the parent to commit and re-test)

- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailPushService.kt`: class KDoc, one overlong line rewrapped;
  no code change.
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/InboxWatcherTests.kt`: two tests and the private
  `HangingIdleSession` fake added (CR-3).

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sync.InboxWatcherTests'` - Pass, 13 tests
- Mutation runs of the same command (stop closing inline; re-IDLE NOOP inline) - each failed exactly the new
  matching test; production code restored

Full test suite:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sync.*' ktlintCheck` - BUILD SUCCESSFUL:
  InboxWatcherTests 13, MailPushServiceTests 17, NewMailAnnouncerTests 10, MailPushPropertiesTests 9,
  MailHeaderSyncServiceTests 27, MailHeaderStoreTests 22, ImapHelpersTests 33, MailThreadingTests 35; 0 failures
- Integration tests (`./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.sync.*'`): not re-run by this
  fixer; the parent reported them passing at 5da96d451 (MailIdleGreenMailTests 4, HeaderSyncGreenMailTests 14,
  HeaderSyncDovecotTests 2). This fixer changed no production code, so they are unaffected.
- Full server `./gradlew check`: not run by this fixer

Lint/type-check/build:
- `cd server && ./gradlew ktlintCheck` (with the run above) - Pass

`.taskcheck`:
- Not run (the parent runs `sh scripts/check.sh` before `done`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed. CR-2, CR-3 and CR-4 were fixed in production code by the parent
in df2ba5d67 and CR-1 by the parent's tests in 5da96d451; this fixer verified them at HEAD, added two regression tests
for CR-3 and rewrapped one KDoc line. Because test code changed, the parent must commit these files and re-run the
review helpers per AGENTS.md.
