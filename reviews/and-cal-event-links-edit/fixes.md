# Fix Report

Date: 2026-11-28

Task: and-cal-event-links-edit (Android event editor: shared linked-items panel)

Scope: current task, findings CR-1, CR-2 and CR-3 of reviews/and-cal-event-links-edit/review.md

## Fixed findings

### CR-2 — Event links panel shows a stale list when the same event is reopened

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventLinksPanel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorLinksInAppTest.kt`

Problem:
The panel called `LinkedItemsViewModel.reload()` only when `refresh > 0`. The Hilt view model is keyed per event
(`linksViewModelKey`) and outlives the editor, and `open(item)` returns early for the item it already shows, so
reopening the same event (or coming back after changing links elsewhere) showed the old list.

Fix:
`LaunchedEffect(viewModel, refresh) { viewModel.reload() }` now runs unconditionally. This does not double-load on the
first appearance: the effect runs before `LinkedItemsSectionContent`'s `LaunchedEffect(item) { viewModel.open(item) }`,
and at that point the fresh view model has no item, so `reload()` returns without a request and `open()` loads once.
On later appearances the item is set, `open()` does nothing and `reload()` loads the current links. The existing
tests asserting exactly one `links.list(event)` call on first open (initial load, add link) still pass, which confirms
there is no double load. KDoc and an inline comment explain this ordering.

Regression test added: `reopening the editor of the same event shows the links changed meanwhile` (panel shown with one
link, editor closed by removing the links section, same event shown again while the server now returns two links;
the new link appears and `links.list(event)` was called exactly twice). Without the fix the second appearance makes no
request (refresh stays 0 and `open()` returns early), so the new link never shows and the test fails.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*'` — Pass
  (EventEditorLinksInAppTest: 9 tests, 0 failures, 0 errors)

### CR-3 — Stale comment on the editor's background jobs

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt`

Problem:
The comment on `sourcesJob`/`respondJob`/`linksJob` still said the editor loads "the links", which the shared panel
now does.

Fix:
Comment now reads "Load the local calendars, create a linked task or note, load suggestions, or send invitations",
matching what `linksJob` does (`createLinked`).

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*'` — Pass

### CR-1 — No test of the real panel in the event editor

Files changed:
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorLinksInAppTest.kt` (added by the parent
  worker and test-writer before this fix round; extended here with the CR-2 regression test)

Problem:
The real `EventLinksPanel` (shared section plus Hilt view model) was not exercised from the event editor.

Fix:
Confirmed covered by `EventEditorLinksInAppTest`, which runs the editor in the Hilt `MainActivity` with a fake
`LinksRepository`: the panel loads for `ItemRef("EVENT", eventId)` and for no other item; "Add link" calls
`links.link(ItemRef("EVENT", eventId), ItemRef("EMAIL", ...))` and shows the link; the cross calls
`links.remove(linkId)` and removes the row; a failed removal shows the error; a raised `refresh` after "Create task"
reloads the list (two `list` calls); offline error with retry; no panel for a new event; opening a linked note in place
and back keeps the unsaved edits. No production change was needed for CR-1.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*'` — Pass

Full test suite:
- Not run (parent worker runs `.taskcheck`)

Lint/type-check/build:
- `ktlintCheck` — Pass (part of the command above)

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
