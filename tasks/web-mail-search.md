---
title: Mail view: search box with 'search on server' option
status: in_progress
owner: dell26/agent-1
deps: [mail-search,web-mail-list]
touches: [web/src/app/features/mail/list,web/src/locale]
prio: 2
lease_until: 2026-11-01T09:16:41Z
---
## Goal
The Mail view (`/mail`, `/mail/folders/:id`) has its own search box (SPEC.md §7.3 "Search"). Today mail
can only be found through the global Ctrl+K palette, which covers only mail the server has cached and
cannot filter by folder. The server API exists (`GET /api/v1/mail/search`, task mail-search, generated
client `web/src/app/api/generated/fn/mail-search/search-mail.ts`); this task only adds the web UI.

## Acceptance criteria
- [x] The mail list toolbar has a search field. Submitting a query (Enter) calls `GET /api/v1/mail/search`
      scoped to the current folder (`folderId`), or to all folders when the unified Inbox is shown, and the
      list shows the results newest first in the usual message rows; opening a result opens the read view.
      Clearing the field (or Escape) returns to the normal folder list
  - verified (2026-11-01 10:00+0100 by dell26/agent-1): mail-list-page.spec.ts + mail-list-store.spec.ts search specs (cd web && npx ng test --watch=false --include='src/app/features/mail/**/*.spec.ts' -> 604 passed): Enter searches GET /mail/search with folderId of the folder / none for unified Inbox, results flat newest first, row opens /mail/messages/:id; emptying field, blank submit and Escape reload the folder list
- [x] A "Search on server" option (checkbox or button, e.g. "Also search older mail on the server") repeats
      the search with `server=true`. Accounts listed in `server.failures` are named in a non-blocking
      warning; the cached results are still shown. `hasMore` shows a hint that only the newest results
      are listed
  - verified (2026-11-01 10:01+0100 by dell26/agent-1): same vitest run: 'Also search older mail on the server' checkbox repeats with server=true, keeps cached rows with 'Searching on the server…', failed accounts named by address in a role=status warning with results shown, hasMore hint shown -> pass
- [x] Loading, empty ("No messages found") and error states use the mail list's existing patterns; the
      list's keyboard shortcuts don't fire while typing in the field, and `/` focuses it
  - verified (2026-11-01 10:01+0100 by dell26/agent-1): same vitest run: loading note, load-error with Try again re-running the search, 'No messages found'; j/k/x/e/s/u/v/o/#// shortcuts don't fire while typing in the field, '/' focuses it (not with modifiers/in other inputs/dialogs) -> pass
- [x] New UI strings are in `web/src/locale` (English + German)
  - verified (2026-11-01 10:01+0100 by dell26/agent-1): web/src/locale messages.xlf + messages.de.xlf have 9 new mail.search.* units and updated mail.keysHint with German targets; src/locale/messages-de.spec.ts (placeholders/targets in step) -> pass in vitest run
- [x] Unit tests cover scoped search, server search incl. failures, clearing, and the empty/error states;
      `cd web && npm run check` passes
  - verified (2026-11-01 10:01+0100 by dell26/agent-1): cd web && npm run check -> pass (185 files, 4053 tests, build ok); sh scripts/check.sh -> all checks passed

## Notes
Requested by the owner (2026-11-01). Server unchanged. Out of scope: Android mail search, search
filters beyond the query text (from:, date ranges), changes to the Ctrl+K palette.
- ASSUMPTION (2026-11-01 09:44+0100 by dell26/agent-1): Changing folder (route) clears an active mail search: a search is for the folder it was started in; the server-search option stays as chosen.
- ASSUMPTION (2026-11-01 09:44+0100 by dell26/agent-1): Search results are not refetched on live change events (a server search is slow and itself loads mail); actions still update them optimistically, resubmitting refreshes.
- ASSUMPTION (2026-11-01 09:44+0100 by dell26/agent-1): Search results are always flat (one row per message) even in the Conversations view, since GET /mail/search returns messages; limit is the server default page size (50).
- ASSUMPTION (2026-11-01 09:44+0100 by dell26/agent-1): Repeating a search with 'Also search older mail on the server' keeps the cached results visible with a 'Searching on the server...' status until the answer arrives; an HTTP error replaces them with the usual load error.
