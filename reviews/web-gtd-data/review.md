# Code Review

Date: 2026-10-20

Task: web-gtd-data - Web GTD data layer and shared task components

Scope: `git diff 5c73783d..HEAD -- web/` (signal-based GTD stores in `web/src/app/features/gtd/data`, shared task UI in `web/src/app/features/gtd/ui`, and their specs). Committed changes only. Task-board commits in the range (`assume:`, a `heartbeat:` of another task) are out of scope.

Requirements:
- tasks/web-gtd-data.md acceptance criteria 1-4
- SPEC.md §4.2 / entity rules (version + ETag, updates require If-Match, 409/412 on conflict), UI strings externalized (i18n-ready)
- docs/ARCHITECTURE.md (Angular standalone, signals, zoneless; `@angular/localize`; feature folders)
- AGENTS.md project notes
- Server contract cross-checked: `server/.../gtd/domain/TaskService.kt` (list filters and ordering, move semantics), `core/web/ETags.kt`
- Parent instructions: focus on optimistic updates/rollback, out-of-order list answers, SSE resync/ChangeEvent handling, If-Match/ETag usage, accessibility and i18n

Git range:
- Branch: main
- HEAD: e0ca00be8a968498bac941e5279b78c25b29c7f9 (the given task commits dd457aa2/fa46741c/a20d40ff now appear as b9662dc7/e5ad6f05/e0ca00be after a rebase; the content is the same task work)
- Base/start: 5c73783d1973f0ac35e574a5794870968dbf434f
- Commits reviewed: 3 task code commits (b9662dc7, e5ad6f05, e0ca00be); 4 board-only commits in the range were not reviewed

Summary: 0 P0, 0 P1, 1 P2, 3 P3

What I verified and found correct:
- Out-of-order safety: `refresh()` uses a request sequence, so only the newest request can set items, error or loading. An older answer that fails after a newer one succeeds does not set the error. Switching the query clears the old items. Tests reverse the order of answers (`task-store.spec.ts:95-134`).
- Optimistic overlay and rollback: pending changes are an overlay over the server state. The overlay is removed in `finally`, so an error rolls back without touching the server state, and the error is rethrown. A list answer requested before a local change does not undo that change (`mergeLoaded` with a logical clock). A late answer with an older version does not replace a newer one (`accept` version check).
- SSE: `resync$` refetches (and does nothing before the first load). `DELETED` of the store's own type drops the item. Other ops and related types refetch. Subscriptions use `takeUntilDestroyed`.
- `matchesTask` matches the server's `TaskService.specification` filters, including the due windows `today..today+6`, `maxMinutes` with a null estimate, and the completed/open split. Open ordering (position, then id ascending) matches the server.
- UI: the checkbox has a localized accessible name (complete/reopen + title). The title is a real `<button>`. Chips are a list. Pickers use `role=group` with a localized `aria-label` and `aria-pressed` toggle buttons. Focus-visible outlines exist. User-facing strings use `i18n`/`$localize` with ids.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | If-Match sends the newest version the list knows, not the version the caller's copy is based on, so conflicts go undetected**
    - File: `web/src/app/features/gtd/data/list-store.ts:141`
    - Category: Correctness
    - Problem: `ifMatch(item)` returns `etag(Math.max(item.version, <version in this.server()>))`. If a view holds an older copy (for example an edit dialog opened on v3) and an SSE-triggered refetch has since loaded v4 (changed on another device), `update`/`move`/`complete`/`remove` send `If-Match: "4"`. The server accepts this and overwrites or deletes based on stale data. The behavior is deliberate and covered by a test (`task-store.spec.ts:218-224`, "sends the newest known version as If-Match").
    - Why it matters: SPEC.md requires optimistic locking: updates need If-Match, and a conflict returns 409/412. Sending the newest known version turns stale edits into silent last-write-wins, which is the lost update that If-Match is meant to prevent. This affects every store that inherits `ListStore` (tasks, projects, contexts).
    - Suggested fix: send `etag(item.version)` from the caller's copy. Callers that want the latest version can pass `store.find(id) ?? item`. If the max was added for back-to-back mutations of the same item, handle that case explicitly instead, for example by chaining or serializing mutations per id, or by having callers re-read the item after the previous mutation settles. Then handle 412 in the store by refetching or reporting it, and change the test to expect a stale copy to produce the stale ETag.

## P3 Low

- [ ] **CR-2 | `ensureLoaded()` resolves before the data arrives when a load is already running**
    - File: `web/src/app/features/gtd/data/context-store.ts:27`, `web/src/app/features/gtd/data/project-store.ts:35`
    - Category: Correctness
    - Problem: `if (!this.loaded() && !this.loading()) await this.load({})`. If another component has already started the load, a second caller's `await ensureLoaded()` returns at once, while `items()` is still empty.
    - Why it matters: the doc says to "call ensureLoaded where they are needed", which suggests awaiting it means the data is there. A caller that awaits it and then reads `items()` (for example to pick a default context) sees an empty list, depending on timing.
    - Suggested fix: keep the in-flight load promise and return it from `ensureLoaded()`, or document that the method only starts loading and that callers must read the signals.

- [ ] **CR-3 | Every change event starts a full multi-page refetch, with no coalescing; the root ProjectStore refetches on every TASK event**
    - File: `web/src/app/features/gtd/data/list-store.ts:88-93`, `web/src/app/features/gtd/data/project-store.ts:31`
    - Category: Quality
    - Problem: each non-DELETED event (including the echo of the client's own change) calls `refresh()`, which fetches every page (`fetch` loops over cursors). A burst of N events, such as bulk processing or a restore, sends N full list requests in parallel. Only the last is applied, but all of them are sent. The app-wide `ProjectStore` also refetches all projects on every TASK event.
    - Why it matters: a single checkbox click costs at least two full list fetches (tasks and projects). Bursts multiply the load on the server and the network for no visible benefit.
    - Suggested fix: coalesce refreshes, for example by debouncing events per store for a short window or by skipping a new refresh while one is in flight and running one more afterwards. Optionally skip the refetch for an `UPDATED` event of an item whose version this store already holds from its own `accept`.

- [ ] **CR-4 | ProjectStore and ContextStore behavior is untested**
    - File: `web/src/app/features/gtd/data/project-store.ts:61-76`, `web/src/app/features/gtd/data/context-store.ts:27-59`
    - Category: Tests
    - Problem: only `TaskStore` and the shared `ListStore` paths reached through it have specs. Store-specific logic has no tests: the optimistic `setNextStep` (which recomputes `stalled`), the related-type handling in `ProjectStore` (a TASK `DELETED` must refetch, not drop a project with the same id), `ensureLoaded`, and the project `matches` filters.
    - Why it matters: criterion 4 is satisfied through TaskStore, but this untested logic is shared app-wide by the root singletons, and a regression in it would go unnoticed.
    - Suggested fix: add a small `project-store.spec.ts` with the same fake `Api`/`ChangeEventsService` setup. Cover `setNextStep` (optimistic `stalled` and rollback), a TASK `DELETED` event causing a refetch, and `ensureLoaded` not starting a second load.

## Review conclusion

Material issues found; fix before completing the task. CR-1 weakens the SPEC.md optimistic-locking contract and should be fixed, or recorded as a deliberate deviation with `tasks.py assume`. CR-2 to CR-4 are low priority. No acceptance-criterion evidence was recorded at review time (`tasks.py verify web-gtd-data` showed all 4 criteria unticked). The parent worker must record it before `done`.
