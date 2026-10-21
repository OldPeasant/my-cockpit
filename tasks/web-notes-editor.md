---
title: Notes: Markdown editor with images
status: in_progress
owner: dell26/agent-4
deps: [web-notes-list,web-links-panel]
touches: [web/src/app/features/notes/editor]
prio: 1
lease_until: 2026-10-21T07:23:17Z
extended: 
releases: 0
---
## Goal
Edit notes (SPEC.md §8.1).

## Acceptance criteria
- [x] CodeMirror 6 Markdown editor with preview toggle/split, autosave (debounced, If-Match, conflict notice)
  - verified (2026-10-21 09:00+0200 by dell26/agent-4): cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts' at 79f15454 -> 5 files, 122 passed (CodeMirror editor, preview toggle/split, debounced autosave with If-Match, 409/412 conflict banner tests; see reviews/web-notes-editor/tests.md)
- [x] Paste/drop image → upload → inserts `![](cockpit-file:{id})`, rendered via the authenticated file URL; preview checklists toggle and save
  - verified (2026-10-21 09:00+0200 by dell26/agent-4): same editor spec run -> 122 passed (paste/drop upload inserts ![](cockpit-file:id), authenticated preview URL, preview checklist toggle saves immediately); e2e editor.spec pastes a real image
- [x] `[[` autocomplete over all item types inserts `[[type:id]]`, rendered as chips; revisions dialog with restore; linked items panel
  - verified (2026-10-21 09:00+0200 by dell26/agent-4): same editor spec run -> 122 passed ([[ autocomplete over all item types inserts [[type:id]], chips, revisions dialog restore, linked items panel tests)
- [x] Vitest component/service tests cover autosave, checklist toggle, link autocomplete; `cd web && npm run check` passes
  - verified (2026-10-21 09:00+0200 by dell26/agent-4): editor specs 122 passed; cd web && npm run check at 79f15454 (test-writer) -> exit 0, 96 files/1835 tests passed, lint+build ok
- [x] Playwright `e2e/notes/editor.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/notes/editor.spec.ts`, see p0-e2e-harness): paste an image, toggle a checklist, reload → persisted
  - verified (2026-10-21 09:00+0200 by dell26/agent-4): cd web && npm run e2e -- e2e/notes/editor.spec.ts against docker compose e2e stack (podman-compose), chromium, code of 79f15454 -> 16 passed (editor spec + 15 phase-demos): paste image, toggle checklist, reload -> persisted

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
- ASSUMPTION (2026-10-21 08:34+0200 by dell26/agent-4): When a save returns a title the server normalised differently (e.g. Kotlin trim removes U+001C-U+001F), the title field adopts the server's title if it was not retyped during the save, and leaving stops re-saving once a round sends nothing new; the body is never replaced by the server's copy.
