# Code Review

Date: 2026-11-08

Task: and-dashboard-actions - Android dashboard: tappable items, review button, layout editing (re-review after fixes)

Scope: `git diff a32d28124 78ac21c00 -- android` (the four task commits; the range also contains unrelated merged main changes outside `android/`, which are not part of this task and were not reviewed). `git diff 78ac21c00 HEAD -- android` is empty, so the reviewed Android code is identical at HEAD 10fca3091. Committed changes only.

Reviewed revision: 78ac21c00 (Android code identical at 10fca3091)

Requirements:
- tasks/and-dashboard-actions.md (5 acceptance criteria, 5 recorded assumptions)
- AGENTS.md "Project notes", docs/QUALITY.md
- Previous review and fix report: reviews/and-dashboard-actions/review.md (first round, CR-1 to CR-6), reviews/and-dashboard-actions/fixes.md

Git range:
- Branch: main
- HEAD: 10fca3091
- Base/start: a32d28124c56d00b287cd6377c4bd589c8389399
- Commits reviewed: 4 (416627a6a, b87452d1e, 569068224, 78ac21c00)
- Uncommitted changes: none

Commands run:
- `git log --oneline a32d28124..78ac21c00`, `git diff --stat a32d28124 78ac21c00`
- `git diff a32d28124 78ac21c00 -- android/app/src/main/res .../calendar/edit/EventEditorScreen.kt`
- `git diff 78ac21c00 HEAD --stat -- android` -> empty
- Read in full: DashboardViewModel.kt, DashboardRepository.kt, DashboardScreen.kt; read DashboardActionsScreenTest.kt (helpers, a11y and drag tests)
- `git grep -n 'fun \`' 78ac21c00 -- android/app/src/test/java/ch/sonensei/cockpit/dashboard/` -> test inventory
- `python tasks.py verify and-dashboard-actions` -> all 5 criteria still unverified (the parent worker records them later)
- Gradle was not run in this review session; build, lint and test status comes from the parent worker and the test-writer

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of the first-round findings

- CR-1 (no tests): resolved. Tests now cover the new behaviour. DashboardActionsScreenTest covers item openers, Inbox lines, the habits line, review button states, Customize enablement, checkbox toggle, accessibility actions, long-press drag, reset, layout error messages, the all-hidden state and the German strings. DashboardCustomizeViewModelTest covers change/rollback/queue/refresh races and the openers. DashboardSaveLayoutTest covers If-Match, the ETag carried between saves, 409/412/428 retry once, failure mapping and the late-read ETag race. DashboardSlotsTest covers parse/serialize/move/withVisible. DashboardRouteInAppTest covers the real route: review, project, bookmark with and without a browser, event editor, birthday contact, note editor, and hiding a widget.
- CR-2 (a11y actions on a node TalkBack cannot focus): resolved. `customActions` now sit on the Checkbox's semantics next to `contentDescription = title` (DashboardScreen.kt:497-501). The modifier is applied on the same layout node as the checkbox's toggleable semantics, so the actions end up on the focusable node. The test reads them from that node.
- CR-3 (stale refresh overwrites the saved layout and ETag): resolved. `refresh()` applies the layout only when `generation` is unchanged and nothing is pending (DashboardViewModel.kt:105-109). `readSettings()` keeps its ETag only if no PATCH wrote one meanwhile (DashboardRepository.kt:276-280). Both have regression tests.
- CR-4 (a failed save discards later pending changes): resolved. Edits are queued in `pending`. Each save applies its own edit to `stored`, and the shown layout is `pending` replayed on top of `stored` (DashboardViewModel.kt:174-186), as on the web.
- CR-5 (stale "Read only" KDoc): resolved (DashboardRepository.kt:206).
- CR-6 (wrapped conditions): resolved. Conditions are on one line or extracted into helpers.

Also checked without findings: all 14 new strings exist in EN and DE. The `<id>:hidden` serialization and `dashboardWidgets` key match the web. `stored` cannot be null when `change()` runs (`layoutLoaded` only becomes true together with a fresh first read). All view model state (`pending`, `generation`, `stored`) is touched only on the main dispatcher, and the `saving` mutex serializes saves. `opened()` has an equality guard. Bookmarks fall back to the Bookmarks screen when there is no URL or no browser. `EventEditorCallbacks.of` keeps all 19 callbacks of the former inline wiring.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Customize rows announce the widget name twice, and tapping the name does not toggle it**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardScreen.kt:493`
    - Category: Quality
    - Problem: Each layout row has a Checkbox with `contentDescription = title` and next to it a separate `Text(title)` (line 503). The Row has neither `mergeDescendants` nor a toggle of its own. TalkBack therefore focuses the checkbox ("Today, checkbox, checked") and then the text again ("Today"). On touch, only the 48 dp checkbox toggles, and tapping the widget name does nothing.
    - Why it matters: Redundant screen-reader stops in an 8-row list, and a smaller touch target than the usual Material "checkbox row" pattern. Behaviour is correct, so this is low priority.
    - Suggested fix: Make the row the toggle: `Modifier.toggleable(value = slot.visible, role = Role.Checkbox) { actions.setVisible(slot.id, it) }` on the Row, plus the `customActions`, with `Checkbox(onCheckedChange = null)` and no extra contentDescription. Keep the long-press drag on the same row, and move the test tag and a11y test to the row node.

## Review conclusion

No material issues found. All six first-round findings are fixed and covered by tests, and the one remaining finding is P3. Before `done`, the parent worker still has to record evidence for criteria 1-5 with `tasks.py verify` (none is recorded yet) and run the Gradle and `.taskcheck` commands that criterion 5 names.
