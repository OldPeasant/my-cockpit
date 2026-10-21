---
title: Android notes
status: in_progress
owner: dell26/agent-3
deps: [and-login,notes-api]
touches: [android/app/src/main/java/ch/sonensei/cockpit/notes,android/app/src/test/java/ch/sonensei/cockpit/notes]
prio: 2
lease_until: 2026-10-21T10:18:09Z
---
## Goal
View/edit notes and add photos (SPEC.md §9.2 item 6).

## Acceptance criteria
- [ ] Notes list (pinned first, tag filter), Markdown view with rendered images, plain editor with autosave, add photo from camera/gallery (uploads image)
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'` passes, covering list ordering and save conflict handling (Robolectric/Compose UI tests for screens)

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
