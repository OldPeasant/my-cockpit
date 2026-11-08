# Fix Report

Date: 2026-11-08

Task: and-inbox-rows - Android: inbox and New view rows complete and open tasks

Scope: current task (finding CR-1 from `reviews/and-inbox-rows/review.md`), base 23fe887e6e17aac83984590906541bcea3d5b6a0, task commits e17499172 and f79a01bf9

## Fixed findings

### CR-1 — Completing or undoing from the Inbox never reloads the Inbox cache, so the list can keep a stale version that the next action is refused for

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxRouteTest.kt`

Problem:
I confirmed the finding against the code. `TaskCompletionRepository.outcome` reloads only `TaskView.NEXT` and `TaskView.TODAY`. After complete, then undo, the Inbox showed the cached copy from before the completion (version v). Completing it again sent `If-Match: "v"`, and the server refused it with 412 (CONFLICT snackbar). Every retry failed the same way.

Fix:
The fix stays inside the task's touches. `TaskCompletion.kt` is not changed. `InboxViewModel.complete` and `InboxViewModel.undo` now start `refreshInboxAfter(taskId)`. It waits on `completer.state` until the task is no longer pending, which means the server answered. Then it calls `tasks.refresh(TaskView.INBOX)`. This happens after Completed, Reopened, and a CONFLICT/FAILED refusal. It does not happen after an OFFLINE failure, because nothing reached the server. This matches the repository's own rule. `complete` now returns early when the completer would ignore the tap (task already hidden), so a double tap does not start an extra reload.

Tests:
- `InboxViewModelTest` adds three tests:
  - complete, undo, complete again: the second completion sends the reloaded version 3, and the Inbox reloads 3 times;
  - a refused completion reloads the Inbox, an offline one does not;
  - the reload waits for the server's answer.
- `setUp` now stubs `tasks.refresh(INBOX)` by default.
- One comment that said the Inbox is not reloaded after a completion was reworded.
- `InboxRouteTest` (checkbox test): added a `tasks.refresh(INBOX)` stub. The strict mock had no answer for the new reload call. No assertion was weakened.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.*' --tests 'ch.sonensei.cockpit.newitems.*'` — Pass (InboxViewModelTest 41 tests, InboxRouteTest 7 tests, 0 failures)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.*' --tests 'ch.sonensei.cockpit.newitems.*'` — BUILD SUCCESSFUL. The first run failed for two reasons, both fixed: a ktlint chain-method-continuation violation in the new test, and a missing `refresh` stub in InboxRouteTest.

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — BUILD SUCCESSFUL

Lint/type-check/build:
- Covered by the full Android run above (ktlintCheck, lintDebug, compilation)

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("== all checks passed"). It ran longer than 10 minutes on this machine, with the Gradle daemons partly cold. The web build printed an existing bundle-budget warning, which this fix did not touch.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
