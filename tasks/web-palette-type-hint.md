---
title: Web: make the Ctrl+K type: filter discoverable (hint or chips in the palette)
status: todo
owner:
deps: [web-small-gaps]
touches: [web/src/app/shell]
prio: 2
---
## Goal
web-small-gaps added a `type:` prefix to the Ctrl+K search (`type:note passport`, parsed in
`web/src/app/features/search/search-query.ts`), but the palette (in `shell/`) gives no hint that it exists.
Android shows type chips. Follow-up of review finding CR-2 in `reviews/web-small-gaps/review.md`.

## Acceptance criteria
- [ ] The command palette shows how to filter by type (a hint line, or type chips that insert/remove `type:`)
- [ ] New UI strings are in `web/src/locale` (English + German)
- [ ] Unit tests cover it; `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
