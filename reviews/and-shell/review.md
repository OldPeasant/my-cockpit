# Code Review

Date: 2026-10-20

Task: and-shell (Android app shell: theme, navigation, placeholders)

Scope: `git diff 12e99dabc244ec4a629b9b123d0e21974486a462..448f750d08bb6539b0048ec552388114f2e8ea07` (task commits c20dbfc implementation, 448f750 tests + tests.md). Reviewed revision: 448f750d08bb6539b0048ec552388114f2e8ea07.

Requirements:
- `tasks/and-shell.md` acceptance criteria and the three recorded assumptions (`python tasks.py assumptions`)
- `SPEC.md` (§2.1 Android stack, §9.2, §12 tests)
- `docs/ARCHITECTURE.md` (Android: MVVM, one package per feature, externalized strings, lint warnings as errors, Coverage)
- `AGENTS.md` project notes and conventions
- `touches` of the other `tasks/and-*.md` (placeholder package ownership)
- Parent worker instructions

Git range:
- Branch: main
- HEAD: 448f750d08bb6539b0048ec552388114f2e8ea07
- Base/start: 12e99dabc244ec4a629b9b123d0e21974486a462
- Commits reviewed: 2 (c20dbfc, 448f750)
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 1 P2, 0 P3

## Acceptance criteria check

1. `CockpitApp` is `@HiltAndroidApp` and registered in the manifest (`android:name=".CockpitApp"`, recorded assumption #3). `MainActivity` is `@AndroidEntryPoint`. `CockpitTheme` uses Material 3 with dynamic color on API 31+, a fallback palette before that, and dark mode following the system. The bottom bar holds Today, Inbox, Calendar, Mail and More, and More lists the 12 required items in the required order (`CockpitDestination`). Met.
2. There is one placeholder `<Feature>Screen(modifier)` per destination, each in the package its owning feature task touches (gtd/today, gtd/inbox, calendar/agenda, mail/list, gtd/next, gtd/projects, gtd/lists, notes, files, contacts, bookmarks, journal, habits, review, settings). I checked these against the `touches` of the other `and-*` tasks, and they match assumption #1. All of them are listed in `android/README.md`. Met.
3. User-facing strings (labels, back content description, placeholder body) are in `res/values/strings.xml`. I found no hard-coded UI text in `android/app/src/main/java`. Met.
4. `ch.sonensei.cockpit.ui.*` contains Robolectric Compose UI tests that cover switching between all bottom-bar destinations, the More sub-screens, back behavior and the back stack. The test-writer reports 39 passed. I did not re-run them in this review; the parent records the evidence. Met, subject to the recorded evidence.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Opening More discards the saved state of the tab being left, which contradicts "each tab keeps its state"**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt:106`
    - Category: Correctness
    - Problem: `popUpTo(...) { saveState = destination != CockpitDestination.More }` picks `saveState` based on the *target* tab. In Navigation, `saveState` controls whether the destinations being *popped* are saved, and those belong to the tab being left. So tapping More from Inbox, Calendar or Mail pops that tab without saving it. The next time the user returns to that tab, `restoreState = true` has nothing to restore, and the tab starts fresh. The KDoc at line 101 and the README say "each tab keeps its state". Assumption #2 only covers More being reset, not the other tabs losing their state. `CockpitShellTest.tabKeepsItsPlaceWhenComingBack` (test line 115) does not catch this, for two reasons: the placeholders hold no state, and the test never visits More.
    - Why it matters: Feature tasks cannot edit the shell (they only touch their own packages). Once Inbox, Mail or Calendar keep `rememberSaveable` state (scroll position, filters, selection), every visit to More would silently reset it, and the owning feature task could not fix that.
    - Suggested fix: Always save the state of the tab being left (`saveState = true`), and keep `restoreState = destination != More` so that More still opens on its list. Alternatively, decide `saveState` from the current destination rather than the target. Then add a test that puts a `rememberSaveable` value or a nested entry in a tab, goes to More and back, and asserts the value survived. This can be a test-only composable driven through the same `navigateToTopLevel` logic, or an assertion on the back stack / saved state.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task. All acceptance criteria are met. The one P2 finding is a tab-state defect in the shared navigation helper that later feature tasks cannot fix themselves, so it should be resolved here.
