# Fix Report

Date: 2026-10-20

Task: and-shell (Android app shell: theme, navigation, placeholders)

Scope: current task, finding CR-1 from `reviews/and-shell/review.md` (reviewed revision 448f750d08bb6539b0048ec552388114f2e8ea07)

## Fixed findings

### CR-1 — Opening More discards the saved state of the tab being left

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/ui/CockpitShellNavigationTest.kt`

Problem:
`navigateToTopLevel` set `popUpTo { saveState = destination != More }`. In Navigation, `saveState` applies to the entries being popped, and those belong to the tab being left, not to the target. So going from Inbox, Calendar or Mail to More dropped that tab's state. Coming back then created a fresh entry, with a new id, an empty `rememberSaveable` scope and an empty `SavedStateHandle`. I confirmed this before fixing it.

Fix:
`popUpTo(start) { saveState = true }` is now unconditional. `restoreState = destination != More` is unchanged, so selecting More still always opens the More list, both from a More sub-screen and from another tab. The state saved for More is never restored, and the next time More is left it is replaced. The KDoc now explains this.

Regression tests added to `CockpitShellNavigationTest` (they use the production `CockpitShell(navController)`; there is no test-only NavHost):
- `tabKeepsItsSavedStateAcrossAVisitToMore`: for Inbox, Calendar and Mail, the test records the back-stack entry id and stores a value in the entry's `SavedStateHandle`, then goes to More and back. It asserts that the entry id (and with it the `rememberSaveable` scope) and the stored value both survived. With the old code temporarily restored, this test fails (`ComparisonFailure` on the entry id), which proves it catches the bug.
- `moreIsNotRestoredAfterBeingLeft`: More shows the list again and gets a fresh entry after leaving it and coming back. The existing `leavingMoreFromASubScreenAndComingBackShowsTheMoreList` still passes and covers the sub-screen case.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.CockpitShellNavigationTest'` — Pass (11 tests). With the old `saveState` line temporarily put back: Fail (1 of 11, the new regression test), as expected.
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — Pass (BUILD SUCCESSFUL, 44 tests, 0 failures, 0 skipped)

## Not fixed

None.

## Validation summary

Focused tests:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.CockpitShellNavigationTest'` — Pass, 11 tests

Full test suite:
- `cd android && ./gradlew testDebugUnitTest` — Pass, 44 tests (Android part only; server and web are not affected by this change)

Lint/type-check/build:
- `cd android && ./gradlew ktlintCheck lintDebug` — Pass

`.taskcheck`:
- Not run. This fixer was not permitted to run `sh scripts/check.sh` in its sandbox; the parent worker must run it.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed (the Android part; `.taskcheck` is left to the parent worker).
