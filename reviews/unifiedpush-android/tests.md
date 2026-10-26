# Test Report

Date: 2026-10-27

Task: unifiedpush-android - UnifiedPush for Android

Scope: `git diff origin/main HEAD`, task base `6c501511638ac9750976e9d8b9aca232231f8116` (origin/main = merge-base of HEAD), measured HEAD `419fb4a188b0e700d4e9ef9366b172767f3ac2c1` (branch main, clean working tree). Task-owned commits: `git log origin/main..HEAD` = 0827bb996, e1dd3cb93, f3e5e1e6c, f29a214cc, b65cc62e3, 9bf2f18a1, c7d8f8bf5, 53cdfe6d4 (merge of origin/wip/unifiedpush-android), 419fb4a18 (fix of review finding CR-2: a 5xx answer is OFFLINE, so the worker retries). The earlier subtasks (unifiedpush-server, unifiedpush-android-client) are already on main and are not part of this diff. This is the second test-writer round, on the final code after the CR-2 fix.

Requirements:
- `tasks/unifiedpush-android.md` acceptance criteria and recorded assumptions (no device/ntfy in the agent environment; reminder push loads the reminder by id and shows it via `ReminderScheduler.showFiredByServer`)
- `SPEC.md`, `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`
- Prior reports: `reviews/unifiedpush-android/review.md` (finding CR-2), `reviews/unifiedpush-android/fixes.md` (round 2)

## Test files changed

- None by this test-writer run. The task's test changes (committed in the scope) are:
  `android/app/src/test/java/ch/sonensei/cockpit/push/PushAndroidAdaptersTest.kt`,
  `PushInAppTest.kt`, `PushMessageHandlerTest.kt`, `PushRegistrationKeysContractTest.kt`,
  `PushReminderDeliveryTest.kt`, `PushServerContractTest.kt`, and
  `android/app/src/test/java/ch/sonensei/cockpit/reminders/ReminderShowFiredByServerTest.kt`.

## Tests added or updated

Count: 0

Reason: the CR-2 fix (one new line, `PushReminderDelivery.kt:55`, `if (response.code() >= 500) return OFFLINE`) is already tested well by the fixer's tests in commit 419fb4a18, and both branches of that line are covered:
- 5xx is OFFLINE: `a server error answer (5xx) is offline and the worker retries` uses 503 and 500 with Problem Details bodies and 502 with an empty body (the proxy case), at the boundary value 500; nothing is shown or marked notified.
- Worker retry mapping on 5xx: the same test asserts `ListenableWorker.Result.retry()` from `doWork()`.
- Recovery: `after a 5xx, the retried delivery shows the reminder` (503, then a fired answer is SHOWN once).
- 4xx stays IGNORED: `a client error answer or an unreadable body is ignored` (403, renamed from the former 500 case), `a deleted reminder (404) is ignored`; worker mapping for an ignored 4xx: `the worker does not retry a push it ignores` (404 gives `Result.success()`).
- Unreachable server stays OFFLINE and retried: `an unreachable server is offline and the worker retries`.

I found no real gap. A 499 boundary test was considered and not added: the threshold 500 itself is tested and 499 is not a status the server or a proxy sends.

## Requirement coverage

