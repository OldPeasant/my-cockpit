---
title: Web: share renderMarkdown and Markdown styles between project description and task notes
status: todo
owner:
deps: [web-task-actions]
touches: [web/src/app/features/gtd/ui,web/src/app/features/gtd/projects,web/src/app/features/gtd/task-detail]
prio: 2
---
## Goal
Follow-up of web-task-actions review finding CR-3 (P3, deferred): task notes import `renderMarkdown`
from `gtd/projects`, and `task-detail-notes.scss` copies the `.markdown` styles of
`gtd/projects/project-description.scss`. Move both to one shared place so they cannot drift.

## Acceptance criteria
- [ ] `renderMarkdown` lives in a shared location (e.g. `gtd/ui`) and both project description and task notes use it; no import from `gtd/projects` into `gtd/task-detail` remains
- [ ] One shared `.markdown` stylesheet partial is used by both; rendering looks unchanged
- [ ] `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
