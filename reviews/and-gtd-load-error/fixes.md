# Fix Report

Date: 2026-10-26

Task: and-gtd-load-error - Android: show first-load errors in GTD lists (TaskRepository reports refresh failure)

Scope: current task; finding CR-1 from `reviews/and-gtd-load-error/review.md`

## Fixed findings

### CR-1 — Today hides a failed first load of the Today view when the Next view has tasks scheduled for today

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/TodayViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/TodayScreen.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/TaskListUi.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayScreenTest.kt`

Problem:
`TodayUiState.firstLoadFailed` required `sections.isEmpty()`. If one view (TODAY or NEXT) was never loaded and its load failed while the other had tasks for today, the screen showed a partial list with no error and no retry.

Fix:
- `firstLoadFailed` is now `refreshedAt == null && loadFailed`: a list the screen needs was never loaded and its load failed, whether or not the other list has sections. `loading` already stays false when `loadFailed` is set (unchanged).
- New `LoadFailedBanner(retry, modifier)` next to `LoadFailed` in `TaskListUi.kt`: a full-width `errorContainer` Surface with the `lists_error_failed` text and a retry `TextButton` tagged `TaskListTags.RETRY`, laid out like the lists `StaleBanner`.
- `TodayContent` shows the banner (tagged `TaskListTags.ERROR`) above the sections when `firstLoadFailed` and the sections are not empty. With no sections, the full-screen `LoadFailed` still shows, as before.
- Failed reloads over loaded lists (`refreshedAt != null`) still show no error, as the recorded assumption says.
- Tests added: view-model tests for a failed first load of TODAY while NEXT has a scheduled task, and of NEXT while TODAY has tasks (`firstLoadFailed` true, `loading` false, sections kept). A screen test checks that the partial case shows the banner with the text, the sections and the list, no loading, and that retry calls the callback. The existing tests still hold under the new rule: failed reloads have `refreshedAt` set, and the full-error cases have no sections.

Validation:
- Gradle cannot run in this sub-agent environment. The parent must run `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*' lintDebug` — Not run here
- Manual check: no added line is longer than 140 characters (the ktlint_official limit) — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- Not run by the fixer (Gradle is not available to sub-agents). The parent runs `TodayViewModelTest`, `TodayScreenTest` and `TodayRouteTest`.

Full test suite:
- Not run by the fixer. The parent runs `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`.

Lint/type-check/build:
- Not run by the fixer. The parent runs ktlintCheck and lintDebug as part of the command above.

`.taskcheck`:
- Not run (the parent runs `sh scripts/check.sh`).

Remaining unresolved findings:
0

## Final status

All validated findings fixed. The parent still has to run validation (Gradle is not available to the fixer).
