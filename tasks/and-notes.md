---
title: Android notes
status: in_progress
owner: dell26/agent-3
deps: [and-login,notes-api]
touches: [android/app/src/main/java/ch/sonensei/cockpit/notes,android/app/src/test/java/ch/sonensei/cockpit/notes]
prio: 2
lease_until: 2026-10-21T10:53:24Z
extended: dell26/agent-3
---
## Goal
View/edit notes and add photos (SPEC.md §9.2 item 6).

## Acceptance criteria
- [x] Notes list (pinned first, tag filter), Markdown view with rendered images, plain editor with autosave, add photo from camera/gallery (uploads image)
  - verified (2026-10-21 12:34+0200 by dell26/agent-3): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' -> 134 tests/12 classes, 0 failures: Robolectric+Compose screen tests (NotesScreensTest, NotesScreenInAppTest/NotesRouteInAppTest in Hilt activity) cover pinned-first list + tag filter, Markdown view with cockpit-file images via authenticated loader (MarkdownViewTest), plain editor autosave (NoteEditorViewModelTest/NoteEditorsTest), camera/gallery photo upload (NotePhotoFilesTest, addPhoto tests); no device/emulator here
- [x] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'` passes, covering list ordering and save conflict handling (Robolectric/Compose UI tests for screens)
  - verified (2026-10-21 12:34+0200 by dell26/agent-3): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' -> BUILD SUCCESSFUL, 134 tests, 0 failures/errors/skipped; list ordering: NoteOrderTest, NotesListViewModelTest; save conflicts: NoteEditorViewModelTest conflict tests, NoteEditorsTest; screens: NotesScreensTest, NotesRouteInAppTest (Robolectric)

## Notes
- ASSUMPTION (2026-10-21 11:30+0200 by dell26/agent-3): Android notes has no offline cache (SPEC §9.3 only names Inbox/Next/Today/agenda): the list and a note load from the server, failures show a retry banner; unsaved text stays in the editor until saved
- ASSUMPTION (2026-10-21 11:30+0200 by dell26/agent-3): Save conflict (409/412/404 on PATCH): autosave stops and the user chooses 'Keep my version' (re-saved over the server's current version, or as a new note if deleted) or 'Use the other version'; no merge
- ASSUMPTION (2026-10-21 11:30+0200 by dell26/agent-3): Camera photos need a FileProvider: added one (authority <appId>.notes.photos, cache/notes-photos only) to AndroidManifest.xml and res/xml, plus res/values/strings_notes.xml, outside the task's touches; photos over 25 MB are refused client-side, no downscaling
- ASSUMPTION (2026-10-21 11:30+0200 by dell26/agent-3): Android Markdown rendering is a small in-app parser (headings, lists, checklists read-only, quotes, code, images, inline emphasis/links) instead of a new library; note images load via Coil with memory cache only (no disk cache) through the authenticated OkHttp client
- ASSUMPTION (2026-10-21 11:30+0200 by dell26/agent-3): Android editor edits title and body only (pin/tags/archive/history stay web-only for v1); a new note is created on the server on its first non-empty change or when a photo is added
- ASSUMPTION (2026-10-21 11:54+0200 by dell26/agent-3): Remote (non cockpit-file:) images in notes are never loaded on Android; a placeholder shows their address (like mail blocking remote images), so the authenticated server-rewriting client is never used for them
- ASSUMPTION (2026-10-21 11:54+0200 by dell26/agent-3): Android: reopening a note reloads it from the server only when nothing is pending (unsaved text, save, upload, conflict); otherwise local text is kept and saved
- ASSUMPTION (2026-10-21 11:55+0200 by dell26/agent-3): Android: a photo uploaded during a save conflict is inserted after 'Keep my version', appended to the server text after 'Use the other version', dropped if the note was deleted elsewhere and the user discards
- ASSUMPTION (2026-10-21 11:55+0200 by dell26/agent-3): Android: leaving the editor (toolbar or system back) saves immediately and the notes list waits for that save before reloading
- extended by dell26/agent-3: Implementation, tests, 1st review + fixes (CR-1..6), 2nd review all committed; ktlint + notes tests pass on 886f9cef. Left: confirm WIP commit 886f9cef fully fixes 2nd-review CR-1 (shell top-bar back must save) and CR-2/CR-5 (unsaved new-note text reachable, editor VMs not piling up), optionally P3 CR-3/CR-4; run fixer report (fixes.md) for them, re-run test-writer + code-reviewer on final code, verify both criteria, sh scripts/check.sh, write reviews/and-notes/quality.json, done.
- ASSUMPTION (2026-10-21 12:24+0200 by dell26/agent-3): Android notes: while offline, New note reopens the one new note that could not be created yet (its text is kept), so only one uncreated new note exists at a time
- ASSUMPTION (2026-10-21 12:24+0200 by dell26/agent-3): Android notes: editors left with unsaved text are kept and their saves retried each time the notes list is shown; the list reloads after those retries
- ASSUMPTION (2026-10-21 12:24+0200 by dell26/agent-3): Android notes: leaving the whole Notes screen saves every editor's text in an app-scoped scope; if that save fails (offline) the text is dropped without a message (no persistent local draft store in this task)
- ASSUMPTION (2026-10-21 12:34+0200 by dell26/agent-3): Criterion 1 verified with Robolectric/Compose UI tests in a Hilt test activity, not on a real device or emulator (none available here); on-device check is part of h-android-device
