# Code Review

Date: 2026-10-21

Task: and-reminders - Android reminders

Scope: `git diff origin/main...HEAD` (merge-base with origin/main to HEAD 054e47c8), committed changes only. This is a re-review of the final code after the fixes for CR-1 and CR-2 of the previous review (which covered a32d9704).

Requirements:
- tasks/and-reminders.md (acceptance criteria 1-3, Notes and recorded assumptions)
- SPEC.md §9.2
- docs/ARCHITECTURE.md, AGENTS.md project notes and conventions
- reviews/and-reminders/fixes.md (fixes claimed for CR-1 and CR-2)

Git range:
- Branch: main
- HEAD: 054e47c81889833a2c4a0fdb0850d86b82ccd320
- Base/start: merge-base of HEAD and origin/main
- Commits reviewed: 15 (749be4f6 through 054e47c8; new since the last review: a2f0aa90 review report, 3ccfb800 fixes, 7ae47b0e fixes report, 054e47c8 merge of origin/main); no uncommitted changes

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Verified during review:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.reminders.*' --tests 'ch.sonensei.cockpit.auth.*'` -> 217 tests, 0 failures, 0 errors, 0 skipped. Of these, 95 are reminders tests in 9 classes.
- Previous CR-1 (P1, sync not restarted after re-login) is fixed. `ReminderPromptViewModel.onShellShown()` (`ReminderPermissionPrompt.kt:91`) re-reads `dismissed` and `notificationsRequested` from the store and calls `syncScheduler.start()`. It runs from `LaunchedEffect(viewModel)` (`ReminderPermissionPrompt.kt:140`). `CockpitRoot` composes the prompt only inside `if (loggedIn)`, so the effect runs again after every login. `WorkManagerReminderSyncScheduler.cancel()` resets `periodicScheduled`, so the periodic work is enqueued again after a logout. The regression test in `ReminderUiTest` ("a logout and a new login with the same ViewModel ...") keeps one ViewModel instance and toggles the prompt out of and back into the composition, which is how `CockpitRoot` behaves.
- Previous CR-2 (P2, logout leaves dropped notifications) is fixed. `ReminderNotifier.dismissAll()` (`ReminderNotifier.kt:61`) cancels every active notification with `NOTIFICATION_ID` on the `reminders` channel. `ReminderScheduler.clear()` calls it before `store.clear()`. `activeNotifications` and `Notification.channelId` are available at minSdk 29. The regression test in `ReminderLogoutTest` covers a notification that a later sync dropped from the store.
- Merge 054e47c8: the conflict in `auth/AppStartRoutingTest.kt` and `auth/InAppLoginTest.kt` touched only a comment. The result keeps both explanations (GTD sync and reminder sync) and the single `WorkManagerTestInitHelper.initializeTestWorkManager` call. The merge introduced no other changes in the task's files.
- Criterion evidence: `python tasks.py verify and-reminders` still lists criteria 1-3 as unverified. The parent worker must record the evidence before running `done`. This is not a code finding.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
