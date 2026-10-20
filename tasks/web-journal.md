---
title: Journal UI
status: todo
owner:
deps: [p0-web-shell,journal-api,p0-e2e-harness]
touches: [web/src/app/features/journal]
prio: 3
---
## Goal
Write and browse the journal (SPEC.md §8.5).

## Acceptance criteria
- [ ] 'Today' opens/creates today's entry (CodeMirror + image paste), calendar heatmap, on-this-day
- [ ] Vitest component/service tests cover today entry creation, heatmap; `cd web && npm run check` passes

## Notes
