# Code Review

Date: 2026-11-14

Task: and-mail-task-archive - Android: mail → Next action & archive (read view, New view, mail list)

Scope: `git diff 1bc5b0112..HEAD -- android` (task commits 50ca61a7e, 23dc80124, 3d3f6a0ca), reviewed statically (no Gradle run, by instruction: a test-writer works in parallel)

Requirements:
- tasks/and-mail-task-archive.md acceptance criteria and recorded assumptions
- SPEC.md §7.3 ("→ Next action & archive")
- api/openapi.yaml `POST /api/v1/mail/messages/to-tasks` (MailToTasksRequest / MailToTasksResponse)
- docs/ARCHITECTURE.md and AGENTS.md project conventions
- web counterpart: `web-mail-task-archive` is still `todo`, so there is no web behavior to compare yet

Git range:
- Branch: main
- HEAD: 3d3f6a0ca
- Base/start: 1bc5b01125fc3be34d5ba13b87aee4baff8aa0f0
- Commits reviewed: 3
- Uncommitted changes: none at review time

Summary: 0 P0, 2 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Task opened from the read view cannot open its linked mail (criterion 5)**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt:136` (with `android/app/src/main/java/ch/sonensei/cockpit/search/SearchItemScreen.kt:40`)
    - Category: Correctness
    - Problem: After "Next action & archive" in the read view, the new task is shown with `SearchItemScreen("TASK", ...)`. That builds `TaskDetailNavigation(close = onClose)` only, so `openItem` keeps its no-op default. The task detail's links panel maps the linked mail (type `EMAIL`, an in-app type in `SearchModels.kt:83`) to `navigation.openItem(...)`, which does nothing. The mail does appear in the task's linked items, but tapping it does nothing. The New view path works because it opens the task through `LocalTaskOpener`, i.e. the shell's `TaskRoutes.detail` with a real `openItem`.
    - Why it matters: Criterion 5 ("The task detail's linked items show the mail, and opening it shows the archived mail") fails for the read view, which is the main entry point of the feature.
    - Suggested fix: Let the read view's task overlay open linked items, for example with its own `TaskDetailNavigation` that has an `openItem`/`openTask` overlay inside `MailReadRoute`, or by opening the task through `LocalTaskOpener` as the New view does while keeping the Back behavior from criterion 1. Do not show the mail in a nested `MailReadRoute` that shares the same `MailReadViewModel`. `open(sameId)` returns early and `state.nextAction` would show the task overlay again. Add a test that a linked mail opens from that task detail.

- [ ] **CR-2 | Committed code breaks existing read-view tests; no tests yet for the new behavior**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt:410-414` (toolbar), affecting `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadScreenTest.kt:101-108,163-181` and `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadNoteLinksTest.kt:165-185`
    - Category: Tests
    - Problem: "To Waiting" moved from the toolbar into the overflow menu, but `MailReadScreenTest.actionTags` still clicks `MailReadTags.gtd(MailGtdAction.WAITING)` directly in the toolbar and expects the call order `gtd TASK, gtd WAITING, act ARCHIVE...`. The 320dp toolbar test in `MailReadNoteLinksTest` also requires that tag to be shown in the toolbar. Those nodes now exist only after the menu opens, so these tests would fail. The task commits add no tests for `MailRepository.toNextActions` (batching, missing task, archive failure), `MailReadViewModel.nextAction`, `NewViewModel.nextAction`, `MailListViewModel.nextActionsForSelection`, the snackbar or the new toolbar, row and menu items.
    - Why it matters: Criterion 7 requires unit and Compose tests for the new behavior and a passing `testDebugUnitTest`. At this revision, neither holds.
    - Suggested fix: Update the existing toolbar tests to the new layout (Next action & archive in the toolbar, To Waiting in the overflow). Add tests for the paths listed above, including archive failure, creation failure and Offline. This is expected from the parallel test-writer pass. Confirm it before the task is completed.

## P2 Medium

None.

## P3 Low

- [ ] **CR-3 | Mail links panel is stale after a not-archived Next action**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt:141`
    - Category: Correctness
    - Problem: The links panel reloads only when the notice is `MailReadNotice.Created`. If archiving fails, the user returns from the task overlay to the mail with a `NotArchived` notice. The keyed `LinkedItemsViewModel` was loaded before the action, so it still shows the old links without the new task.
    - Why it matters: The mail that stayed in place does not show its new Next action under linked items until it is reopened. This contradicts "Linked tasks show a link back to the mail" (SPEC.md §7.3) for that screen.
    - Suggested fix: Also reload when the notice is `NotArchived`, or reload whenever the task overlay closes.

- [ ] **CR-4 | Multi-batch failure reports "nothing created" after earlier batches succeeded**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailRepository.kt:159` and `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt:355`
    - Category: Correctness
    - Problem: `toNextActions` sends batches of 100 in sequence inside one `mailCall`. If a later batch fails (for example, 404 for a mail that is gone), the whole call becomes `Failed`/`Offline`, and the results of the earlier batches are lost. Those batches already created tasks and archived mails. The list then shows "The next actions could not be created; the mails stay where they are" and keeps the archived mails in the list.
    - Why it matters: The message is wrong and the list is out of date. This only happens with more than 100 selected mails, which needs 100+ manual taps (there is no select-all), so it is rare.
    - Suggested fix: Return the results collected so far together with the failure (or stop at the first failed batch and report the partial count), and remove the mails that were archived. Alternatively, cap the selection at 100 for this action.

## Review conclusion

Material issues found; fix before completing the task.