Command A (run by the parent worker at 2026-10-26T23:07Z, results read by me from `android/app/build/test-results/testDebugUnitTest/*.xml`):
`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*' --tests 'ch.sonensei.cockpit.reminders.ReminderShowFiredByServerTest' createDebugUnitTestCoverageReport ktlintCheck`
Result: BUILD SUCCESSFUL, 108 tests, 0 failures, 0 errors, 0 skipped (LibraryUnifiedPushConnectorTest 7, PushInAppTest 17, PushAndroidAdaptersTest 12, PushRegistrarTest 28, PushMessageHandlerTest 12, PushRegistrationKeysContractTest 1, PushReminderDeliveryTest 22, PushServerContractTest 2, ReminderShowFiredByServerTest 7); ktlintCheck passed.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: app registers with UnifiedPush (distributor selection, register at login/start, same instance reused, unregister and server delete at logout or distributor drop) | PushRegistrarTest (e.g. `registers with the distributor at app start when logged in`, `an app start within the same login registers the stored instance again, not a new one`, `logout deletes the endpoint on the server, then unregisters at the distributor`, `the distributor dropping the registration deletes it on the server`); LibraryUnifiedPushConnectorTest (`the only installed distributor is selected and remembered`, `the system default distributor is taken over the first installed one`); PushInAppTest (`the registrar of the app graph registers at start while logged in`, `a login through the login screen's repository registers with the distributor`, `the push service deletes the endpoint when the distributor unregisters`); PushRegistrationKeysContractTest; PushAndroidAdaptersTest (`the instance fits the server's instance id pattern of the push registration path`) | Command A | Pass |
| AC1: reminder push handled (shows that reminder and runs the reminder sync) | PushMessageHandlerTest `a reminder push shows that reminder and runs the reminder sync`; PushInAppTest `a fired reminder this device never synced is loaded by id and shown`, `a tap on the pushed reminder opens its item, and the notification shows the reminder's time`; PushAndroidAdaptersTest `the trigger enqueues one delivery per reminder that waits for the network`, `a reminder push enqueues one reminder sync that waits for the network` | Command A | Pass |
| AC1: pushed reminder delivery edge cases (logged out, logout during request, 404, 4xx, unreadable/empty body, trashed item, not fired, id mismatch, offline retry, cancellation, shown at most once vs. local alarm, worker input) | PushReminderDeliveryTest (22 tests, e.g. `a logout while the request runs does not show the reminder`, `a deleted reminder (404) is ignored`, `a client error answer or an unreadable body is ignored`, `an unreachable server is offline and the worker retries`, `a cancelled delivery is cancelled, not reported as ignored`, `a worker without a reminder id fails without a request`, `the worker does not retry a push it ignores`); ReminderShowFiredByServerTest (7 tests, e.g. `it is shown once, also when pushed twice`, `when no longer valid (logged out meanwhile) nothing is shown or marked`) | Command A | Pass |
| CR-2 fix: a 5xx answer (server restarting behind a proxy) is retried, not dropped | PushReminderDeliveryTest `a server error answer (5xx) is offline and the worker retries`, `after a 5xx, the retried delivery shows the reminder`; 4xx still final: `a client error answer or an unreadable body is ignored`, `the worker does not retry a push it ignores` | Command A | Pass |
| AC1: new-mail push shows the new-mail notification (no mail contents, one notification, permission handling) | PushMessageHandlerTest `a new-mail push shows the new-mail notification`; PushAndroidAdaptersTest `a new-mail notification is posted on channel mail, without mail contents, and opens the app`, `every new-mail push updates the one notification instead of adding another`, `without the notification permission no notification is shown`, `before Android 13 the notification needs no runtime permission` | Command A | Pass |
| AC1: message handling security (undecrypted, other registration, logged out, unknown, garbled, non-UTF-8, oversized, size boundary) | PushMessageHandlerTest (`a message the library could not decrypt is ignored`, `a message for another registration is ignored`, `a message after the logout is ignored`, `unknown or garbled messages are ignored`, `bytes that are not UTF-8 are ignored`, `a message of exactly the maximum size is still read, one byte more is ignored`, `a deeply nested message within the size limit is ignored without crashing the push service`) | Command A | Pass |
| AC1: app understands the server's payload format | PushServerContractTest `every kind the server sends is understood`, `the largest payload the server can send fits the app's size limit` | Command A | Pass |
| AC1: fallback to periodic sync when there is no distributor | PushRegistrarTest `no distributor installed means no registration, only the periodic sync`; LibraryUnifiedPushConnectorTest `without a distributor app nothing is selected, so the app does not register`. The periodic reminder sync itself is tested in reminders (ReminderSyncSchedulerTest), not re-run here | Command A | Pass |
| AC1: server sends reminder/new-mail pushes (server side) | Server tests of task unifiedpush-server (WireMock distributor, RFC 8291) | none by me | Not run: outside this task's Android diff and my scope; the parent records server evidence per the task assumption |
| AC1: end-to-end on a real device with an ntfy distributor | none | none | Not run: no device, emulator or ntfy in the agent environment (recorded task assumption) |
| AC2: `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*'` passes, covering message handling | all push.* classes listed above (101 tests) | Command A (superset of the AC2 command) | Pass |
| AC2: Robolectric/Compose UI tests for screens | PushInAppTest (Robolectric + Hilt), PushAndroidAdaptersTest (Robolectric) | Command A | Pass; the push feature has no screens, so no Compose UI test applies |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*' --tests 'ch.sonensei.cockpit.reminders.ReminderShowFiredByServerTest' createDebugUnitTestCoverageReport ktlintCheck` (run by the parent worker; Gradle is not available to this sub-agent)

Result:

Pass (108 tests, 0 failures, 0 errors, per the JUnit XML files written at 2026-10-27 00:07:06 +0100, after commit 419fb4a18 at 00:06:48 +0100)

## Full test execution

Command:

`sh scripts/check.sh` (`.taskcheck`, includes the Android unit tests)

Result:

Not run by me (Gradle not available to this sub-agent); the parent worker runs it before `done`.

## .taskcheck

Result:

Not run by me; present (`scripts/check.sh`), to be run by the parent worker.

## Bugs found

None.

## Coverage

