---
title: Bookmarks UI
status: in_progress
owner: dell26/agent-3
deps: [p0-web-shell,bookmarks-api,p0-e2e-harness]
touches: [web/src/app/features/bookmarks]
prio: 3
lease_until: 2026-10-22T02:20:44Z
---
## Goal
Read-later queue and bookmarks (SPEC.md §8.4).

## Acceptance criteria
- [ ] Read-later queue, all by tag, add by URL, mark read, snapshot view
- [ ] Vitest component/service tests cover queue actions; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-22 03:46+0200 by dell26/agent-3): Snapshot view shows the file in snapshotFileId via /files/{id}/preview (Markdown rendered with the shared renderMarkdown, other text as plain text); the API has no way to request a snapshot, so the UI only shows existing ones
- ASSUMPTION (2026-10-22 03:46+0200 by dell26/agent-3): Search (SPEC 8.4) is left to the global search; the bookmarks API has no search parameter and the task criteria do not name it
- ASSUMPTION (2026-10-22 03:46+0200 by dell26/agent-3): app.i18n.spec.ts now uses the Dashboard placeholder instead of Bookmarks (outside touches, needed because Bookmarks is no longer a placeholder)
- ASSUMPTION (2026-10-22 03:58+0200 by dell26/agent-3): app.spec.ts: /bookmarks moved to the BUILT list (outside touches, needed because Bookmarks is no longer a placeholder)
- ASSUMPTION (2026-10-22 03:58+0200 by dell26/agent-3): Favicons are not loaded from third-party sites: the production CSP allows only same-origin images and direct loads would leak the owner's IP (cf. SPEC 4.1 mail images); the list shows a letter tile instead until a server-side favicon proxy exists
