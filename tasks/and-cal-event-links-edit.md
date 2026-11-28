---
title: Android: add and remove links from the calendar event editor
status: in_progress
owner: dell26/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/calendar/edit,android/app/src/test/java/ch/sonensei/cockpit/calendar/edit,android/app/src/main/res]
prio: 2
lease_until: 2026-11-28T13:17:48Z
releases: 1
extended: 
---
## Goal
On Android, links can be added to and removed from a calendar event while looking at the event, as on the web.
Today the Android event editor's Links section (`calendar/edit/EventEditorSections.kt`, `LinksSection`) is read-only
("links are added and removed where the items are"): to link an email to an event, the owner has to open the email
and link the event from there. Requested by the owner: "link any item to a calendar entry when looking at the calendar
entry".

The web event dialog already does this with the shared panel (`web/.../calendar/overlay/event-links.ts`,
`<app-linked-items [item]="eventRef(occurrence)">`, item `{type: EVENT, id: occurrence.eventId}`). Android has the
same shared panel, `core/links/LinkedItemsSection` + `LinkedItemsViewModel` (Add link with search over all item types,
remove with ✕, open a linked item), used by notes, tasks, projects, mail, files and contacts.

Intended approach: replace the event editor's own read-only list with `LinkedItemsSection(ItemRef("EVENT", eventId))`,
keeping the event's "Create task from event" / "Create note for event" actions (SPEC.md §6.3) and their results showing
up in the panel. Drop the editor's now unused link-listing code (`EventExtrasRepository` link list, `LinksUiState`) if
nothing else needs it. Server and web need no change.

## Acceptance criteria
- [ ] In the Android event editor of a saved event, "Add link" opens the search; picking any item type (email, task,
      note, file, contact, ...) links it to the event, and it appears in the panel right away. The link is the same
      one the web shows (item `EVENT` with the event id; for a recurring event the series, not one occurrence, as on web)
- [ ] A linked item can be removed with ✕ from the event editor, and opened (tapping it shows the item, back returns
      to the event editor without losing unsaved edits of the event, or unsaved edits are saved first as in the note
      editor; record which with `tasks.py assume`)
- [ ] "Create task from event" and "Create note for event" still work and the created item shows in the panel
- [ ] A new, not yet saved event shows no link panel (or a hint that links can be added after saving); read-only
      events (e.g. subscribed/read-only calendars, invitations) behave sensibly: decide whether linking is allowed
      (links are Cockpit data, not CalDAV data, so allowing it is expected) and record it with `tasks.py assume`
- [ ] Offline / server error: the panel shows its usual error and retry, the event editor stays usable
- [ ] Tests: Robolectric/Compose UI tests in `android/app/src/test/.../calendar/edit` for add, remove, open and the
      create-task/note refresh; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` green
- [~] Checked on a real device or emulator: link an email to an event from the event editor, then see the event in the
      email's Linked items panel (and on web). Skip with a reason if no device is available
  - skipped (2026-11-28 13:56+0100 by dell26/agent-1): no Android device or emulator here (no adb/emulator on PATH); covered by Robolectric tests of the panel wiring and the event id handed to it

## Notes
- Android only. Strings: reuse the panel's existing `links_*` strings (en + de); remove event-editor link strings that
  become unused (lint flags them).
- ASSUMPTION (2026-11-28 13:50+0100 by dell26/agent-1): Opening a linked item from the event editor shows it in place of the editor (like the note editor's SearchItemScreen); unsaved event edits are kept (form lives in EventEditorViewModel), not saved first.
- ASSUMPTION (2026-11-28 13:50+0100 by dell26/agent-1): Read-only events (subscribed calendars, invitations) still show the links panel with add/remove and create task/note: links are Cockpit data, not CalDAV data.
- release by dell26/agent-1: Main code done (commit 823ee743c): EventLinksPanel.kt (shared LinkedItemsSection for ItemRef EVENT/eventId, reload on LinksUiState.refresh), EventEditorContent linksPanel slot + in-place SearchItemScreen for opened items, LinksUiState slimmed (eventId/creating/result/refresh), EventExtrasRepository.links()/LinkedType/LinkedItem/listLinks and 17 calendar_edit_link* strings removed. NOT compiled yet. Left: fix tests still using removed API: EventExtrasRepositoryTest (links() test ~l.270-300, delete it), EventEditorViewModelExtrasTest (links/loadLinks tests ~l.103, 600-720, 836-851: rewrite to assert LinksUiState(eventId) and refresh increments after create), EventEditorExtrasScreenTest (reloadLinks l.81, link list tests l.350-440: replace with fake linksPanel slot tests); add tests for add/remove/open; run ./gradlew ktlintCheck testDebugUnitTest lintDebug; quality helpers; emulator check (skip if none). Assumptions recorded.
