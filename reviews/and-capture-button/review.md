# Code Review

Date: 2026-11-01

Task: and-capture-button - Android: in-app capture button on every screen

Scope: re-review of the final code. `git merge-base HEAD origin/main`..HEAD, `android/` only (the and-capture-button commits: implementation, tests, the CR-1/CR-2 fix commit, and the fixer's FAB-clearance commit for the GTD list bottoms). Focus: whether earlier findings CR-1 and CR-2 are resolved, and whether the fixes add new problems.

Requirements:
- `tasks/and-capture-button.md` acceptance criteria and its four recorded assumptions (reuse of `CaptureActivity` in the app task; FAB everywhere except Calendar/Mail/Notes/Files; FAB clearance via a CompositionLocal for the GTD snackbars; bottom content padding for the GTD lists)
- `SPEC.md` §2 and §9.3, `AGENTS.md` project notes, `docs/QUALITY.md`
- previous `reviews/and-capture-button/review.md` (CR-1, CR-2) and `reviews/and-capture-button/fixes.md`

Git range:
- Branch: main
- HEAD: a3e3ef1590de6c69351820031225ddacd84ad565
- Base/start: `git merge-base HEAD origin/main` (fixes.md records 21f4c93132a459c2c8e8af31f820803e16126865; origin/main is 9047beca89b6183436af3f2932918d8bd9fa150f)
- Commits reviewed: the task's commits from e60df51ed (implementation) up to HEAD, including ba954ad05 (CR-1/CR-2 fixes) and 7d76ff00e (list FAB clearance, fixer report)

Method note: Bash was not available to the reviewer in this run, so the commit list and diff could not be printed with git. Instead, the reviewer read in full the final versions of every file that fixes.md and the task assumptions name: `ui/FabClearance.kt`, `ui/CockpitShell.kt`, `capture/entry/CaptureButton.kt`, `gtd/today/TodayScreen.kt`, `gtd/lists/ListUi.kt`, `gtd/projects/ProjectsScreen.kt`, the relevant part of `gtd/next/NextScreen.kt`, `ui/FabClearanceTest.kt` and `capture/entry/CaptureButtonTest.kt`. The earlier review had already covered the unchanged rest of the implementation (strings, manifest, `withOwnFab` placement).

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Status of earlier findings

- **CR-1 (FAB covers the Undo action of the GTD snackbars): resolved.**
    - `ui/FabClearance.kt` defines `LocalFabClearance`, which defaults to 0 dp. `ShellFabClearance` is 72 dp: the 56 dp FAB plus the Scaffold's 16 dp spacing.
    - `CockpitShellFrame` (`ui/CockpitShell.kt:127-131`) provides the clearance for each destination through `fabClearance(destination)`. It is 0 dp exactly on `CockpitDestination.withOwnFab`, the same set that decides `showsFab`, so the two can't drift apart.
    - All four in-content `SnackbarHost`s are padded by it: `TodayScreen.kt:116`, `NextScreen.kt:167`, `ListUi.kt:104` and `ProjectsScreen.kt:173`.
    - The five GTD lists also get `contentPadding = PaddingValues(bottom = LocalFabClearance.current)`: Today, Next, Waiting/Someday through `LiveTaskListContent`, the Projects list and the project detail. So the trailing row actions ("Follow up", "Activate", "Make next step") can be scrolled out from under the FAB.
    - The geometry holds. The NavHost content ends at the Scaffold's inner padding (above the bottom bar), and the Scaffold places the FAB 16 dp above that edge, so 72 dp puts the snackbar's bottom at the FAB's top.
    - `FabClearanceTest` covers three things: the clearance for every destination; a padded vs. unpadded snackbar, where the unpadded one is a control that must overlap; and the real `SomedayContent` inside the real shell frame, checking that neither the Undo action nor the last row's action lies under the FAB. fixes.md records a negative run, which failed as expected with the list padding removed.
    - `CockpitShell`'s public signature is unchanged. `CockpitShellFrame` is `internal` and only moves the existing body out.
    - The gtd/ edits are outside the task's `touches`. They are one-line, consistent and covered by recorded assumptions, which the parent and humans can revisit.
- **CR-2 (a quick double tap stacks two capture sheets): resolved as suggested.**
    - `captureSheetIntent` (`CaptureButton.kt:96`) adds `Intent.FLAG_ACTIVITY_SINGLE_TOP` and still has no `FLAG_ACTIVITY_NEW_TASK`, so the sheet stays in the app task.
    - Both intent tests (FAB on the start screen, app-bar action on Notes) assert both flags (`CaptureButtonTest.kt:121-122, 190-191`).
    - Remaining limitation (accepted, not a finding): SINGLE_TOP only takes effect once the first sheet is on top, so two taps delivered before the first launch is processed could still race. The previous review said debouncing was optional, and fixes.md documents this limitation.

## Notes (not findings)

- `fabClearance()` (`CockpitShell.kt:140-147`) is wrapped oddly over several lines, probably by `ktlintFormat`. It is purely cosmetic.
- Other shell-FAB screens without padded lists (Inbox, Habits, New, Review, Contacts, More) have no row controls that only sit at the end, from reading fixes.md and the recorded assumption. The previous review did not raise them, and they stay out of scope.
- Evidence: fixes.md reports `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passed (2263 tests). It also reports that `.taskcheck` failed only on load-induced web vitest timeouts in files this task does not touch. The parent must re-run `.taskcheck` before `done`. As noted in the previous review:
    - Criterion 2's "works in airplane mode" and criterion 3's real back-stack behavior were exercised only under Robolectric. The parent should record them as such, or skip the on-device part with a reason.
    - The reviewer could not run tests in this session.

## Review conclusion

No material issues found.
