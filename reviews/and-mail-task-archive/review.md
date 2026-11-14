# Code Review

Date: 2026-11-14

Task: and-mail-task-archive - Android: mail → Next action & archive (read view, New view, mail list)

Scope: `git diff 1bc5b01125fc3be34d5ba13b87aee4baff8aa0f0..HEAD -- android` (production code and tests), task commits 3583cdba9, 2423fddea, 7e7686f37, 7590d0ebe, 45d06ed93. Reviewed statically: no Gradle run, by instruction, because a test-writer runs in parallel. This review replaces the earlier one for 3d3f6a0ca. It checks the fixes in 45d06ed93 (CR-1, CR-3, TW-3) and the test-writer's tests for CR-2.

Requirements:
- tasks/and-mail-task-archive.md acceptance criteria and recorded assumptions: "To Waiting" moved to the read view's overflow menu; the selection action is in the overflow menu and its snackbar stays while a mail is named; `openNext` hook in `ui/CockpitShell.kt`; CR-4/TW-1 (multi-batch partial failure for more than 100 mails) accepted as an assumption
- SPEC.md §7.3 ("→ Next action & archive")
- api/openapi.yaml `POST /api/v1/mail/messages/to-tasks`
- docs/ARCHITECTURE.md, AGENTS.md project conventions
- reviews/and-mail-task-archive/fixes.md and tests.md

Git range:
- Branch: main
- HEAD: 45d06ed93ff2f8ac09d479a241ba7cef7073de86
- Base/start: 1bc5b01125fc3be34d5ba13b87aee4baff8aa0f0
- Commits reviewed: 5 code commits (plus board-only claim/heartbeat/assume commits)
- Uncommitted changes: `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailNextActionInAppTest.kt` was modified in the working tree at review time. This is the parallel test-writer's work, which adds the CR-3 in-app test. It was read but is not committed yet.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Verification of earlier findings

- CR-1 (task opened from the read view could not open its linked mail): fixed. `MailReadScreen.kt` `NextActionTask` now renders `TaskDetailScreen` with an `openItem` that shows the item over the task. A linked EMAIL opens in a `MailReadRoute` on its own keyed view models, so the read view's `MailReadViewModel`, which still holds `state.nextAction`, is not reused and cannot reopen the overlay. Back goes to the task. This is covered by `MailNextActionInAppTest` "opening the linked mail from the new task's detail shows the archived mail".
- CR-2 (tests): addressed. The toolbar tests (`MailReadScreenTest`, `MailReadNoteLinksTest`) follow the new layout. New tests cover the repository (batching, distinct ids, list/archive flags, 404/5xx/unreadable), the three view models (success, not archived, failed, offline, double tap, stale answer), the screens (toolbar, New row buttons, selection menu item, snackbar, 320dp, 48dp) and in-app flows through the real shell.
- CR-3 (links panel stale after a not-archived Next action): fixed. `MailReadScreen.kt:143-145` also reloads for `MailReadNotice.NotArchived`. The effect enters composition only after the task overlay closes (early return), so the reload runs when the mail shows again. An in-app test for this is in the uncommitted test-writer change.
- CR-4 (multi-batch partial failure): accepted as a recorded assumption, so it is not reported again.
- TW-3 (subjectless mail named as an empty string): fixed in `MailReadViewModel.nextAction` and `NewViewModel.nextAction`, which fall back to the task title. Both fixes have tests.

## Acceptance criteria check

1. Read view: the "Next action & archive" toolbar button sits next to "To task", which is unchanged. On success the task detail opens over the mail. Back from it: if archived, `onRemoved` runs and then the view closes, returning to the list it was opened from (Mail list or New view). If not archived, Back returns to the mail with its notice. Met.
2. New view: the row button sits beside "→ Inbox task" in a `FlowRow`. The archived mail leaves the list and the cache, and the task opens once through `LocalTaskOpener` (shell route). Back returns to the New list. Met.
3. Mail list multi-select: the overflow item calls `nextActionsForSelection`. The selection ends, the archived mails leave the list, and the plural snackbar's "Open Next Actions" calls `openNext`, which navigates to `CockpitDestination.Next`. Met.
4. Failures: a not-archived mail keeps its task, and the detail still opens. The notice or snackbar names the mail and the server reason. A failed creation leaves the mail in place with `NextActionFailed`, `TaskOffline` or `NEXT_ACTIONS_FAILED`. Met.
5. Linked items: the mail shows in the task's links and opens from both the read-view path (fixed) and the New-view path (shell `TaskRoutes.detail`). Met.
6. EN/DE strings are present for every new key (`strings_mail.xml`, `strings_new.xml`). Material3 buttons and menu items get 48dp touch targets. The 320dp layouts are tested. Met (statically).
7. Tests exist for the new behavior. Whether Gradle, lint and check.sh pass was not checked here (no Gradle run by instruction). The parent worker must record that evidence.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The read view's task overlay drops the task detail's other navigation (linked tasks, screens, converted note)**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt:216-219` (with `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailScreen.kt:146-152,163-171`)
    - Category: Correctness
    - Problem: `NextActionTask` builds `TaskDetailNavigation(close = leave, openItem = ...)` only. `openTask` and `openScreen` keep their no-op defaults, so a linked TASK or a `SearchTarget.Screen` link does nothing from this overlay. If the task is converted to a note there, `openConverted` calls `navigation.close()` (`leave`) and then `openItem("NOTE", ...)`. `leave` clears `state.nextAction`, and for an archived mail it also closes the read view, so the `NextActionTask` composable is gone and its `linked` state is dropped: the new note never opens. The same task opened from the New view uses the shell's `TaskRoutes.detail`, where all four callbacks work.
    - Why it matters: Criterion 2 asks for "the same result" on both surfaces, but the task detail from the read view is a reduced copy of the shell's task detail. A freshly created task links only to its mail, so this is rare. It shows up once the user adds links or converts the task in that overlay.
    - Suggested fix: Open the task through `LocalTaskOpener`, as the New view does. For an archived mail, call `onRemoved` and close the read view first; for a not-archived mail, keep the read view with its notice. Then the shell's route provides the full navigation and Back behavior, and the local overlay with its nested `MailReadRoute` is no longer needed. Otherwise, at least wire `openTask` and `openScreen` and handle `openConverted` without losing the overlay state.

## Review conclusion

No material issues found.
