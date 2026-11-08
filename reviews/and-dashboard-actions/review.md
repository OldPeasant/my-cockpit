# Code Review

Date: 2026-11-08

Task: and-dashboard-actions - Android dashboard: tappable items, review button, layout editing

Scope: `git diff f964dbfe..734e6678` (single task commit, Android only; committed changes only)

Requirements:
- tasks/and-dashboard-actions.md (acceptance criteria and the five recorded assumptions)
- AGENTS.md "Project notes", docs/QUALITY.md
- Reference behaviour: web/src/app/features/dashboard/dashboard-layout.ts, dashboard-page.html
- Server contract: server/src/main/kotlin/ch/sonensei/cockpit/core/settings/api/SettingsController.kt (PATCH needs If-Match; 409/412/428), core/settings/infra/DashboardSettings.kt (`dashboardWidgets`)

Git range:
- Branch: main
- HEAD: 734e6678
- Base/start: f964dbfe49e97a5594f2edcefcc78fd0f6d67c42
- Commits reviewed: 1 (734e6678 "Android dashboard: tappable items, review button, layout editing")
- Uncommitted changes: none

Commands run:
- `git log --oneline f964dbfe..734e6678`, `git diff --stat` and `git diff f964dbfe..734e6678` -> 12 files, +743/-80
- Read DashboardScreen.kt, DashboardViewModel.kt, DashboardRepository.kt in full, plus EventEditorScreen.kt / CalendarWithEditor.kt diff, ProjectOpenRequests.kt, BirthdayContact/NoteEditor/EventEditor back handlers, SettingsController.kt, web dashboard-layout.ts
- `grep -rln "saveLayout|REVIEW_BUTTON|customize|openEvent|setVisible|reviewButton|DashboardActions|CUSTOMIZE" android/app/src/test/java/ch/sonensei/cockpit/dashboard` -> no matches
- `python tasks.py verify and-dashboard-actions` -> all 5 criteria still unverified
- `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*'` -> not run (Bash permission denied for Gradle in this review session); lint/test status must come from the parent worker or the test-writer

Summary: 0 P0, 2 P1, 1 P2, 3 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | None of the new behaviour has tests**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/dashboard/DashboardViewModelTest.kt:43`
    - Category: Tests
    - Problem: The commit only adapts existing tests to the new `List<WidgetSlot>` type (and taps the Inbox card at its edge). Nothing tests `saveLayout` (If-Match sent, ETag kept, 409/412/428 re-read and resend, failure mapping), `parseSlots`/`toSetting`/`moved`/`withVisible`, the view model's `setVisible`/`move`/`reset`/`customize` (optimistic update, newly shown widget loads, rollback plus `layoutError` on failure, `unsaved` vs. `refresh()`), `openEvent`/`openProject`/`opened`, `reviewButton()` (continue / start at >= 7 days / none), or the Compose behaviour (item taps for events, projects, bookmarks, notes, Inbox lines, review button, Customize panel, checkbox, reset, accessibility actions).
    - Why it matters: Criterion 5 ("Unit/Compose tests cover the new behavior") is not met, and the concurrency code in `change()` is the kind of logic that breaks without anyone noticing.
    - Suggested fix: Add repository tests with MockWebServer for the PATCH/ETag paths. Add view model tests for change/rollback/serialization and the refresh interplay (see CR-3). Add Compose tests for each item opener, the review button states, the Customize panel (toggle, reset, custom actions present on a focusable node) and the drag (`performTouchInput { longClick(); moveBy(...) ; up() }`).

- [ ] **CR-2 | Accessibility "Move up / Move down" actions sit on a node TalkBack cannot focus**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardScreen.kt:497`
    - Category: Correctness
    - Problem: `customActions` is set on the row's plain `Modifier.semantics { ... }`, without `mergeDescendants`. The row has no text, content description or click of its own, so Compose does not make it screen-reader focusable (it is neither a merging node nor a speaking leaf). TalkBack focuses its children separately instead: the `Checkbox` (toggleable, which merges its own subtree, with `contentDescription = title`) and the `Text`. Neither child carries the custom actions, so a TalkBack user cannot reach them. A semantics-tree test would still find the actions on the tagged row, so a test alone would not catch this.
    - Why it matters: Criterion 3 asks for reordering through accessibility actions. Without them, reordering needs the long-press drag, which TalkBack users cannot do.
    - Suggested fix: Move the `customActions` to the Checkbox's semantics (the focusable node that already names the widget). Or make the row `semantics(mergeDescendants = true)` with the title as its description, and check that the checkbox state is still announced. Then test the actions on the node TalkBack actually focuses (e.g. `onNode(hasContentDescription(title) and hasCustomAction...)`).

