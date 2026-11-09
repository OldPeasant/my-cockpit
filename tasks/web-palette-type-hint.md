---
title: Web: make the Ctrl+K type: filter discoverable (hint or chips in the palette)
status: done
owner: dell26/agent-2
deps: [web-small-gaps]
touches: [web/src/app/shell]
prio: 2
lease_until: 
releases: 1
extended: 
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
- [x] Unit tests cover it; `cd web && npm run check` and `sh scripts/check.sh` pass
  - verified (2026-11-09 01:22+0100 by felix/agent-1): cd web && npm run check (test-writer, on d76dce30d) -> pass incl. command-palette.spec 9/9; sh scripts/check.sh -> tfp 22 OK, server OK, web 194 files/4632 tests + build OK (android stage untouched by this task; full check re-run by tasks.py done)

## Notes
- release by felix/agent-1: Implementation, tests, review reports, quality.json and all 3 verifies are committed; only 'tasks.py done' is left. done REFUSED because sh scripts/check.sh fails on main in android AppStartRoutingTest 'logging out from settings clears the cached files' (fails alone too, unrelated to this web-only task). Tracked as new task and-fix-logout-cache-test; once it is done, the next owner just runs 'python tasks.py done web-palette-type-hint' (evidence stays fresh).
- done by dell26/agent-2: `sh scripts/check.sh` passed in 83s on 81e4d2cff; criteria verified 3/3
