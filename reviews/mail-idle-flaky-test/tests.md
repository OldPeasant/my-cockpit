# Test Report

Date: 2026-10-25

Task: mail-idle-flaky-test - MailIdleGreenMailTests: wait for the change event (flaky in CI)

Scope: task commit 22e4f014c7367d45fb518d08fb48427ce0669cd6 on top of base 677f9840bfc96e617bed01677314dc4e48ffb17d
(`git diff --stat 677f9840b..HEAD`: only `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailIdleGreenMailTests.kt`,
10 insertions, 3 deletions). Working tree clean, no uncommitted inputs.

Requirements:
- `tasks/mail-idle-flaky-test.md` acceptance criteria
- Parent's root-cause note (event published after the message is stored)
- `AGENTS.md`, `docs/ARCHITECTURE.md` "Coverage"

## Test files changed

- None by the test-writer. The task commit's change to
  `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailIdleGreenMailTests.kt` was reviewed and judged sufficient.

## Tests added or updated

Count: 0 (by the test-writer; the task commit updated 2 tests)

## Root cause check

Confirmed in production code (read only, not modified):
- `MailHeaderSyncService.syncFolder` (`server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt:106-120`)
  stores the headers inside `mailbox.withFolder { full/incremental }`, and only then calls `announce(result)` and
  `announcer.announce(account, folder, result.arrived)`.
- `NewMailAnnouncer.announce` (`NewMailAnnouncer.kt:53-70`) publishes one `EMAIL` CREATED change event per arrived id,
  then (Inbox and `notifyNewMail` only) the `ClientNotification`.
- So the test thread can see the row via `inboxMessage(...)` before the watcher thread publishes the change event. The
  old assertion at line 177 (`... shouldHaveSize 1` right after the message appeared) and the old `recorder.changes.first { ... }`
  in the first test (would throw `NoSuchElementException`) both had this race. The parent's diagnosis matches the code.
- Two events for one message are not expected: `syncFolder` is serialized per folder by `lock(folderId)`, and a later
  sync sees the message cached, so `arrived` does not contain it again. The `shouldHaveSize 1` after the settle still
  checks this.

## Review of the other assertions in the file (same race?)

| Assertion | Race? | Reason |
|---|---|---|
| Test 1 `eventually { emailChanges(message).isNotEmpty() }` + `shouldContain ChangeEvent(...)` | Fixed | Waits for the event before reading `.first().at`. |
| Test 1 `eventually { notifications.isNotEmpty() }` then `notifications.single()` | No | Notification is published once, on the same thread, after the change events; only a further arrival could add a second one. |
| Test 1 `(elapsed < ARRIVAL_LIMIT) shouldBe true` | No | Measured up to the cache visibility; the 10 s limit is wide (and `eventually` itself times out at 10 s). |
| Test 2 `push.watcher(account.id)?.idling shouldBe true` after `Thread.sleep(2_500)` | No | `InboxWatcher.idling` (`InboxWatcher.kt:105-111`) stays `true` across re-IDLEs and syncs inside `watch()`; it only becomes false when the connection fails or the watcher stops, which is exactly what the test must catch. |
| Test 3 `accounts.update(notifyNewMail=false)` then `recorder.changes.clear()` | No | A `notifyNewMail`-only update does not publish `MailAccountConnectionChanged` (`MailAccountService.kt:192`), so the watcher is not restarted; the `syncFolder` call reads the account fresh, so the new setting applies. |
| Test 3 `eventually { emailChanges(message).isNotEmpty() }`, `sleep(300)`, `shouldHaveSize 1` | Fixed | Matches criterion 1. |
| Test 3 `notifications.shouldBeEmpty()` after the settle | No | A notification would be published synchronously right after the change event on the same thread, well within 300 ms. |
| Test 4 `push.watcher(account.id) shouldBe null` right after `accounts.delete` | No | `onAccountChanged` is `AFTER_COMMIT` with `fallbackExecution = true`; the test has no surrounding transaction, so it runs synchronously within `delete`. Unchanged by this task and not reported flaky. |
| `setUp` `eventually { idling }` then `clear()` | No | The catch-up sync runs before `idling = true` (`InboxWatcher.kt:103-105`), so its events are cleared; later re-IDLE syncs only add `MAIL_FOLDER` events, which `emailChanges` filters out. |

