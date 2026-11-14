# Fix Report

Date: 2026-11-14

Task: and-mail-task-archive - Android: mail → Next action & archive (read view, New view, mail list)

Scope: current task; findings CR-1/TW-2, CR-3 and TW-3 from `reviews/and-mail-task-archive/review.md` and `reviews/and-mail-task-archive/tests.md`, as passed by the parent worker (base 1bc5b01125fc3be34d5ba13b87aee4baff8aa0f0).

## Fixed findings

### CR-1 / TW-2 — Task opened from the read view cannot open its linked mail

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailNextActionInAppTest.kt`

Problem:
After "Next action & archive" in the read view, the new task was shown with `SearchItemScreen("TASK", ...)`, whose `TaskDetailNavigation` has only `close`. Tapping the linked mail in the task's links panel did nothing (acceptance criterion 5).

Fix:
New private composable `NextActionTask` in `MailReadScreen.kt` renders `TaskDetailScreen(taskId, TaskDetailNavigation(close = leave, openItem = ...))`. `openItem` sets a saveable overlay state (keyed by the task id). An opened item shows over the task, and Back or its close returns to the task. A linked mail (type EMAIL) is shown with `MailReadRoute` on its own view models (`hiltViewModel(key = "mail-next-action-linked:$itemId")` and `"mail-next-action-linked-attachments:$itemId"`), not the read view's shared `MailReadViewModel`, which still holds `state.nextAction`. Other in-app types use `SearchItemScreen`. The unused `TASK_ITEM_TYPE` constant was removed. The in-app test no longer describes itself as failing, and it now also checks that Back from the linked mail returns to the task detail (title "Subject 1", mail text gone).

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*' --tests 'ch.sonensei.cockpit.newitems.*'` — Pass (`MailNextActionInAppTest` "opening the linked mail from the new task's detail shows the archived mail" passes, including the new Back assertion)

### CR-3 — Mail links panel is stale after a not-archived Next action

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt`

Problem:
The mail's links panel reloaded only for a `MailReadNotice.Created` notice. After a Next action whose archive failed, the mail showed again without its new task in the linked items.

Fix:
The `LaunchedEffect(state.notice)` now reloads the links for `MailReadNotice.Created` and for `MailReadNotice.NotArchived`.

Validation:
- same command as above — Pass (no dedicated test: the reload path needs the Hilt links view model; the condition is a one-line extension of the existing Created branch)

### TW-3 — A mail without subject is named as an empty string in the not-archived notice

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/newitems/NewViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadNextActionViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/newitems/NewNextActionViewModelTest.kt`

Problem:
In the read view and the New view, a blank mail subject produced an empty name in the "could not be archived" notice; the mail list already falls back to the created task's title.

Fix:
Both view models now use the created task's title (`created.title`) when the subject is blank. New tests: `MailReadNextActionViewModelTest` "a failed archive of a mail without subject names it by the task's title" and `NewNextActionViewModelTest.aFailedArchiveOfAMailWithoutSubjectNamesItByTheTaskTitle`.

Validation:
- same command as above — Pass (both new tests run and pass)

## Not fixed

None of the supplied findings. CR-2 (tests) and CR-4/TW-1 (multi-batch partial failure, covered by a recorded assumption) were not in the list passed to this fixer and were not changed.

## Validation summary

Focused tests:
- `./gradlew ktlintFormat` (in `android/`) — formatted `MailReadViewModel.kt`
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*' --tests 'ch.sonensei.cockpit.newitems.*'` — Pass, BUILD SUCCESSFUL, 1088 tests, 0 failures, 0 skipped

Full test suite:
- Not run (full `testDebugUnitTest` and `sh scripts/check.sh` are left to the parent worker before `done`)

Lint/type-check/build:
- `cd android && ./gradlew lintDebug` — Pass, BUILD SUCCESSFUL
- ktlintCheck — Pass (part of the focused command)

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
