# Code Review

Date: 2026-11-28

Task: and-cal-event-links-edit - Android: add and remove links from the calendar event editor

Scope: `git diff 2d77642c3 cc166d8b0 -- android` (task commits 7b67ee70b, f62e2cd08, c7e7312ce, cc166d8b0)

Requirements:
- tasks/and-cal-event-links-edit.md (goal, acceptance criteria, recorded assumptions)
- AGENTS.md project notes (lint, strings, conventions)
- Web counterpart web/src/app/features/calendar/overlay/event-links.ts
- Shared Android panel core/links (LinkedItemsPanel.kt, LinkedItemsViewModel.kt) and the existing in-place pattern in contacts/ContactsScreen.kt

Git range:
- Branch: main
- HEAD: cc166d8b0
- Base/start: 2d77642c3aeb57d9965920d058d27b90de0d5487
- Commits reviewed: 4

Summary: 0 P0, 1 P1, 1 P2, 1 P3

Verified as correct (no finding): the panel is shown for `ItemRef("EVENT", edited.eventId)`, the same id the web uses (`eventRef(occurrence)`); a new event has no `links` state, so no panel; read-only events keep the panel (matches the recorded assumption); a create bumps `refresh` also when only the link failed, not on a failed create (matches web "Also after a failed link"); the panel's own error/retry covers offline and server errors without touching the editor; opened in-app items replace the editor via `SearchItemScreen` with `rememberSaveable` state and a `BackHandler`, and the form stays in the view model; the Hilt-less guard mirrors `LinkedItemsSection` and other screens; the removed `EventExtrasRepository.links`/`listLinks`, `LinkedType`, `LinkedItem`, `loadLinks`, `reloadLinks`, `LINKS_RETRY` and the 17 `calendar_edit_link*` strings (en + de) have no remaining references in main code.

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | No calendar/edit test exercises the real event links panel (add, remove, refresh reload)**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventLinksPanel.kt:23`
    - Category: Tests
    - Problem: Every screen test in `EventEditorExtrasScreenTest` replaces the panel with a fake `linksPanel` slot, which only shows that the editor passes `eventId`/`refresh` and handles `open`. Nothing tests `EventLinksPanel` itself: that adding a search hit creates a link for item type `EVENT` with the event id, that the ✕ removes it, or that a higher `refresh` actually calls `LinkedItemsViewModel.reload()` (the `LaunchedEffect(viewModel, refresh) { if (refresh > 0) ... }` wiring). `EventLinksPanel` cannot be tested as written, because it always gets its view model from `hiltViewModel` and returns early outside a Hilt activity. No test under `android/app/src/test` references `EventLinksPanel`.
    - Why it matters: Acceptance criterion 6 explicitly asks for Robolectric/Compose tests in `calendar/edit` for add, remove, open and the create-task/note refresh. Add, remove and the reload on refresh have no tests, so a regression in the only new production composable (wrong item type, a dropped reload) would pass the suite.
    - Suggested fix: Give `EventLinksPanel` an optional `viewModel: LinkedItemsViewModel? = null`, the same way `LinkedItemsSection` does, so it uses Hilt only when none is passed. Then add a Robolectric test in `calendar/edit` that builds a `LinkedItemsViewModel` over a fake links source (as `LinkedItemsViewModelTest` does). It should check that the panel lists links for `ItemRef("EVENT", id)`, that add and remove go to the source with that ref, and that recomposing with a higher `refresh` triggers a second `list` call.

## P2 Medium

- [ ] **CR-2 | Reopening an event (or returning from an opened item) can show stale links**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventLinksPanel.kt:31-32`
    - Category: Correctness
    - Problem: The panel view model is keyed `links:EVENT:<id>` and lives in the screen's ViewModelStore after the editor closes. `LinkedItemsViewModel.open(item)` returns early when the item has not changed (`if (state.value.item == item) return`). `EventEditorViewModel.load()` resets `refresh` to 0 on every open, and the panel reloads only when `refresh > 0`. So when the same event is opened again in the same calendar/Today/Dashboard entry, the old cached list is shown and not loaded again. The same happens after opening a linked item in place (for example an email), removing its link to the event from that item's own panel, and pressing back: the event still shows the removed link, unless a task or note was created earlier in that session. The old code called `loadLinks()` on every `load()`. The web component is recreated each time the dialog opens, so it always loads again.
    - Why it matters: The panel shows links that no longer exist, or misses new ones added elsewhere, until the user taps retry or creates something. Removing such a stale link then fails against the server.
    - Suggested fix: Reload whenever the panel enters composition, not only when `refresh > 0`. For example, use `LaunchedEffect(viewModel, refresh) { viewModel.reload() }`. On the very first entry `open()` loads anyway, so either accept one extra request or skip the reload only while the view model's item is still unset.

## P3 Low

- [ ] **CR-3 | Stale KDoc still says the sources job loads the links**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt:221`
    - Category: Quality
    - Problem: The comment reads "Load the local calendars, the links, suggestions, or send invitations", but the editor no longer loads links (the panel does). `linksJob` now only covers "Create task / note from event".
    - Why it matters: It misleads readers about who owns the link list, which this task deliberately moved to the shared panel.
    - Suggested fix: Remove "the links" from the comment, or say that `linksJob` covers task/note creation.

## Review conclusion

Material issues found; fix before completing the task.
