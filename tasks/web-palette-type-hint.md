---
title: Web: make the Ctrl+K type: filter discoverable (hint or chips in the palette)
status: in_progress
owner: felix/agent-1
deps: [web-small-gaps]
touches: [web/src/app/shell]
prio: 2
lease_until: 2026-11-09T00:39:26Z
---
## Goal
web-small-gaps added a `type:` prefix to the Ctrl+K search (`type:note passport`, parsed in
`web/src/app/features/search/search-query.ts`), but the palette (in `shell/`) gives no hint that it exists.
Android shows type chips. Follow-up of review finding CR-2 in `reviews/web-small-gaps/review.md`.

## Acceptance criteria
- [x] The command palette shows how to filter by type (a hint line, or type chips that insert/remove `type:`)
  - verified (2026-11-09 01:17+0100 by felix/agent-1): npx vitest command-palette.spec.ts (via npm run check) -> hint line with type:note passport / type:task,event examples shown, hidden once query has a type: word; 2 specs pass
- [x] New UI strings are in `web/src/locale` (English + German)
  - verified (2026-11-09 01:17+0100 by felix/agent-1): palette.typeHint added to web/src/locale/messages.xlf + messages.de.xlf; npm --prefix web run build:de -> exit 0 (fails on missing translations)
- [ ] Unit tests cover it; `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
