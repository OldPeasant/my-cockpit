# Code Review

Date: 2026-11-08

Task: and-scheduled-list - Android: Scheduled list screen

Scope: second-round review of the final task code, `git diff 6a993ed68..161f80803` (production and tests), committed changes only. The commits are a8dedeb2c (implementation), 85880e8dc (first-round tests and review) and 161f80803 (typed ListSection for CR-2, fixer report).

Requirements:
- tasks/and-scheduled-list.md acceptance criteria and the recorded assumption (Scheduled is a More entry after Someday; the checkbox completes and a tap opens the detail; Yesterday or the full date for past dates; the 'No date' group comes last; the one-line CockpitShell.kt edit is outside touches)
- SPEC.md section 5.1 (Scheduled list), docs/PARITY.md row "No Scheduled list"
- AGENTS.md project conventions (i18n EN+DE, lint, feature folders, touches)
- First-round review reviews/and-scheduled-list/review.md (CR-1, CR-2) and reviews/and-scheduled-list/fixes.md
- Android siblings: WaitingScreen.kt, SomedayScreen.kt, LogbookScreen.kt, ListViewModels.kt, ListUi.kt

Git range:
- Branch: main
- HEAD: 161f80803
- Base/start: 6a993ed682dd38426131fa2e8eaa4c852ef0d734
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Files reviewed in full at HEAD: gtd/lists/ScheduledScreen.kt, gtd/lists/ListUi.kt, gtd/lists/ListViewModels.kt, gtd/lists/LogbookScreen.kt, ui/navigation/CockpitDestination.kt, ui/CockpitShell.kt (imports and the Scheduled branch), res/values-de/strings_projects_lists.xml, and the tests ScheduledViewModelTest.kt, ScheduledListTest.kt, ListSectionTest.kt, ScheduledStringsTest.kt, ScheduledInAppTest.kt.

Reviewer limitation: shell access was denied in this session, so the reviewer could not run `git diff` or `git log`. The files above were read in full at the current working tree (clean, HEAD 161f80803 according to the session's git status), and the change set was matched against the first-round review and fixes.md. Gradle was not run by the reviewer. The parent reported this run on HEAD 161f80803: `cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.lists.*' --tests 'ch.sonensei.cockpit.ui.navigation.*'` -> BUILD SUCCESSFUL, 0 failures (ScheduledViewModelTest 9/9, ScheduledListTest 11/11, ListSectionTest 6/6, ScheduledStringsTest 3/3, ScheduledInAppTest 1/1, LogbookScreenTest 17/17, CockpitDestinationTest 11/11). It also reported line coverage of ScheduledScreen.kt 37/38 and ListUi.kt 153/155.

## Resolution of first-round findings

- CR-1 (ScheduledViewModel had no view-model test): resolved. ScheduledViewModelTest builds `ScheduledViewModel(repository, contexts, completion, clock)` against a mock whose stubbed `observe` answers only for `TaskList.SCHEDULED`, and it verifies that call. It feeds the tasks out of date order and asserts date order with ties by position and undated tasks last. It also covers completion (the task is hidden and undo is offered), undo restoring date order, a reload after the completion answer, retry, new data re-sorted on reload, no completion while read-only, and a first-load error. Removing `::byScheduledDate` or observing another list would now fail a test.
- CR-2 (untyped ListSection): resolved. ListUi.kt:120 is now `class ListSection<K>(val key: (GtdTask) -> K, val header: @Composable (K) -> Unit)`. Section rendering moved into `ListSection.items` (ListUi.kt:125-137), where `K` is in scope, and `groupConsecutiveBy` is generic (ListUi.kt:141). `LiveTaskListContent` takes `ListSection<*>?` (ListUi.kt:82), which is sound because `items` takes no `K` parameter. ScheduledScreen.kt:56 builds `ListSection<LocalDate?>` with no cast. The Waiting, Someday and project screens pass no section, so their unsectioned path (ListUi.kt:98-102) is unchanged.

## Re-check of the acceptance criteria

- AC1 (a screen reachable like Waiting/Someday, grouped by date, complete and open detail): `CockpitDestination.Scheduled` sits in the More section between Someday and Logbook (CockpitDestination.kt:53), and CockpitShell.kt imports and routes `ScheduledScreen`. ScheduledContent groups by `scheduledDate` with Today, Tomorrow, Yesterday, a full localized date, or 'No date' (ScheduledScreen.kt:69-83). Rows use `LiveTaskRow`: the checkbox calls `callbacks.complete`, and `opensTask` opens the detail. ScheduledListTest covers the headers (one per date, marked as headings), completion, opening the detail, busy and read-only states, empty, loading, error with retry, and the absence of an extra action. ScheduledInAppTest reaches the real screen from More inside the Hilt activity.
- AC2 (EN and DE strings): `nav_scheduled` and `scheduled_{empty,today,tomorrow,yesterday,no_date}` are in both string files (values-de lines 31-37). ScheduledStringsTest checks that both languages have the same keys, that the German texts are not blank and not copies of the English, and that no placeholders slipped in. The German Compose test checks the rendered headers and the empty text.
- AC3 (tests and checks): tests cover the new behavior as listed above. Running Gradle and `sh scripts/check.sh` is for the parent to record as evidence. The reviewer did not run them.

## New-issue check

No new validated issues. Points checked:
- Lazy keys: section headers use `section-{key}` (`section-null` for undated), and rows use the task UUID. Because the view model sorts first, every date forms exactly one run, so no key repeats (ListSectionTest `sorted by scheduled date, every date is exactly one section`).
- The Logbook refactor keeps its private `DayHeader(date, today)`, which delegates to the shared internal `DayHeader(label, tag)`. Tags and heading semantics are unchanged, and LogbookScreenTest passes per the parent run.
- The full-date header uses `DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)` with the default locale, the same as the existing Logbook header. This is consistent with the project and not new to this task.
- No secrets, logging or new dependencies were introduced. Test data is fictitious.

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
