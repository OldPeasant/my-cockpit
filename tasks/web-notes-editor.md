---
title: Notes: Markdown editor with images
status: todo
owner:
deps: [web-notes-list,web-links-panel]
touches: [web/src/app/features/notes/editor]
prio: 1
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
