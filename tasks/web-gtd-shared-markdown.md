---
title: Web: share renderMarkdown and Markdown styles between project description and task notes
status: done
owner: felix/agent-1
deps: [web-task-actions]
touches: [web/src/app/features/gtd/ui,web/src/app/features/gtd/projects,web/src/app/features/gtd/task-detail]
prio: 2
lease_until: 
releases: 1
extended: 
---
## Goal
Follow-up of web-task-actions review finding CR-3 (P3, deferred): task notes import `renderMarkdown`
from `gtd/projects`, and `task-detail-notes.scss` copies the `.markdown` styles of
`gtd/projects/project-description.scss`. Move both to one shared place so they cannot drift.

## Acceptance criteria
- [x] `renderMarkdown` lives in a shared location (e.g. `gtd/ui`) and both project description and task notes use it; no import from `gtd/projects` into `gtd/task-detail` remains
  - verified (2026-11-09 02:04+0100 by felix/agent-1): grep -rn import web/src/app/features/gtd/task-detail | grep projects -> no hits; renderMarkdown in gtd/ui/markdown.ts, exported via gtd/ui/index.ts, used by project-detail-page.ts and task-detail.ts; gtd/projects/markdown.ts is a re-export shim (assumption recorded); markdown.spec.ts in vitest passes (npm --prefix web run check -> 4682 tests passed)
- [x] One shared `.markdown` stylesheet partial is used by both; rendering looks unchanged
  - verified (2026-11-09 02:04+0100 by felix/agent-1): gtd/ui/markdown.scss holds the .markdown rules, both copies removed; project-detail-page.ts and task-detail.ts styleUrls include ../ui/markdown.scss; diff check: shared rules identical to the removed ones, only task-notes-specific '.markdown a {cursor:pointer}' remains local; ng build passes; reviewer confirmed cascade/specificity unchanged. Not checked visually in a browser
- [x] `cd web && npm run check` and `sh scripts/check.sh` pass
  - verified (2026-11-09 02:06+0100 by felix/agent-1): npm --prefix web run check -> exit 0, 194 files/4682 tests passed, ng build ok; sh scripts/check.sh (server+web+android) -> 'all checks passed', exit 0

## Notes
- ASSUMPTION (2026-11-09 01:35+0100 by felix/agent-1): Kept gtd/projects/markdown.ts as a one-line re-export of gtd/ui/markdown because bookmarks, files and contacts import it and are outside this task's touches; they can switch to gtd/ui later.
- release by felix/agent-1: Implementation complete (renderMarkdown + .markdown styles in gtd/ui, re-export shim kept in gtd/projects). test-writer, code-reviewer, fixer reports are in reviews/web-gtd-shared-markdown/. Fixer fixed CR-1/CR-2 (comments + styleUrls formatting) in ab0f13d35, so review/tests are now STALE: re-run test-writer and code-reviewer on HEAD, then verify the 3 criteria, write quality.json, and run done. npm --prefix web run check passed (4630 tests). sh scripts/check.sh failed only on the android AppStartRoutingTest 'logging out from settings clears the cached files' (ComposeTimeoutException 5000ms, looks flaky and unrelated; web part passed).
- done by felix/agent-1: `sh scripts/check.sh` passed in 170s on 703d1d97b; criteria verified 3/3
