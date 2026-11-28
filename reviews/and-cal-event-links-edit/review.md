# Code Review

Date: 2026-11-28

Task: and-cal-event-links-edit - Android: add and remove links from the calendar event editor

Scope: round 2, the whole task diff at the final revision, `git diff 5d4c6a954 011e2e9d3 -- android`. This round focuses on the round-1 fixes (EventLinksPanel.kt, the EventEditorViewModel.kt comment) and the new test file EventEditorLinksInAppTest.kt.

Requirements:
- tasks/and-cal-event-links-edit.md (goal, acceptance criteria, recorded assumptions)
- AGENTS.md project notes (lint, strings, conventions)
- Round-1 review (this file, previous version) and reviews/and-cal-event-links-edit/fixes.md
- Shared Android panel core/links (LinkedItemsPanel.kt, LinkedItemsViewModel.kt)

Git range:
- Branch: main
- HEAD: 011e2e9d336687d526104d076def3960634b759d
- Base/start: 5d4c6a9542f1736f75a1d76e75003732bf882c5a
- Commits reviewed: all task commits in 5d4c6a954..011e2e9d3. Shell git commands were not available to this reviewer in round 2, so the current files were read directly and checked against the round-1 diff and the fix report.

Summary: 0 P0, 0 P1, 0 P2, 0 P3 (open). All 3 round-1 findings are resolved.

Verified in round 2 (no finding):
- `EventLinksPanel` (EventLinksPanel.kt:35) now runs `LaunchedEffect(viewModel, refresh) { viewModel.reload() }` unconditionally. `LinkedItemsViewModel.reload()` is `load()`, which returns at once while `state.value.item` is null (LinkedItemsViewModel.kt:100). On the first appearance the parent effect runs before the section's `LaunchedEffect(item) { viewModel.open(item) }` (LinkedItemsPanel.kt:150), so only `open()` loads. Even if the order were different, `load()` cancels the previous `loadJob`, so at worst one superseded request runs and the list never goes stale. Later appearances (reopening the same event, or returning from an item opened in place, which removes the panel from composition in EventEditorScreen.kt:209-212) reload the current links. A higher `refresh` still reloads after a task or note is created.
- The panel is still shown for `ItemRef("EVENT", edited.eventId)`. A new event (`links == null`) has no panel. Read-only events keep the panel, which matches the recorded assumption. Opening a linked item in place keeps the form in the view model.
- The new tests run the real `EventLinksPanel` in the Hilt `MainActivity` with a `@BindValue` fake `LinksRepository`. They assert real behavior through the repository calls and the UI: the list is loaded only for `EVENT`/eventId; Add link searches and calls `links.link(event, EMAIL ref)`, and the link shows with no extra list call; ✕ calls `links.remove`, and a failed removal shows the error while the link stays; a linked note opens in place, and back keeps the unsaved title without closing the editor; `refresh = 1` reloads after Create task; reopening the same event reloads (the CR-2 regression test); offline shows the error and retry while the editor stays editable; a new event shows no panel and makes no request. Test data is fictitious, and the tests use no real network or credentials.

## P0 Critical

None.

## P1 High

- [x] **CR-1 | No calendar/edit test exercises the real event links panel (add, remove, refresh reload)** (round 1, resolved)
    - File: `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorLinksInAppTest.kt:170-310`
    - Category: Tests
    - Problem (round 1): All screen tests replaced the panel with a fake slot, so add, remove and the reload on refresh had no tests.
    - Resolution: `EventEditorLinksInAppTest` runs the editor's default `linksPanel` (the real `EventLinksPanel` with its Hilt `LinkedItemsViewModel`) against a fake `LinksRepository`. It covers add, remove, a failed remove, open and back, the reload on create, the reload on reopen, offline with retry, and the case of no panel for a new event. This takes a different route from the suggested view-model parameter, but it tests the same wiring and is closer to production. Verified by reading the tests. Per the fix report they pass (9 tests); this reviewer did not run Gradle.

## P2 Medium

- [x] **CR-2 | Reopening an event (or returning from an opened item) can show stale links** (round 1, resolved)
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventLinksPanel.kt:35`
    - Category: Correctness
    - Problem (round 1): The panel reloaded only when `refresh > 0`, and `open()` returns early for the same item, so the long-lived view model showed an old list.
    - Resolution: The panel now reloads unconditionally whenever it enters composition or `refresh` changes, without a double load on first appearance (see "Verified" above). The regression test `reopening the editor of the same event shows the links changed meanwhile` asserts that the new link shows and that `list` was called exactly twice.

## P3 Low

- [x] **CR-3 | Stale KDoc still says the sources job loads the links** (round 1, resolved)
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt:221`
    - Category: Quality
    - Resolution: The comment now reads "Load the local calendars, create a linked task or note, load suggestions, or send invitations".

## Review conclusion

No material issues found.