Note (not a flakiness issue, no change made): with `re-idle-interval=PT1S`, the 300 ms settle in test 3 does not span
a full re-IDLE sync, so a hypothetical duplicate CREATED from the next sync might not be caught. This is detection
sensitivity, not a race that can make the test fail spuriously; the criterion asks for "a short settle, like the
notification check", which the change does.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: test waits for the change event (`eventually { ... }`) before asserting exactly one after a short settle; cause found | `an account that does not notify gets change events but no notification` (and same race fixed in `a delivered message appears within seconds without polling, with a change event and a notification`) | Code review of the diff against `MailHeaderSyncService.kt:106-120` and `NewMailAnnouncer.kt:53-70` | Pass (by inspection; cause confirmed as described above) |
| AC2: `cd server && ./gradlew integrationTest --tests '*MailIdleGreenMailTests' --rerun` passes 10 times in a row | all 4 tests in `MailIdleGreenMailTests` | `cd server && ./gradlew integrationTest --tests '*MailIdleGreenMailTests' --rerun` x10 | Not run: sub-agents cannot run Gradle in this environment; the parent runs it and records the result |

## Focused test execution

Command:

`cd server && ./gradlew integrationTest --tests '*MailIdleGreenMailTests' --rerun` (x10)

Result:

Not run by the test-writer (Gradle not available to sub-agents); run by the parent worker.

## Full test execution

Command:

`sh scripts/check.sh`

Result:

Not run by the test-writer (Gradle not available to sub-agents); the parent runs `.taskcheck`.

## .taskcheck

Result:

Not run (see above; parent runs it before `done`).

## Bugs found

None.

## Coverage

Status: not applicable - the task changes no production code (only the test file
`server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailIdleGreenMailTests.kt`), so the task coverage scope is empty.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no production lines changed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-idle-flaky-test; base
  677f9840bfc96e617bed01677314dc4e48ffb17d; HEAD 22e4f014c7367d45fb518d08fb48427ce0669cd6; task commit 22e4f014c
  supplied by the parent and confirmed with `git diff --stat 677f9840b..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 22e4f014c, clean working tree; sha256 of the
  test file `d4a7421a1e11756df04557e9e75edb1f873565ed71fc2c376725de4ff9ad3367`.
- Exact included files; renamed/deleted/excluded files and reasons: no production files in scope; the only changed file
  is a test file (excluded from coverage by definition).
- Provider/version, command, working directory, and test selection: none run (empty scope; Gradle unavailable to sub-agents).
- Run start/completion time and exit status: N/A.
- Generated report paths and report hashes/run identifier: N/A.
- Evidence outputs are fresh and inputs stayed unchanged: N/A; no coverage numbers are reported.
- Final run after latest test additions and production fixes: N/A (no coverage run; no test-writer changes).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production files changed) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| N/A | N/A | Empty task coverage scope |

## Unable to test

- AC2 (10 consecutive green runs of the integration test): needs Gradle, GreenMail and Testcontainers Postgres, which
  sub-agents cannot run here. The parent worker runs it and records the result; no follow-up beyond that.

## Summary

No additional tests were necessary for the reviewed scope. The task commit fixes the race in both affected
assertions, the root cause is confirmed in `MailHeaderSyncService.syncFolder` / `NewMailAnnouncer.announce`, and no
other assertion in the file has the same race. The test file was not changed by the test-writer, so the parent's
10-run result on 22e4f014c applies as-is.
