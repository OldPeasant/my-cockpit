# Fix Report

Date: 2026-11-08

Task: and-dashboard-actions - Android dashboard: tappable items, review button, layout editing

Scope: current task; findings CR-2 to CR-6 from `reviews/and-dashboard-actions/review.md` as assigned by the parent worker (CR-1, missing tests, was handled by the test-writer in a858c5d7). Base 8af08396, task commits f930a79e and a858c5d7. Changes stay within the task's touches (dashboard main and test folders).

## Fixed findings

### CR-2 - Accessibility "Move up / Move down" actions sit on a node TalkBack cannot focus

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/dashboard/DashboardActionsScreenTest.kt`

Problem:
The `customActions` were set on the row's plain `semantics` block. The row is not a focusable node, so TalkBack users could not reach the actions.

Fix:
The actions now sit on the Checkbox's semantics, next to `contentDescription = title`. That checkbox is the node TalkBack focuses and it names the widget. The actions are built by a new private helper, `moveActions(index, last, moveUp, moveDown, move)`. The row no longer carries semantics. The test helper `customActions(widget)` now reads the actions from the checkbox node (`DashboardTags.slotVisible`). It also asserts that this node's content description is the widget title, so the test checks the node TalkBack actually focuses.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*'` - Pass

### CR-3 - A refresh whose GET started before a layout save can overwrite the saved layout and roll back the ETag

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardRepository.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/dashboard/DashboardSaveLayoutTest.kt`

Problem:
`refresh()` checked `unsaved == 0` only after the GET answered. A GET sent before a change, and answered after that change was saved, put the old layout back on screen and in `stored`. It also replaced the PATCH's newer ETag with its own older one.

Fix:
- View model: a `generation` counter goes up on every layout change and every finished save. `refresh()` records it before the GET. It applies the returned layout (as shown layout and as `stored`) only when the counter has not moved and no change is pending.
- Repository: an `etagWrites` counter goes up when a PATCH returns an ETag. `readSettings()` keeps its ETag only if no PATCH wrote one while it ran. ETag and counter are guarded by one lock (replacing `@Volatile`).
- The existing test `DashboardCustomizeViewModelTest` "a layout read before a saved change does not undo it when it answers late" failed before this fix and passes now.
- New regression test `DashboardSaveLayoutTest` "a read that started before a save does not replace the save's ETag with its older one". MockWebServer holds a GET until a PATCH has finished. The next PATCH must send the PATCH's ETag ("s2"), not the late GET's ("s1").

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*'` - Pass

### CR-4 - A failed save also discards later pending changes, unlike the web

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/dashboard/DashboardCustomizeViewModelTest.kt`

Problem:
A failed save rolled the view back to `stored`. A save waiting behind it then sent that rolled-back layout, so the later change was lost too.

Fix:
This now works like the web's `DashboardLayout`:
- Each change is queued as a `LayoutEdit` in `pending`, which replaces the `unsaved` counter.
- When its turn comes, a save sends its own edit applied to the layout stored at that moment.
- After every save, successful or not, the edit leaves `pending` and the shown layout becomes `pending` applied on top of `stored`.
- A refused change therefore drops out alone. Later changes stay visible and are still saved, and the error message stays visible.

Two new tests: "a failed save drops only its own change, a later pending one is still shown and saved" and "right after an earlier save fails the later pending change stays shown". All existing customize tests still pass, including "quick changes ...", "when a later change fails ..." and "a failed save rolls back ...".

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*'` - Pass

### CR-5 - Repository KDoc still says it is read only

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardRepository.kt`

Problem:
The class KDoc said "Read only: nothing here changes data." even though `saveLayout` PATCHes `/settings`.

Fix:
The sentence now reads: "Only the dashboard layout setting is written (by [saveLayout])."

Validation:
- `./gradlew ktlintCheck` - Pass

### CR-6 - Hard-to-read wrapped conditions

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardScreen.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardViewModel.kt`

Problem:
Conditions were split across lines, like `if (x !=\n null\n)` and `index >\n 0`.

Fix:
- The birthday check is now a plain `if (birthday != null) { ... }`.
- The bookmark opener is now `url?.let { startActivity(...) } ?: onOpen(Bookmarks)`.
- The layout error string comes from a new `@StringRes layoutErrorText(error)` helper.
- The move-action index checks moved into `moveActions` (`index > 0`, `index < last`).
- `openEvent` is now `calendars?.let { Event(...) } ?: Screen(Calendar)`.
- The save result maps to an error through `DashboardResult<*>.layoutError()`.

After `ktlintFormat`, no condition in the dashboard main sources ends a line with `!=`, `==`, `<` or `>` (checked with grep).

Validation:
- `./gradlew ktlintFormat ktlintCheck` - Pass

## Not fixed

None. CR-1 was not part of this fixer assignment because the test-writer already added the tests in a858c5d7. This round adds regression tests for CR-2, CR-3 and CR-4.

## Validation summary

Focused tests:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*'` - Pass (the first run found a compile error and a missing title mapping in the test helper; both were fixed and the rerun passed)

Full test suite:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest lintDebug` - BUILD SUCCESSFUL (9m 4s)

Lint/type-check/build:
- Included above: ktlintCheck and lintDebug pass.

`.taskcheck`:
- `sh scripts/check.sh` - Fail, from the environment and not from this change. The server part fails with 425 of 3062 tests failed because Testcontainers cannot reach Docker in this sandbox ("Could not find a valid Docker environment"). Every other failure follows from that (Spring context load threshold). This fix changed no server files. The parent worker must run `.taskcheck` where Docker is available.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed. The Android check passes. `.taskcheck` could not pass in this sandbox because the server tests need Docker.
