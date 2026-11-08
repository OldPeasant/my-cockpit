# Code Review

Date: 2026-11-08

Task: and-task-convert - Android: convert tasks to notes and events

Scope: Final code of the task's commits b94b6eb82, 4e9e0df1a, ed99e000e, 58d9dd4d5 (fixes for the earlier CR-1..CR-4) and f55b136cd, all in HEAD. Reviewed with `git diff 331589981 HEAD -- android`, limited to the task's own files (gtd/task, gtd/inbox, ui/CockpitShell.kt, strings_task_detail/strings_inbox in both locales, and their tests). The mail/read changes in that range come from other tasks merged from main and are out of scope. Uncommitted test files of the concurrent test-writer were not reviewed.

Requirements:
- tasks/and-task-convert.md acceptance criteria and recorded assumptions
- SPEC.md §5.3 (To reference / To calendar)
- docs/ARCHITECTURE.md, docs/PARITY.md (row "No convert task -> note / event")
- AGENTS.md project notes
- Web reference: web/src/app/features/gtd/inbox/inbox-page.ts, task-detail convert flow

Git range:
- Branch: main
- HEAD: 9bfdc2fe6b668285f8b57e9e477f0b47a350aa97
- Base/start: 331589981717b1a8b0acb786b5ad9491fb67fa39
- Commits reviewed: 5

Summary: 0 P0, 0 P1, 1 P2, 0 P3

## Verification of earlier findings

- CR-1 (device zone instead of the synced zone): fixed. Both `TaskDetailViewModel` (`zone` at TaskDetailViewModel.kt:359) and `InboxViewModel` (InboxViewModel.kt:398) use `settings.value?.timeZone ?: clock.zone` for the default start and for the request. Both `@Inject` constructors take `UserSettingsRepository`. Tests cover a synced America/New_York zone while the clock is in Europe/Zurich.
- CR-2 (an older calendar list overwrites a newer one): fixed. Both view models cancel `calendarsJob` before they start a new load. Both repositories rethrow `CancellationException` (TaskDetailRepository.kt `calendars()`, InboxRepository.kt `calendars()`). The Inbox load also checks that the prompt still belongs to the same item and kind.
- CR-3 (To reference in task detail without confirmation): fixed. The menu item opens an `AlertDialog` with `REFERENCE_CONFIRM` (TaskDetailScreen.kt, `askReference`), in EN and DE. Compose tests cover confirm and cancel. The Inbox converts at once, like the web Inbox (inbox-page.ts `run`, `reference` branch, no confirmation).
- CR-4 (convert calls duplicated): fixed. `TaskConverter.noteResponse`/`eventResponse` are the only places that call the endpoints with If-Match. `TaskDetailRepository` runs them through `call()`, so Problem Details mapping and 409/412 handling stay in one place.

## Acceptance criteria check

1. Inbox "To reference" and "To calendar" (dialog with calendar, start, duration or all-day): implemented. `InboxActionKind.REFERENCE`/`CALENDAR`, ACTION_GROUPS, `ToCalendarDialog` with writable calendars, date and time pickers, duration chips and an all-day switch.
2. Task detail menu offers both actions: implemented, only for open tasks that are not read-only.
3. After converting, the user sees where the item went, with "Open": snackbar in task detail (Open closes the detail and opens the note or the Calendar tab) and in the Inbox (through `LocalConvertedOpener` from the shell). See CR-1 for one race in the Inbox.
4. Strings in EN and DE: all new keys are in `values/` and `values-de/`, for both strings_task_detail.xml and strings_inbox.xml.
5. Tests: the ViewModel, repository (MockWebServer), Compose screen, route and shell-opener tests cover the new behavior. I did not run gradle here, because a concurrent test-writer uses the same clone. The parent must record the ktlint/test/lint and `scripts/check.sh` results. f55b136cd is labelled "unverified" (TaskDetailConvertRouteTest), so its pass must come from an actual run.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Inbox: an older conversion answering last replaces the newer one's "where it went" snackbar**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModel.kt:326`
    - Category: Correctness
    - Problem: `run()` sets `converted = (result as? ProcessResult.Converted)?.item ?: it.converted` whenever any conversion answers. Inbox actions run concurrently: the item leaves the queue at once and the next item can be processed. Suppose item A goes "To reference" and item B then goes "To calendar", and A's request answers after B's. Then A's item overwrites B's. `ConvertedSnackbarEffect` (`LaunchedEffect(converted)`, InboxScreen.kt:235) restarts and cancels B's snackbar, so the user never learns where B went and cannot open it. The web Inbox prevents exactly this with a run sequence ("The 'Moved to ...' status describes only the latest action, whatever answers last", inbox-page.ts:286-290, `seq === this.runSeq`). No test covers the ordering. The tests in InboxConvertViewModelTest cover only dismissing and a later plain action.
    - Why it matters: Criterion 3 ("After converting, the user sees where the item went with an 'Open' action") fails for the newer item when answers arrive out of order. The Android Inbox also differs from the web behavior it is meant to match (docs/PARITY.md).
    - Suggested fix: Give each `run()` a sequence or request id, as the web does. Store `converted` only when the answering run is still the latest conversion (or the latest action, to match the web exactly). Add a ViewModel test in which two conversions answer in reverse order.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task.
