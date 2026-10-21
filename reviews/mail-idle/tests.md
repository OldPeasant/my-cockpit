# Test Report

Date: 2026-10-21

Task: mail-idle - IMAP IDLE push and polling

Scope: `git diff 8dfa49e29096593b9a9e95c2c5f577eab718e2d7..c25536adfe381297928f10903b524722b34bac09` (7 task commits:
74e4b88c6, 02d36747f, 0e9f9dfff, df2ba5d67, a6d1fc4c4, 5da96d451, c25536adf) on branch `main`, plus the uncommitted
test changes of this test-writer listed below. Production paths in scope:
`server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/{InboxWatcher,ImapIdleConnector,MailPushProperties,MailPushService,NewMailAnnouncer,MailHeaderSyncService,MailHeaderStore}.kt`.

Requirements:
- `tasks/mail-idle.md` (3 acceptance criteria and 3 recorded assumptions)
- SPEC.md section 7.2 (as summarized in the task, the review and the production KDoc)
- `docs/ARCHITECTURE.md` "Coverage" (JaCoCo, no task-level threshold)
- `reviews/mail-idle/review.md` (CR-1..CR-4) and `reviews/mail-idle/fixes.md`
- Parent instructions (scope, base, test results at c25536adf)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailIdlePushGreenMailTests.kt` (new, tagged `external`)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailPushPropertiesTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailPushServiceTests.kt`

## Tests added or updated

Count: 7 (6 added, 1 replaced)

- `MailIdlePushGreenMailTests` "a delivered message is pushed into the cache within seconds with neither re-IDLE nor
  poll due" (added). Reason: in `MailIdleGreenMailTests` the re-IDLE interval is 1 s and `InboxWatcher` syncs after
  every re-IDLE (`await()` returns after `refresh()`, then `sync()`), so that suite would find a delivered message
  within about a second even if the server's IDLE response never arrived. It does not prove criterion 3 ("without
  polling"). The new class sets both the re-IDLE and the poll interval to one hour, so only the IDLE push can bring the
  message in.
- `MailPushPropertiesTests` "without settings the IDLE is renewed every 29 minutes and every folder is polled every 5
  minutes" (replaces "the defaults keep the re-IDLE below the 30 minutes ..."). The old test asserted the defaults of
  the test's own helper function, not the `@DefaultValue` annotations, so it could not fail. The new test binds the
  properties through `MailPushConfiguration` in an `ApplicationContextRunner` (the pattern of `CalendarSyncUnitTests`)
  and checks the real defaults (29 min re-IDLE, still below 30 min; 5 min poll; 5 s / 5 min backoff; enabled).
- `MailPushPropertiesTests` "bound settings override the defaults" and "an invalid setting fails the startup" (added).
- `MailPushServiceTests` "account events before start or after stop start no watcher" (added; covered the untested
  `restart` guard at `MailPushService.kt:148`).
- `MailPushServiceTests` "a watcher that died from an error is restarted by the next poll" (added; the
  `!alive && !unsupported` restart branch of `reconcile`, `MailPushService.kt:142`, was never taken).
- `MailPushServiceTests` "an account without an Inbox gets no IDLE, its watcher retries, and the poll still syncs its
  other folders" (added; the "has no Inbox" path, `MailPushService.kt:171-176`, was never run).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: IDLE on each account's INBOX | InboxWatcherTests "catches up once connected, then syncs after every change ..."; MailPushServiceTests "start watches the Inbox of every account", "a watcher connects with the cached Inbox path ..."; MailIdleGreenMailTests (4) | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sync.*' integrationTest --tests 'ch.sonensei.cockpit.mail.sync.*'` (parent, c25536adf) | Pass |
