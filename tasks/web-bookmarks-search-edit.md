---
title: Web bookmarks: search, edit, undo, to inbox
status: in_progress
owner: dell26/agent-1
deps: []
touches: [web/src/app/features/bookmarks,web/src/locale]
prio: 2
lease_until: 2026-11-09T00:55:22Z
---
## Goal
Android bookmarks have search and editing; web has neither (SPEC.md §8.4 lists search). SPEC.md §4.5 wants
bookmark → Inbox task.

## Acceptance criteria
- [x] Search field (title, URL, notes, tags)
  - verified (2026-11-09 01:37+0100 by dell26/agent-1): npx ng test --watch=false --include='src/app/features/bookmarks/**/*.spec.ts' in web/ at e52450cb4 -> 132 passed; search specs cover title, URL, notes (description), #tag, multi-word, no-match message, Escape clears, Show more while searching
- [x] Edit title, tags and notes
  - verified (2026-11-09 01:37+0100 by dell26/agent-1): same bookmarks spec run -> 132 passed; editor specs: opens with current values, PATCH title/description/tags with If-Match of the opened version, blank -> null, cancel by button/Escape, failure keeps editor, conflict keeps editor and retry saves
- [x] Undo after delete; "To Inbox task" action
  - verified (2026-11-09 01:37+0100 by dell26/agent-1): same bookmarks spec run -> 132 passed; delete opens MatSnackBar 'Undo' -> POST /api/v1/trash/BOOKMARK/{id}/restore and row reloaded, dismissed -> no restore, failed restore -> error; 'To Inbox task' -> POST /api/v1/tasks (UUIDv7 id, list INBOX, notes = URL) + POST /api/v1/links origin, retry reuses id, unlinked and failed messages
- [ ] New UI strings are in `web/src/locale` (English + German)
- [ ] Unit tests cover the new behavior; `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-09 01:12+0100 by dell26/agent-1): Bookmark 'notes' are the server's description field (no separate notes field in the API), as on Android; search filters only the loaded bookmarks client-side (API has no q param), as Android does
- ASSUMPTION (2026-11-09 01:12+0100 by dell26/agent-1): To Inbox task: title = displayTitle (max 500), notesMd = URL, plus a link TASK->BOOKMARK labelled origin, same as Android; web reuses features/capture/uuidv7 for the idempotent task id
