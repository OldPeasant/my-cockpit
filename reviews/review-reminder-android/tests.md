# Test Report

Date: 2026-11-09

Task: review-reminder-android - Weekly review reminder opens the review on Android

Scope: `git diff 81c26c97b3139ac4e6b0ce7a3985d4f99cc388ca..bde783c827ba7861e5674b5a0aff066b07e7f1cf` (base supplied by the parent worker; one task commit `bde783c82`), plus uncommitted test-only changes by the test-writer listed below. Branch `main`, HEAD `bde783c827ba7861e5674b5a0aff066b07e7f1cf`.

Requirements:
- `tasks/review-reminder-android.md` acceptance criteria
- `AGENTS.md`, `docs/ARCHITECTURE.md` ("Coverage")
- Parent instruction: also consider the UnifiedPush path (`push/PushReminderDelivery.kt`)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/push/PushReminderDeliveryTest.kt` (sha256 `0cfa3fce656ec3205de48f23014f8cc38861d7460700bf8e26c463fe9092805c`)
- `android/app/src/test/java/ch/sonensei/cockpit/reminders/ReminderAlarmsNotifierTest.kt` (sha256 `db4fdb4862b233629a66e9bbbb30a2dc10234d3874b6246c281d72e3a29516cb`)

## Tests added or updated

Count: 4 added (on top of the 2 tests the task commit added/updated)

- `PushReminderDeliveryTest`: `a pushed weekly review reminder without a title is called Weekly review and a tap opens the review` (UnifiedPush path end to end: server JSON with `type: WEEKLY_REVIEW` and no title -> notification title "Weekly review", content intent carries `WEEKLY_REVIEW` and resolves to `CockpitDestination.Review`).
- `PushReminderDeliveryTest`: `a pushed weekly review reminder with a title keeps the server's title` (fallback only applies when the title is missing).
- `ReminderAlarmsNotifierTest`: `in German a weekly review reminder without a title is called Wochenrückblick` (`@Config(qualifiers = "de")`, covers the German string of criterion 2 at runtime).
- `ReminderAlarmsNotifierTest`: `only the weekly review gets its own fallback title, other untitled items stay untitled` (`untitledTitle` for TASK, NOTE, HABIT, lowercase `weekly_review`, empty string).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Navigation maps WEEKLY_REVIEW to the review destination (unit test) | `ReminderUiTest` destination table (task commit); `ReminderAlarmsNotifierTest` `a weekly review reminder without a title is called Weekly review and opens the review` (task commit); `PushReminderDeliveryTest` `a pushed weekly review reminder without a title ... opens the review` (new) | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.reminders.*' --tests 'ch.sonensei.cockpit.push.*'` | Not run: the test-writer's sandbox denied every Gradle invocation; the parent must run it |
| 2. English and German strings in `res/values{,-de}/strings_review.xml` | English: `... is called Weekly review ...` (task commit and push test); German: `in German ... Wochenrückblick` (new); files inspected: `review_reminder_title` = "Weekly review" / "Wochenrückblick" | same as above | Not run: Gradle denied in the test-writer sandbox |
| 3. `ktlintCheck testDebugUnitTest lintDebug` passes | whole Android suite | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` | Not run: Gradle denied in the test-writer sandbox |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.reminders.*' --tests 'ch.sonensei.cockpit.push.*'`

Result:

Not run. The permission system denied every attempt to start Gradle from this helper (both `cd android && ./gradlew ...` and `./android/gradlew -p android ...`), while plain `git` commands were allowed. The new tests are therefore unverified: they have not been compiled or executed. The parent worker must run the focused command above (and `ktlintCheck`, since new test code must pass ktlint) before relying on this report.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run (same reason).

## .taskcheck

Result:

Not run (`sh scripts/check.sh` runs Gradle, which is denied in this sandbox).

## Bugs found

None found by inspection. Notes from reading the production code:

- `PushReminderDelivery.toFired()` passes `item.type.value` (generated enum `WEEKLY_REVIEW("WEEKLY_REVIEW")` in `RemindersApi`) into `ScheduledReminder.itemType`, so the push path reaches the same `untitledTitle`/`OpenItemRequest.destination` mapping as the local alarm. The new push test exercises exactly this; it is unexecuted.
- A pushed WEEKLY_REVIEW reminder is shown only if the server sends `item.available = true` (otherwise it is ignored as "item trashed"). That depends on the server task `review-reminder-server`, outside this task's scope.

## Coverage

Status: unavailable - the coverage command (`cd android && ./gradlew createDebugUnitTestCoverageReport`, docs/ARCHITECTURE.md "Coverage") could not be run because Gradle invocations were denied by the permission system in the test-writer sandbox. No partial output was produced; no earlier or repository-wide numbers are substituted.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `review-reminder-android`, base `81c26c97b3139ac4e6b0ce7a3985d4f99cc388ca`, HEAD `bde783c827ba7861e5674b5a0aff066b07e7f1cf`, task commit `bde783c82` (supplied by the parent, confirmed with `git diff --stat`).
- Source/test snapshot identifier (including uncommitted inputs): HEAD `bde783c82` plus the two test files with the sha256 hashes above.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/reminders/ReminderNotifier.kt`, `android/app/src/main/java/ch/sonensei/cockpit/reminders/ReminderOpenRequests.kt`. Excluded: `res/values/strings_review.xml`, `res/values-de/strings_review.xml` (resources, not instrumented; covered behaviorally by the title tests).
- Provider/version, command, working directory, and test selection: JaCoCo via AGP, `./gradlew createDebugUnitTestCoverageReport` in `android/` - not run.
- Run start/completion time and exit status: not run (permission denied before start).
- Generated report paths and report hashes/run identifier: none.
- Evidence outputs are fresh and inputs stayed unchanged: N/A, no run.
- Final run after latest test additions and production fixes: no - no run was possible.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/reminders/ReminderNotifier.kt` | not measured | not measured | not measured | not measured |
| `android/app/src/main/java/ch/sonensei/cockpit/reminders/ReminderOpenRequests.kt` | not measured | not measured | not measured | not measured |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ReminderNotifier.kt` `untitledTitle` both branches | fallback title per item type | Targeted by `only the weekly review gets its own fallback title ...` and the title tests; unmeasured |
| `ReminderOpenRequests.kt` `WEEKLY_REVIEW -> CockpitDestination.Review` | criterion 1 | Targeted by `ReminderUiTest` table and the notifier/push tap tests; unmeasured |

Follow-up: the parent runs `cd android && ./gradlew createDebugUnitTestCoverageReport` and reads the `ReminderNotifier.kt` / `ReminderOpenRequests.kt` counters from `android/app/build/reports/coverage/test/debug/report.xml`, or re-runs the test-writer in an environment where Gradle is allowed.

## Unable to test

- Running any test, lint or coverage: Gradle is denied in this helper's sandbox. Needs follow-up by the parent (run the focused command, ktlint and coverage) before the task is completed.

## Summary

Meaningful task behavior remains untested and requires follow-up: 4 tests were added (UnifiedPush path, German string, fallback mapping), but no test, lint or coverage command could be executed in the test-writer sandbox, so the new tests and the task's criteria are unverified until the parent runs them.