Status: verified (task-scoped, from the fresh JaCoCo report of Command A on HEAD 419fb4a18).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 62/62 (100%) executable changed lines covered, 0 changed lines with missed branches (including the new 5xx line `PushReminderDelivery.kt:55`, both branches covered). Method: added/modified lines from `git diff -U0 origin/main HEAD -- {file}` intersected with JaCoCo `line` elements (`ci` greater than 0 = covered; `mb` checked for partial branches). Removed and non-executable lines are not in the denominator. Changed-branch coverage is not inferred from this beyond the `mb` check.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: unifiedpush-android; base `6c501511638ac9750976e9d8b9aca232231f8116`; HEAD `419fb4a188b0e700d4e9ef9366b172767f3ac2c1`; scope from `git log origin/main..HEAD` and `git diff --name-only origin/main HEAD -- android/app/src/main`, matching the scope given by the parent.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `419fb4a188b0e700d4e9ef9366b172767f3ac2c1`, `git status --short` empty while measuring (no uncommitted inputs; no tests added by me).
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/push/CockpitPushService.kt`, `push/NewMailNotifier.kt`, `push/PushMessageHandler.kt`, `push/PushReminderDelivery.kt` (new), `android/app/src/main/java/ch/sonensei/cockpit/reminders/ReminderScheduler.kt`. No renamed or deleted production files; none excluded; all 5 present in the report.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (`createDebugUnitTestCoverageReport`); Command A in `android/`; selection `ch.sonensei.cockpit.push.*` plus `ch.sonensei.cockpit.reminders.ReminderShowFiredByServerTest`. Computed with `.tmp/tw-scripts/upa_cov.py` (reads provider counters, sums them).
- Run start/completion time and exit status: parent run at 2026-10-26T23:06-23:07Z (first suite timestamp 23:06:53Z, last 23:07:06Z), report written 2026-10-27 00:07:08 +0100; BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 `346c8cbe9bc40522e48576a09233c09745b9649e19c6f647dafb07b4d3209ae7`.
- Evidence outputs are fresh and inputs stayed unchanged: report and JUnit XML were written after commit 419fb4a18 (00:06:48 +0100); PushReminderDeliveryTest shows 22 tests (20 before the fix), and `PushReminderDelivery.kt` now has 45 lines (44 before), confirming the report includes the fix. Tree clean and HEAD unchanged while I read them.
- Final run after latest test additions and production fixes: yes (CR-2 fix 419fb4a18 is HEAD; no tests added by me).

Values below use covered/total (percentage). Statements = JaCoCo INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `push/CockpitPushService.kt` | 122/125 (97.6%) | 4/4 (100%) | 14/15 (93.3%) | 14/15 (93.3%) |
| `push/NewMailNotifier.kt` | 194/195 (99.5%) | 6/6 (100%) | 8/8 (100%) | 48/49 (98.0%) |
| `push/PushMessageHandler.kt` | 168/180 (93.3%) | 20/20 (100%) | 10/12 (83.3%) | 35/37 (94.6%) |
| `push/PushReminderDelivery.kt` | 221/221 (100%) | 20/20 (100%) | 9/9 (100%) | 45/45 (100%) |
| `reminders/ReminderScheduler.kt` | 276/301 (91.7%) | 17/20 (85.0%) | 12/14 (85.7%) | 47/51 (92.2%) |
| Aggregate (summed counters, all 5 files measured, none missing or excluded) | 981/1022 (96.0%) | 67/70 (95.7%) | 53/58 (91.4%) | 189/197 (95.9%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `push/CockpitPushService.kt:61` (`PushBindings` constructor) | Hilt module class instantiation, not task behavior | Non-material: Hilt binding glue; the bindings are exercised through the Hilt graph in PushInAppTest |
| `push/NewMailNotifier.kt:68` (catch of `SecurityException` around `notify`) | Permission revoked between check and call | Non-material defensive path, pre-existing, not changed by this task; not reliably reachable under Robolectric |
| `push/PushMessageHandler.kt:26-27` (generated `PushPayload` serialization constructor) | None: kotlinx serialization plugin output | Non-material; pre-existing lines |
| `reminders/ReminderScheduler.kt:40`, `:44`, `:65` (partial branches in `apply`/`rescheduleAll`), `:102-104` (`clear` loop body), `:111` (`syncUntil`) | Pre-existing reminder sync, logout and reschedule behavior of task and-reminders | Not changed by this task; only ReminderShowFiredByServerTest of the reminders package ran in Command A. Covered by ReminderSchedulerTest, ReminderSyncTest and ReminderLogoutTest, which `.taskcheck` runs |

## Unable to test

- End-to-end push through a real ntfy distributor on a device: no device, emulator or ntfy here (task assumption). Needs a manual owner run; not a blocker per the recorded assumption.
- Server sending of reminder/new-mail pushes: server-side behavior from task unifiedpush-server, outside this Android diff; the parent should cite those server tests as evidence for AC1.
- I could not run Gradle myself; all results are from the parent's Command A run, read from its JUnit and JaCoCo XML outputs.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. No tests were added: the CR-2 fix is covered by the fixer's tests (5xx with 500/502/503 is OFFLINE and the worker retries, a later delivery shows the reminder, 403/404 stay IGNORED and the worker does not retry them), all 62 changed executable lines are covered with no missed branch, and the remaining uncovered lines in the changed files are pre-existing or non-material.
