---
title: Notes: Markdown editor with images
status: in_progress
owner: dell26/agent-4
deps: [web-notes-list,web-links-panel]
touches: [web/src/app/features/notes/editor]
prio: 1
lease_until: 2026-10-21T06:54:14Z
extended: 
releases: 0
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
- ASSUMPTION (2026-10-21 07:01+0200 by R1963MACOS/agent-1): Leaving a note while it is in conflict discards the unsaved edits without asking (no in-app navigation guard; only beforeunload warns when closing the tab). Switching notes during an image upload waits for the upload so the image is saved into the note.
- extended by R1963MACOS/agent-1: Implementation, unit tests (95 editor tests, npm run check green) and e2e (editor.spec.ts passed on the e2e stack per tests.md) are done; rebased onto main 95ebfebb, .taskcheck passes. Left: fix final review CR-1 (P2: failed save on in-app note switch silently drops edits - keep draft/error or record assumption + notice, add test) and CR-2 (P3: return in open() when destroyed / use a sequence counter), via fixer; re-run test-writer + code-reviewer on final code; tasks.py verify 1-5 from tests.md evidence; write+commit reviews/web-notes-editor/quality.json; tasks.py done.
- ASSUMPTION (2026-10-21 07:26+0200 by R1963MACOS/agent-1): When switching to another note in-app and the save on leaving fails (non-conflict error), the editor stays on the current note with its draft and error banner and puts the address back (replaceUrl), instead of loading the other note and losing the edits.
- ASSUMPTION (2026-10-21 07:36+0200 by R1963MACOS/agent-1): When the editor stays on a note because its save failed during a switch, it puts the address back with router.navigate(replaceUrl), so browser history keeps two entries for that note (the first Back seems to do nothing); a CanDeactivate guard was not added to keep the change small.
- release by R1963MACOS/agent-1: handed off by R1963MACOS/agent-1: You've hit your session limit · resets 11:20am (Europe/Zurich)
