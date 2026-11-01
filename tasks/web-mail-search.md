---
title: Mail view: search box with 'search on server' option
status: in_progress
owner: dell26/agent-1
deps: [mail-search,web-mail-list]
touches: [web/src/app/features/mail/list,web/src/locale]
prio: 2
lease_until: 2026-11-01T09:01:39Z
---
## Goal
The Mail view (`/mail`, `/mail/folders/:id`) has its own search box (SPEC.md §7.3 "Search"). Today mail
can only be found through the global Ctrl+K palette, which covers only mail the server has cached and
cannot filter by folder. The server API exists (`GET /api/v1/mail/search`, task mail-search, generated
client `web/src/app/api/generated/fn/mail-search/search-mail.ts`); this task only adds the web UI.

## Acceptance criteria
- [ ] The mail list toolbar has a search field. Submitting a query (Enter) calls `GET /api/v1/mail/search`
      scoped to the current folder (`folderId`), or to all folders when the unified Inbox is shown, and the
      list shows the results newest first in the usual message rows; opening a result opens the read view.
      Clearing the field (or Escape) returns to the normal folder list
- [ ] A "Search on server" option (checkbox or button, e.g. "Also search older mail on the server") repeats
      the search with `server=true`. Accounts listed in `server.failures` are named in a non-blocking
      warning; the cached results are still shown. `hasMore` shows a hint that only the newest results
      are listed
- [ ] Loading, empty ("No messages found") and error states use the mail list's existing patterns; the
      list's keyboard shortcuts don't fire while typing in the field, and `/` focuses it
- [ ] New UI strings are in `web/src/locale` (English + German)
- [ ] Unit tests cover scoped search, server search incl. failures, clearing, and the empty/error states;
      `cd web && npm run check` passes

## Notes
Requested by the owner (2026-11-01). Server unchanged. Out of scope: Android mail search, search
filters beyond the query text (from:, date ranges), changes to the Ctrl+K palette.