| AC1: reconnect/backoff | InboxWatcherTests "a failed connection is retried with a doubling backoff ...", "a failing catch-up sync ...", "a connection that fails while idling reconnects ...", "stop during the backoff wait ..."; MailPushPropertiesTests backoff tests (6) | same (parent, c25536adf) | Pass |
| AC1: reconnect after a crashed watcher, account without Inbox | MailPushServiceTests "a watcher that died from an error is restarted by the next poll", "an account without an Inbox gets no IDLE, ..." | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sync.MailPushServiceTests'` | Not run: written by this test-writer, Gradle is denied to sub-agents; the parent must run it |
| AC1: 29-min re-IDLE | InboxWatcherTests "the IDLE is renewed every re-IDLE interval ...", "the re-IDLE is scheduled at the configured interval ...", "a re-IDLE NOOP that hangs does not block ..."; MailIdleGreenMailTests "mail keeps arriving across re-IDLEs" | parent run at c25536adf | Pass |
| AC1: 29-min / 5-min defaults as bound by Spring | MailPushPropertiesTests "without settings the IDLE is renewed every 29 minutes ...", "bound settings override the defaults", "an invalid setting fails the startup" | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sync.MailPushPropertiesTests'` | Not run: Gradle denied to sub-agents; parent must run |
| AC1: other folders polled every 5 min (Inbox too, own thread) | MailPushServiceTests "poll syncs the folder list and every selectable folder ...", "start schedules the poll every poll interval on its own thread", "poll syncs the Inbox of an account whose server has no IDLE", "poll syncs the Inbox while the IDLE connection is down", "a failing account does not stop ...", "a folder that is gone ...", "an exception outside the per-account handling ..." | parent run at c25536adf | Pass |
| AC1: watchers follow accounts (create/update/delete) | MailPushServiceTests "a created account gets its watcher right away", "changed connection settings restart ...", "a deleted account's watcher is stopped ...", "reconcile ..." (2); MailIdleGreenMailTests "deleting the account stops its watcher"; new "account events before start or after stop ..." | parent run (existing); new test not run | Pass (existing) / Not run (new) |
| AC2: new mail -> ChangeEvent | NewMailAnnouncerTests "every arrival gets an EMAIL created change event", "seen arrivals and rows of other folders ..."; MailHeaderSyncServiceTests "an incremental sync reports the rows created ...", "... only reattached known rows announces nothing", "... without new UIDs announces nothing", "a first or full sync never announces ..."; MailHeaderStoreTests (createdIds); MailIdleGreenMailTests first test | parent run at c25536adf | Pass |
| AC2: optional notification event (per-account setting) | NewMailAnnouncerTests (unread newest first, at most 10, seen excluded, non-Inbox excluded, `notifyNewMail=false`, all seen, no sender, tie order); MailIdleGreenMailTests "an account that does not notify gets change events but no notification" | parent run at c25536adf | Pass |
| AC3: `external` GreenMail test, delivered message appears within seconds without polling | MailIdleGreenMailTests "a delivered message appears within seconds without polling ..." (poll 1 h, but re-IDLE sync every 1 s, see above); MailIdlePushGreenMailTests "a delivered message is pushed into the cache within seconds with neither re-IDLE nor poll due" | `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.sync.MailIdle*'` | Existing: Pass (parent, c25536adf). New: Not run: Gradle denied to sub-agents; parent must run it, it is the stronger evidence for AC3 |
| CR-3: stop/re-IDLE bounded on half-open connections | InboxWatcherTests "stop returns within its timeout ...", "a re-IDLE NOOP that hangs does not block the scheduler ..." | parent run at c25536adf | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sync.*' jacocoTestReport integrationTest --tests 'ch.sonensei.cockpit.mail.sync.*' ktlintCheck`
(run by the parent at c25536adf, before this test-writer's additions)

Result:

Pass. Unit: InboxWatcherTests 13, MailPushServiceTests 17, NewMailAnnouncerTests 10, MailPushPropertiesTests 9,
MailHeaderSyncServiceTests 27, MailHeaderStoreTests 22, ImapHelpersTests 33, MailThreadingTests 35; integration:
MailIdleGreenMailTests 4, HeaderSyncGreenMailTests 14, HeaderSyncDovecotTests 2; 0 failures; ktlint clean.

The tests added by this test-writer were not run: every Gradle invocation is denied to this sub-agent (the permission
system refused `./gradlew`). The parent must run, at the final revision:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sync.*' jacocoTestReport integrationTest --tests 'ch.sonensei.cockpit.mail.sync.*' ktlintCheck`

Expected: MailPushServiceTests 20, MailPushPropertiesTests 11, MailIdlePushGreenMailTests 1 (plus the unchanged counts
above), 0 failures.

## Full test execution

Command:

`sh scripts/check.sh` / `cd server && ./gradlew check`

Result:

Not run: Gradle is denied to this sub-agent. The parent runs `.taskcheck` before `done`.

## .taskcheck

Result:

Not run (Gradle denied to this sub-agent; the parent runs `sh scripts/check.sh`).

## Bugs found

No production defect was found by reading the code against the criteria. One evidence weakness (not a production
defect) is worth recording:

### AC3 evidence did not isolate the IDLE push

Finding ID:

`TW-1`

Test:

`MailIdleGreenMailTests` "a delivered message appears within seconds without polling, with a change event and a notification"

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/InboxWatcher.kt:106-108` (a sync follows every `await()`, also
one ended by the re-IDLE `refresh()`)

Requirement:

AC3: a delivered message appears within seconds without polling.

Expected behavior:

The evidence shows the message arrives through the server's IDLE response alone.

Actual behavior:

The suite runs with `re-idle-interval=PT1S`, so the Inbox is synced every second regardless of IDLE responses; the test
would pass even if IDLE push did not work. Added `MailIdlePushGreenMailTests` (re-IDLE and poll 1 h) to close the gap.
If it fails, IDLE push does not work with GreenMail and AC3 is not met (either a production problem or a GreenMail IDLE
limitation that must be investigated, not worked around by shortening the re-IDLE).

Severity:

Medium

Fix required:

No production change known to be required. The parent must run `MailIdlePushGreenMailTests` and record it as the AC3
evidence; if it fails, the failure must go to the fixer.

## Coverage

Status: partial - fresh JaCoCo output exists for the task's production code at c25536adf (before this test-writer's
additions); it is unit tests only, and no run after the additions was possible (Gradle denied to this sub-agent).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-idle; base
  8dfa49e29096593b9a9e95c2c5f577eab718e2d7 (merge-base with origin/main, from the parent); measured HEAD
  c25536adfe381297928f10903b524722b34bac09; file scope from the parent, checked against
  `git diff --stat 8dfa49e29..HEAD` (the 7 production files are the only production changes in the range).
- Source/test snapshot identifier (including uncommitted inputs): c25536adf with a clean working tree for the
  measured run. Uncommitted after the run (not measured): `MailIdlePushGreenMailTests.kt` sha256 71ffaed3...c06bf,
  `MailPushPropertiesTests.kt` sha256 b8a9551c...9cdc2, `MailPushServiceTests.kt` sha256 6e616950...fbf17.
  `reviews/mail-idle/review.md` also shows as modified in the working tree; not changed by this test-writer.
- Exact included files; renamed/deleted/excluded files and reasons: all 7 files above included and measured; none
  renamed, deleted or excluded. Test files, `reviews/`, and `server/src/test/resources/config/application.properties`
  (test configuration) are not production code.
- Provider/version, command, working directory, and test selection: JaCoCo (version from the project's Gradle
  `jacoco` plugin); `./gradlew test --tests 'ch.sonensei.cockpit.mail.sync.*' jacocoTestReport ...` in `server/`, run
  by the parent; the selection is the mail.sync unit tests only. `integrationTest` (GreenMail/Dovecot) is not part of
  this report.
- Run start/completion time and exit status: JaCoCo session `dell26-a02b5461`, start 2026-10-21 22:27:10 +0200, dump
  22:27:35 +0200; report written 22:28:36 +0200; exit status 0 (as reported by the parent).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`,
  sha256 6b6516e2e265fb03ca6945098b032f36be776ed8df6ed5d647c3a4d25bda772b, single session `dell26-a02b5461`.
- Evidence outputs are fresh and inputs stayed unchanged: the session started before the HEAD commit was recorded
  (22:28:30) but the commit contains the fixer's working-tree changes that were in place during the run, and the
  parent reports the run "at c25536adf"; the report has one session only.
- Final run after latest test additions and production fixes: no - this test-writer added tests afterwards and could
  not run Gradle. The numbers below describe c25536adf without the new tests; the parent must re-run
  `jacocoTestReport` for final numbers. Expected change: `MailPushService.kt` branches 142, 148 and 171 become
  covered.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" are
JaCoCo bytecode instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/InboxWatcher.kt` | 295/298 (99.0%) | 17/18 (94.4%) | 13/14 (92.9%) | 66/66 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailPushService.kt` | 424/441 (96.1%) | 26/32 (81.3%) | 17/17 (100%) | 89/89 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/NewMailAnnouncer.kt` | 157/169 (92.9%) | 14/14 (100%) | 8/12 (66.7%) | 34/36 (94.4%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailPushProperties.kt` | 132/141 (93.6%) | 18/18 (100%) | 4/7 (57.1%) | 20/20 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/ImapIdleConnector.kt` | 20/183 (10.9%) | 0/4 (0%) | 3/10 (30.0%) | 7/52 (13.5%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt` | 971/976 (99.5%) | 69/70 (98.6%) | 27/28 (96.4%) | 126/126 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderStore.kt` | 738/751 (98.3%) | 50/54 (92.6%) | 25/25 (100%) | 123/123 (100%) |
| Aggregate (summed counters, all 7 files measured, none excluded) | 2737/2959 (92.5%) | 194/210 (92.4%) | 97/113 (85.8%) | 465/512 (90.8%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ImapIdleConnector.kt:67-130` (all of `open`, `ImapIdleSession`, `close`) | The real IMAP IDLE connection (AC1, AC3) | Not reachable without a server; the happy path (connect, IDLE, NOOP refresh, close) runs in `MailIdleGreenMailTests` and the new `MailIdlePushGreenMailTests` (integrationTest, not in this JaCoCo report). Gap left: the error mappings (no IDLE capability -> `IdleNotSupportedException`, `FolderNotFoundException` -> `RemoteFolderNotFoundException`, `MessagingException` -> `MailConnectionException`) have no test at all; the watcher treats every exception as a retryable failure except `IdleNotSupportedException`, so only the "server without IDLE" mapping changes behavior. Testing it needs an IMAP server without IDLE; follow-up optional. |
| `MailPushService.kt:142` (`!alive && !unsupported` restart) | Reconnect: a watcher whose thread died is restarted | Added "a watcher that died from an error is restarted by the next poll" |
| `MailPushService.kt:148` (`restart` while not running) | Account events before start / after shutdown | Added "account events before start or after stop start no watcher" |
| `MailPushService.kt:171-176` (Inbox missing after a folder sync) | Account without Inbox must not break push or poll | Added "an account without an Inbox gets no IDLE, ..." |
| `MailPushService.kt:167` (account deleted while its watcher connects) | Race with deletion; the watcher retries and `reconcile` stops it | Left: timing-only path, the outcome (retry, then stop) is covered by the backoff and reconcile tests |
| `MailPushService.kt:81, 139` (null branch of `remove(...)?.stop()`) | Concurrent removal | Left: defensive null-safety, not reachable under the `@Synchronized` sections |
| `InboxWatcher.kt:55` (`stop()` called from the watcher's own thread) | Self-join guard | Left: defensive, nothing calls `stop()` from the watcher thread |
| `NewMailAnnouncer.kt:25-26, 33, 35` (property getters of the payload data classes) | Serialized by Jackson for SSE | Left: the tests compare whole data-class instances; serialization of `ClientNotification` belongs to the events module |
| `MailPushProperties.kt:14, 23, 25` (getters `enabled`, `initialBackoff`, `maxBackoff`) | Configuration binding | New binding tests read them (not yet measured) |
| `MailHeaderSyncService.kt:225` (`folder.uidNext ?: 1`), `:37` | Pre-existing logic and a getter, outside the task's changed lines | Left: not task behavior |
| `MailHeaderStore.kt:84, 228` (partial branches) | Pre-existing code outside the task's hunks (task changed lines 14-22, 51, 67, 70) | Left: not task behavior |

## Unable to test

- Running any test: Gradle is denied to this sub-agent. All new tests (6 unit, 1 external) need a run by the parent;
  until then they are written but not evidence.
- `ImapIdleConnector` against a server without IDLE (`IdleNotSupportedException`): neither GreenMail nor the Dovecot
  setup lacks IDLE; would need a dedicated fake IMAP server. Low risk (one `hasCapability` check), optional follow-up.
- Silent connection drop (CR-2) end to end: needs network fault injection; the mitigation (the poll syncs the Inbox
  too) is covered by `MailPushServiceTests` "poll syncs the folder list and every selectable folder, the Inbox too
  while its IDLE runs".

## Summary

Tests added; no production defects found by inspection, but the added tests (including the stronger AC3 evidence
`MailIdlePushGreenMailTests`) could not be run here because Gradle is denied to this sub-agent. The parent must run
the focused unit and integration commands above, record the results, and re-run `jacocoTestReport` for final coverage;
a failure of `MailIdlePushGreenMailTests` would mean AC3 is not demonstrated and must go to the fixer.
