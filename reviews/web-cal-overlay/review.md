# Code Review

Date: 2026-10-21

Task: web-cal-overlay - Calendar task overlay and cross-links (round 2, final code)

Scope: committed changes of the task, `git diff 7d07e18c..43826282 -- . ':!tasks' ':!reviews'` (task commits ca18fd70e, 61ba28182, d114d91c1, 376aaec60, 45f087fc3; 4f47ef065 and 43826282c touch only `reviews/`). Reviewed revision: 43826282c5ae7d03357b5c2a295fd570abb7a3d4 (HEAD at review start). Uncommitted changes: none.

Requirements:
- tasks/web-cal-overlay.md (three acceptance criteria and the five ASSUMPTION notes)
- SPEC.md §6.3 (optional task overlay in day/week, event detail with linked items, "Create task from event" / "Create note for event" with pre-filled title + date)
- docs/ARCHITECTURE.md, docs/QUALITY.md, AGENTS.md project notes
- reviews/web-cal-overlay/review.md (round 1) and reviews/web-cal-overlay/fixes.md

Git range:
- Branch: main
- HEAD: 43826282c5ae7d03357b5c2a295fd570abb7a3d4
- Base/start: 7d07e18cf944c834f8017fe8377eef7a55acce08
- Commits reviewed: 7 (5 with code changes)

Commands run (read-only):
- `git log --oneline 7d07e18c..HEAD` -> 7 commits
- `git diff --stat 7d07e18c..HEAD -- . ':!tasks' ':!reviews'` -> 22 files, +2079/-20
- `git show 45f087fc3` (the round-1 fix commit), `git diff 7d07e18c..HEAD` for `calendar.routes.ts` and `calendar/edit/`
- full reads of `overlay/task-overlay.ts`, `views/calendar-view.ts` (component), `gtd/data/list-store.ts`, `gtd/data/task-store.ts`; test outlines of `task-overlay.spec.ts` and `event-links.spec.ts`
- `python tasks.py verify web-cal-overlay` -> all three criteria still unrecorded (the parent records them after the review)
- The reviewer ran no tests. The parent reported: focused web tests 347 passed, `npm run check` passed, `TaskDateRangeWebTests` passed.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Round-1 findings:
- CR-1 (task from event without date): resolved. `EventLinksService.createTask(occurrence, timeZone)` uses the shared `datedTitle` (`<title> (<yyyy-MM-dd>)`, plain title without a date), the panel passes `timeZone()`. It sets no due date, which a new ASSUMPTION note records. `event-links.spec.ts` covers the dated title, the notification text and the no-date case.
- CR-2 (`matchesTask` range branch untested): resolved. `task-store.spec.ts` has the "with a from/to range" cases (inclusive bounds, outside, future tickler, completed, half range).
- CR-3 (range message off by one): resolved. The message now reads "the range must span at most 366 days" and the rule is unchanged (`DAYS.between >= 366` rejected, so the longest allowed range is 366 inclusive days).
- CR-4 (refetch while overlay off): resolved. `OverlayTaskStore.refresh()` does nothing while `active` is false. `ListStore` sends `load`, change events (non-DELETED) and `resync$` through `this.refresh()` (`list-store.ts:87-93`, `112`), so with dynamic dispatch that one override covers every fetch path. `TaskOverlay` sets `active` before it calls `load` in `setEnabled(true)`, and in its constructor from the stored toggle. The `active = false` field initializer runs after the `ListStore` constructor, and that constructor only subscribes, so no event fires before it. `calendar-view.ts:66` provides `OverlayTaskStore` per view instance, and nothing in `calendar/` still injects the plain `TaskStore`. The new suite "TaskOverlay with its OverlayTaskStore" uses the real store with fake `Api`/`ChangeEventsService`, and it fails when the gate is removed (fixes.md).

Checked again without findings:
- Toggling off while a request runs: the answer still lands, `entries()` returns `[]` while off, and `loading` ends through the request sequence.
- Turning on after the range changed while off: `show()` updates the range without fetching, then `load` with a new query clears the old items first (`list-store.ts:106-110`).
- The retry button only shows while enabled, so `refresh()` is not gated when it is used.
- `/calendar/events/:id` redirect, the event-dialog links panel (edit mode only), and the dialog spec's `LinksService` stub.
- Server range validation and the generated client: unchanged since round 1 apart from the message.

Observation, not a finding: `TaskOverlay` keeps the on/off state in `enabledState` and mirrors it into `OverlayTaskStore.active` at both places that write it (constructor and `setEnabled`). The overlay is the only writer and the tests cover both paths. Today this is consistent, but a future writer would have to keep both in sync.

Note for the parent: criterion 3 (Playwright overlay spec) was last run before the round-1 fixes. The fixes changed `task-overlay.ts`/`calendar-view.ts`, which the e2e spec exercises. Record its evidence from a run on this revision.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
