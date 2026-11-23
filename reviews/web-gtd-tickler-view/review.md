# Code Review

Date: 2026-11-23

Task: web-gtd-tickler-view - Web: Tickler list view (what comes back when)

Scope: implementation commit 455998012 against base 00b75d3f8 (`git diff 00b75d3f8..455998012`); on the current branch it is the rebased commit 08de6ae64, which differs from 455998012 only in `tasks/web-gtd-tickler-view.md` (the assumption line that 455998012 predates). Uncommitted edits to `tickler-page.spec.ts` by the parallel test-writer are not part of this review.

Requirements:
- tasks/web-gtd-tickler-view.md (acceptance criteria and recorded assumption)
- tasks/and-gtd-tickler-view.md (Android counterpart, Goal and assumptions)
- SPEC.md §5.1 / §5.3 (tickler hides tasks until their date; Tickler view)
- AGENTS.md project notes (feature folders, i18n, conventions)
- Server contract: `server/.../gtd/api/TaskController.kt` (`from`/`to` inclusive, `ChronoUnit.DAYS.between(from, to) < 366`, includes tasks still in a tickler, open tasks by default)
- Existing list pages: `scheduled-page.*`, `list-page.ts`, `data/task-store.ts`, `data/list-store.ts`

Git range:
- Branch: main
- HEAD: 08de6ae64
- Base/start: 00b75d3f86c2b4e557ee9d730d94ec67a0dded5b
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Verification notes (no finding)

- Criterion 1: `nav-sections.ts` adds `tickler` right after `scheduled` in the Tasks group; `gtd.routes.ts` mounts `/tickler` via `lists/tickler.routes.ts` with a localized title. `TicklerStore.fetch` reads ten consecutive inclusive yearly windows starting tomorrow, de-duplicates by id, and keeps only `isInTickler` tasks (open, `ticklerDate > today`); `compare` sorts by `ticklerDate`, then `position` (ties by id from `ListStore`). Tasks with tickler date today or earlier, done tasks and tasks found only by due date are excluded. Each row shows "Back tomorrow" / "Back on EEE, d MMM" (+ year outside the current year).
- Window sizes respect the server limit: the longest window (one containing 29 Feb) has `DAYS.between = 365 < 366`; 29 Feb start dates roll to 1 Mar and windows stay contiguous.
- Criterion 2: rows reuse `TaskRow` with `ListPage.open` (navigates to `/tasks/:id`) and `toggleDone` (optimistic complete with undo snackbar). The optimistic state drops the completed row (`matches` false), undo re-adds it. The page provides its own store and is recreated when the user comes back from the detail, and TASK change events refetch, so changes are reflected.
- Criterion 3: loading / empty ("Nothing in the tickler.") / error with "Try again" use the same markup and shared message ids as Scheduled; no separate offline state, as on the existing pages (recorded assumption).
- Criterion 4: only `GET /api/v1/tasks?from=&to=` is used; no server or OpenAPI change in the diff.
- Criterion 5: `gtd.tickler.title`, `gtd.tickler.backTomorrow`, `gtd.tickler.backOn`, `gtd.tickler.backOnYear`, `gtd.tickler.empty`, `nav.tickler` exist in `messages.xlf` and are translated in `messages.de.xlf`; the other xlf changes are only location line numbers from re-extraction. The committed spec covers navigation placement, the request windows, sorting and return-date labels, exclusion rules, de-duplication, empty/loading/error+retry, opening the detail, re-render after a change, and completing a row. Running `npm run check` is the parent's evidence to record.
- Out-of-order loads are guarded by `ListStore.requestSeq`; a failing window fails the whole load and shows the error with retry, so no partial list is presented as complete.
- No e2e locator collides with the new "Tickler" nav link (`web/e2e` only uses "Due tasks and ticklers" and per-list checks).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Ten yearly windows are fetched one after another on every load and change event**
    - File: `web/src/app/features/gtd/lists/tickler-store.ts:19`
    - Category: Quality
    - Problem: `fetch` awaits each of the ten `super.fetch` calls inside the loop, so the first paint and every refetch (each TASK change event or SSE resync calls `refresh()`) waits for ten sequential round trips. The windows are independent.
    - Why it matters: load latency is about ten times that of the other list pages, noticeable on a slow or mobile connection, and overlapping refreshes (several change events in a row) keep long chains running.
    - Suggested fix: issue the windows concurrently, e.g. `await Promise.all(ticklerWindows(today).map(([from, to]) => super.fetch({ ...query, from, to })))`, then merge by id as now. The existing loading-state test with per-request release would need to release all pending requests.

- [ ] **CR-2 | German return date reads "Mo., 14 Dez." instead of "Mo., 14. Dez."**
    - File: `web/src/app/features/gtd/lists/tickler-page.html:23`
    - Category: Quality
    - Problem: the fixed DatePipe pattern `'EEE, d MMM'` (and `'EEE, d MMM y'` on line 27) is used for every locale, so the German build renders the day number without the ordinal dot. The Android counterpart uses the locale's best pattern and shows "Zurück am Mo., 14. Dez." (tasks/and-gtd-tickler-view.md assumption), and this task asks for the same as Android.
    - Why it matters: minor localization inconsistency between the two clients for the same feature. The other web list pages also use fixed patterns, so this is not a regression of an existing convention.
    - Suggested fix: format with a locale-aware skeleton, e.g. `Intl.DateTimeFormat(locale, { weekday: 'short', day: 'numeric', month: 'short' })` (plus `year: 'numeric'` outside the current year) through a small helper or pipe using `LOCALE_ID`, or accept the deviation and record it with `tasks.py assume`.

## Review conclusion

No material issues found.
