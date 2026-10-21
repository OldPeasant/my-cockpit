---
title: Notes: Markdown editor with images
status: in_progress
owner: R1963MACOS/agent-1
deps: [web-notes-list,web-links-panel]
touches: [web/src/app/features/notes/editor]
prio: 1
lease_until: 2026-10-21T05:06:21Z
---
## Goal
Edit notes (SPEC.md §8.1).

## Acceptance criteria
- [ ] CodeMirror 6 Markdown editor with preview toggle/split, autosave (debounced, If-Match, conflict notice)
- [ ] Paste/drop image → upload → inserts `![](cockpit-file:{id})`, rendered via the authenticated file URL; preview checklists toggle and save
- [ ] `[[` autocomplete over all item types inserts `[[type:id]]`, rendered as chips; revisions dialog with restore; linked items panel
- [ ] Vitest component/service tests cover autosave, checklist toggle, link autocomplete; `cd web && npm run check` passes
- [ ] Playwright `e2e/notes/editor.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/notes/editor.spec.ts`, see p0-e2e-harness): paste an image, toggle a checklist, reload → persisted

## Notes
- ASSUMPTION (2026-10-21 06:31+0200 by R1963MACOS/agent-1): Preview uses an own escaped Markdown renderer (features/notes/editor/note-markdown.ts, no new library) trusted via bypassSecurityTrustHtml because Angular's sanitizer strips checkbox inputs; images only from cockpit-file: (shown via /api/v1/files/{id}/preview with the session cookie) and https:
- ASSUMPTION (2026-10-21 06:31+0200 by R1963MACOS/agent-1): Autosave: 1 s debounce; a 409/412 stops autosaving and shows a banner with 'Discard my edits' (reload) or 'Save my version' (re-GET version, overwrite). Checklist toggles in the preview save immediately. Pending edits are flushed when leaving the page.
- ASSUMPTION (2026-10-21 06:31+0200 by R1963MACOS/agent-1): Route /notes/:id added to features/notes/notes.routes.ts (outside touches, one entry) and @codemirror/autocomplete added as a direct dependency (already in the lockfile transitively). Chips show type + title from the note's links or the [[ search; unknown items show the type only.
- ASSUMPTION (2026-10-21 06:31+0200 by R1963MACOS/agent-1): Default layout is side by side (split), choice remembered in localStorage; messages.xlf not re-extracted (shared file, other features don't either).