## P2 Medium

- [ ] **CR-3 | A refresh whose GET started before a layout save can overwrite the saved layout and roll back the ETag**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardViewModel.kt:99`
    - Category: Correctness
    - Problem: `refresh()` checks `unsaved == 0` only after `dashboard.layout()` returns. Sequence: a refresh (on resume, after an editor closes, or a pull) sends GET /settings. The user then toggles a widget. Its PATCH finishes first, so `unsaved` is back to 0. The older GET response then arrives: `stored` and the shown layout are set to the pre-change layout. In the repository, `readSettings()` also overwrites `settingsEtag` with the older ETag (DashboardRepository.kt:265). The UI now shows the old layout while the server holds the new one. The next Customize change edits the stale layout and sends it, which reverts the earlier change on the server. The stale ETag itself recovers through the 412 retry.
    - Why it matters: The user's saved change is reverted without any message. The window is short but real, because a refresh runs on every resume and after every editor close.
    - Suggested fix: Keep a layout generation counter, incremented in `change()`, record it before the GET, and only apply the GET's layout when the counter has not moved (instead of checking only `unsaved == 0`). In the repository, only accept a GET's ETag when no PATCH completed during it, or let PATCH results always win.

## P3 Low

- [ ] **CR-4 | A failed save also discards later pending changes, unlike the web**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardViewModel.kt:184`
    - Category: Correctness
    - Problem: When save N fails while save N+1 waits on the mutex, the rollback restores `stored`. The waiting save then sends `state.value.layout`, which is the rolled-back layout. So the later change is lost too, and a redundant PATCH goes out. The web (`DashboardLayout`) replays pending changes on top of the stored layout, so "a refused change drops out without taking the later ones with it".
    - Why it matters: This is a minor parity gap. An error message is shown, but it suggests that only one change failed.
    - Suggested fix: Queue the edit functions (as the web does) and re-apply the ones still pending on top of `stored` after a failure. Or document the behaviour in the assumption.

- [ ] **CR-5 | Repository KDoc still says it is read only**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardRepository.kt:206`
    - Category: Quality
    - Problem: The class KDoc says "Read only: nothing here changes data.", but `saveLayout` now PATCHes `/settings`.
    - Why it matters: The comment now misleads anyone reasoning about side effects.
    - Suggested fix: Update the sentence (e.g. "Only the dashboard layout setting is written.").

- [ ] **CR-6 | Hard-to-read wrapped conditions**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardScreen.kt:173`
    - Category: Quality
    - Problem: Several conditions are wrapped as `if (x !=\n null\n)` or `index >\n 0` (DashboardScreen.kt:173, 217, 446, 500-507; DashboardViewModel.kt:138-141), apparently formatter output from an over-long expression.
    - Why it matters: These conditions are hard to read, and they sit in the branching logic.
    - Suggested fix: Extract locals or helper functions (e.g. `val birthday = ...; if (birthday != null)`, a `layoutErrorText(error)` helper, `index > 0` on one line) so the formatter keeps them on one line.

Checked without findings: EN/DE strings (14 new keys in both files, matching). Setting key and `<id>:hidden` serialization match the web's `serializeLayout`. PATCH sends only `dashboardWidgets` with If-Match and retries once on 409/412/428 as the assumption states. The mutex serializes saves, and each save sends the latest layout. Customize is disabled until the layout has loaded. Nested item clickables inside the clickable card take the tap first, and the Inbox card test was adjusted for that. `LaunchedEffect(state.open)` calls `opened()` after acting, with an equality guard. The overlays (note, birthday contact) and the event editor all have back handlers, and closing them refreshes the dashboard. The `EventEditorCallbacks.of` extraction preserves every callback. `ProjectOpenRequests` is a singleton shared with the Projects screen.

## Review conclusion

Material issues found; fix before completing the task.
