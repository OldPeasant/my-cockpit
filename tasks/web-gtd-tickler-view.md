---
title: Web: Tickler list view (what comes back when)
status: done
owner: dell26/agent-1
deps: [web-gtd-tickler-chip,and-gtd-tickler-view]
touches: [web/src/app/features/gtd/lists,web/src/app/features/gtd/gtd.routes.ts,web/src/app/shell/nav-sections.ts,web/src/locale]
prio: 2
lease_until: 
---
## Goal
The web client gets the same as Android task `and-gtd-tickler-view` (read its Goal): a **Tickler** list view next to
Scheduled, listing every task still in the tickler with the date it comes back, so the user can check that it is
there and when it returns. SPEC.md is updated by `and-gtd-tickler-view`; don't edit it here.

## Acceptance criteria
- [x] A "Tickler" / "Wiedervorlage" entry in the navigation next to Scheduled (`shell/nav-sections.ts`), route
      `/tickler`, lists all open tasks with a tickler date after today, sorted by that date (then by their usual
      order), each showing the date it comes back. Tasks whose tickler date has come are not shown
  - verified (2026-11-23 19:29+0100 by dell26/agent-1): npx ng test --watch=false --include src/app/features/gtd/lists/tickler-page.spec.ts -> pass: nav entry {id:tickler,path:/tickler,label:Tickler} right after Scheduled + gtd.routes 'tickler'; lists open tasks with ticklerDate>today sorted by date then position with 'Back tomorrow'/'Back on Fri, 12 Mar'/'... 2028' labels; tickler date today, due-only and done tasks hidden
- [x] Opening a row opens the task detail, where the date can be changed or another list chosen; the view reflects
      the change afterwards. Rows support completing a task like the other list views
  - verified (2026-11-23 19:29+0100 by dell26/agent-1): tickler-page.spec.ts -> pass: row title click navigates ['/tasks', id] (shared task detail with tickler form); re-render after detail change shows new date/drops task that left tickler; TASK UPDATED event reloads; checkbox completes (If-Match) and row leaves, failed completion restores it
- [x] Loading, empty ("Nothing in the tickler"), offline and error states follow the existing list pages
      (`features/gtd/lists`: Scheduled, Someday, Waiting)
  - verified (2026-11-23 19:29+0100 by dell26/agent-1): tickler-page.spec.ts -> pass: 'Loading…' until all windows answer, empty 'Nothing in the tickler.', load failure (offline) shows role=alert 'The list could not be loaded.' + Try again retry, same markup as Scheduled/Someday/Waiting (they have no separate offline state; assumption recorded)
- [x] Data comes from the API the Android task uses (`GET /api/v1/tasks?from=&to=` filtered on `ticklerDate`, or
      the server filter that task added); no other server change here
  - verified (2026-11-23 19:29+0100 by dell26/agent-1): tickler-page.spec.ts -> pass: only listTasks with from/to: 10 yearly windows from tomorrow (each <366 days, paging via nextCursor), client filter ticklerDate>today, dedupe; git diff base..HEAD shows no server/ or api/ change
- [x] New strings are in `messages.xlf` and `messages.de.xlf`; unit tests cover the criteria above;
      `cd web && npm run check` passes
  - verified (2026-11-23 19:29+0100 by dell26/agent-1): 6 units (nav.tickler, gtd.tickler.title/backTomorrow/backOn/backOnYear/empty) in messages.xlf and messages.de.xlf, checked by messages-de.spec.ts; cd web && npm run check -> lint pass, 200 files / 5023 tests passed, build (en+de) ok, exit 0
- ASSUMPTION (2026-11-23 19:22+0100 by dell26/agent-1): Tickler view reads 10 yearly GET /api/v1/tasks?from=&to= windows starting tomorrow (server max 366 days), filters ticklerDate>today client-side, like and-gtd-tickler-view; tickler dates more than 10 years ahead are not listed. Date shown as 'Back on EEE, d MMM' (+ year outside the current year, 'Back tomorrow' for tomorrow); German 'Zurück am …' uses the same pattern. No separate offline state: like Scheduled/Someday/Waiting, a failed load shows the error with retry.
- done by dell26/agent-1: `sh scripts/check.sh` passed in 74s on 0cd3bc134; criteria verified 5/5
