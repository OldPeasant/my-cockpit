# Code Review

Date: 2026-11-23

Task: web-gtd-tickler-view - Web: Tickler list view (what comes back when)

Scope: whole task scope `git diff 00b75d3f8..14ba71262` (final revision 14ba71262). It consists of the implementation commit 08de6ae64 (rebased form of 455998012, which differs from it only in `tasks/web-gtd-tickler-view.md`) and the test-only commit 14ba71262. The first review round covered 455998012; this round adds the delta `git diff 455998012..14ba71262 -- web`, which touches only `web/src/app/features/gtd/lists/tickler-page.spec.ts` and `web/src/locale/messages-de.spec.ts`. No production code changed after the first round. The working tree was clean at review time.

Requirements:
- tasks/web-gtd-tickler-view.md (acceptance criteria and recorded assumption)
- tasks/and-gtd-tickler-view.md (Android counterpart, Goal and assumptions)
- SPEC.md §5.1 / §5.3 (tickler hides tasks until their date; Tickler view)
- AGENTS.md project notes (feature folders, i18n, conventions)
- Server contract: `server/.../gtd/api/TaskController.kt` (`from`/`to` inclusive, `ChronoUnit.DAYS.between(from, to) < 366`, includes tasks still in a tickler, open tasks by default)
- Existing list pages: `scheduled-page.*`, `list-page.ts`, `data/task-store.ts`, `data/list-store.ts`

Git range:
- Branch: main
- HEAD: 14ba71262
- Base/start: 00b75d3f86c2b4e557ee9d730d94ec67a0dded5b
- Commits reviewed: 2 (08de6ae64 implementation, 14ba71262 tests and reports)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Verification notes (no finding)

- Criterion 1: `nav-sections.ts` adds `tickler` right after `scheduled` in the Tasks group; `gtd.routes.ts` mounts `/tickler` via `lists/tickler.routes.ts` with a localized title. `TicklerStore.fetch` reads ten consecutive inclusive yearly windows starting tomorrow, de-duplicates by id, and keeps only `isInTickler` tasks (open, `ticklerDate > today`); `compare` sorts by `ticklerDate`, then `position` (ties by id from `ListStore`). Tasks with tickler date today or earlier, done tasks and tasks found only by due date are excluded. Each row shows "Back tomorrow" / "Back on EEE, d MMM" (+ year outside the current year).
- Window sizes respect the server limit: the longest window (one containing 29 Feb) has `DAYS.between = 365 < 366`; 29 Feb start dates roll to 1 Mar and windows stay contiguous.
- Criterion 2: rows reuse `TaskRow` with `ListPage.open` (navigates to `/tasks/:id`) and `toggleDone` (optimistic complete with undo snackbar). The optimistic state drops the completed row (`matches` false), undo re-adds it. The page provides its own store and is recreated when the user comes back from the detail, and TASK change events refetch, so changes are reflected.
- Criterion 3: loading / empty ("Nothing in the tickler.") / error with "Try again" use the same markup and shared message ids as Scheduled; no separate offline state, as on the existing pages (recorded assumption).
- Criterion 4: only `GET /api/v1/tasks?from=&to=` is used; no server or OpenAPI change in the diff.
- Criterion 5: `gtd.tickler.title`, `gtd.tickler.backTomorrow`, `gtd.tickler.backOn`, `gtd.tickler.backOnYear`, `gtd.tickler.empty`, `nav.tickler` exist in `messages.xlf` and are translated in `messages.de.xlf`; the other xlf changes are only location line numbers from re-extraction.
- Out-of-order loads are guarded by `ListStore.requestSeq`; a failing window fails the whole load and shows the error with retry, so no partial list is presented as complete.
- No e2e locator collides with the new "Tickler" nav link (`web/e2e` only uses "Due tasks and ticklers" and per-list checks).

### Test delta 455998012..14ba71262

- The fake `listTasks` now mirrors the real contract more closely: cursor paging (`nextCursor`, `limit` = `TASK_PAGE_SIZE` = 200 as sent by `TaskStore.fetch`), a per-range failure (`failingFrom`), and a failing `completeTask`. The change-event mock routes `events$('TASK')` and `resync$` to per-test subjects, so the event tests exercise the real `ListStore` subscriptions rather than a stub.
- New cases and what they pin down: completion failure rolls the row back; paging is followed within one range (cursors `undefined`, `'2'`, `'4'`); inclusive range edges (end of year one, start of year two, last day of the horizon) are listed and the day after the horizon is not; a failure in a later range (window 5) shows the error and no partial list; a TASK `UPDATED` event triggers exactly `TICKLER_HORIZON_YEARS` new `listTasks` calls and re-sorts; a `DELETED` event drops the row without a reload; a resync reloads; with a fixed clock (Wed 10 Mar 2027) the first window is `2027-03-11..2028-03-10`, a task due back today is excluded, and the labels are "Back tomorrow", "Back on Fri, 12 Mar", "Back on Fri, 31 Dec", "Back on Wed, 5 Jan 2028" (weekdays checked by hand against the calendar).
- `messages-de.spec.ts` asserts the six Tickler message ids with their English sources and German targets, using the file's existing `english`/`german` maps.
- Flakiness: the fake API resolves only through microtasks (no timers), so the `refresh()` helper's `setTimeout` settles cover the ten sequential window requests and the paged requests. The fixed-date block fakes only `Date` (`toFake: ['Date']`), so `setTimeout`-based settling still works, and `afterEach` restores real timers before the outer `afterEach` removes the element. The `label()` helper compares against a module-level `today`, as in the first-round tests; only a run crossing midnight could disagree, which is the same pre-existing pattern and not new in this delta.
- Assertions check rendered rows, labels and request parameters (behavior and the server contract), not store internals. No existing test was removed, skipped or weakened; the only modified lines are the mock setup.
- Executed here: `npx ng test --watch=false --include src/app/features/gtd/lists/tickler-page.spec.ts --include src/locale/messages-de.spec.ts` -> 2 files, 34 tests passed; a second run of the tickler spec alone -> 21 passed. `npx eslint` and `npx prettier --check` on both spec files -> clean.
- If CR-1 is implemented with concurrent requests, the delta's tests stay valid (they count calls and check results, not ordering); only the earlier loading-state test with a single `release` handle would need to release every pending request, as already noted in CR-1.

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
