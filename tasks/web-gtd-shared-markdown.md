---
title: Web: share renderMarkdown and Markdown styles between project description and task notes
status: in_progress
owner: felix/agent-1
deps: [web-task-actions]
touches: [web/src/app/features/gtd/ui,web/src/app/features/gtd/projects,web/src/app/features/gtd/task-detail]
prio: 2
lease_until: 2026-11-09T01:14:51Z
releases: 1
extended: 
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
- ASSUMPTION (2026-11-09 01:35+0100 by felix/agent-1): Kept gtd/projects/markdown.ts as a one-line re-export of gtd/ui/markdown because bookmarks, files and contacts import it and are outside this task's touches; they can switch to gtd/ui later.
- release by felix/agent-1: Implementation complete (renderMarkdown + .markdown styles in gtd/ui, re-export shim kept in gtd/projects). test-writer, code-reviewer, fixer reports are in reviews/web-gtd-shared-markdown/. Fixer fixed CR-1/CR-2 (comments + styleUrls formatting) in ab0f13d35, so review/tests are now STALE: re-run test-writer and code-reviewer on HEAD, then verify the 3 criteria, write quality.json, and run done. npm --prefix web run check passed (4630 tests). sh scripts/check.sh failed only on the android AppStartRoutingTest 'logging out from settings clears the cached files' (ComposeTimeoutException 5000ms, looks flaky and unrelated; web part passed).
