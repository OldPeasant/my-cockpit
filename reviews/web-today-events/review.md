# Code Review

Date: 2026-10-21

Task: web-today-events - Today view: calendar events

Scope: re-review of the final revision, `git diff 3ed76da7..80efa0cb -- web` (commits 5c72edf0 implementation, 6c7efdba tests, 80efa0cb fixes for CR-1, CR-2 and the whole-day span). Files: `web/src/app/features/gtd/today/today-events.{ts,html,scss,spec.ts}`, `today-view.{ts,html,scss,spec.ts}`, `today-sections.ts`. Heartbeat/extend commits in the range touch only `tasks/` and are not part of the review.

Requirements:
- `tasks/web-today-events.md` acceptance criteria and recorded assumption
- SPEC.md §5.3 (Today view: "today's calendar events")
- docs/ARCHITECTURE.md and AGENTS.md project notes (web conventions, i18n, lint, feature folders, fictitious test data)
- Previous review `reviews/web-today-events/review.md` (CR-1..CR-3) and `reviews/web-today-events/fixes.md`
- Reused contracts: `calendar/views/calendar-store.ts`, `calendar/views/occurrence-store.ts`, generated `OccurrenceResponse`, server `CalendarDtos.kt` (exclusive `endDate`) and `OccurrenceExpander.kt` (range selection)

Git range:
- Branch: main
- HEAD: 80efa0cbe0eb370828f02d723fc1204f9a0f055d
- Base/start: 3ed76da7c9f369ff3f2667f10c88807cbf19691e
- Commits reviewed: 3 (task commits; the remaining commits in the range are heartbeat/extend task-state commits)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Verification of previous findings

- CR-1 (status shown only by styling): fixed. `today-events.html:52-56` adds visually hidden, i18n'd
  "Cancelled"/"Tentative" text after the title. The local `.visually-hidden` rule
  (`today-events.scss:79-86`) follows the project's per-component pattern. Covered by the test "tells
  screen readers that an event is cancelled or tentative".
- CR-2 (adjacent-day all-day events): fixed. `isOnDate` (`today-events.ts:41-45`) keeps an all-day
  occurrence only when `startDate <= today < endDate`. It defaults a missing `endDate` to one day,
  which matches the server's exclusive `endDate` (`CalendarDtos.kt:128`,
  `OccurrenceExpander.kt:211`). `today` and `range` come from the same `now`
  (`today-events.ts:87-89`), so they cannot disagree. Covered by `isOnDate` unit tests and the
  component tests "leaves out all-day events of yesterday and tomorrow..." and "picks all-day events
  by the browser's local date, also just after and before midnight".
- CR-3 (untested branches): resolved by the test-writer's tests. They cover the From/Until/range
  labels (`today-events.spec.ts:290`), the cancelled/tentative classes (`:346`), the calendar-list
  failure and retry (`:525`), and the hidden-calendar filter (`:397`).
- Whole-day span change: `eventSpan` now returns `allDay` when `start <= from && end >= to`
  (`today-events.ts:57`). The boundary cases (exact midnight to midnight, starting at midnight but
  ending earlier, crossing by one minute) are tested at `today-events.spec.ts:57-83`.

## Acceptance criteria

1. Today lists today's events, time-ordered, above tasks: met in code. `<app-today-events>` is
   rendered at `today-view.html:7`, before the task sections. `compareEvents` puts all-day and
   day-spanning events first, then events running since yesterday, then sorts by start and then by
   title. Covered by `today-events.spec.ts` (ordering tests) and `today-view.spec.ts` ("shows today's
   calendar events above the task sections", DOM position against the first task section).
2. Vitest tests cover the events section: met. They cover the request (range and visible ids),
   ordering, labels, status, color and name, the hidden-calendar filter, loading, empty and error
   states with retry for both stores, reloading on a visibility change, out-of-order answers, SSE
   refresh and resync, and a failed refresh keeping stale data. `npm run check` passing was not
   re-run in this review session, because shell access was limited to read-only inspection.
   `fixes.md` reports `npm run check` passing (107 files, 2129 tests) and `.taskcheck` passing after
   one re-run of a flaky timeout. The parent worker must record this evidence with `tasks.py verify`.
   The task file shows both criteria still unchecked.

## Also checked and found fine

- Out-of-order answers: request sequence numbers in both stores. The effect reloads only when the
  sorted visible id string changes.
- Loading, error and empty states: `failed` covers both stores before the first success. `retry()`
  reloads calendars or refreshes occurrences, whichever failed. A later refresh failure keeps the
  data on screen.
- No visible calendars: the store short-circuits to an empty list without a request.
- Server range semantics: when the browser zone and the settings zone differ by less than 24 h, the
  local-day range always overlaps the local date in the settings zone. So today's all-day events are
  returned, and the client filter removes the neighbouring days.
- Conventions: OnPush and signals, all static strings i18n'd (no committed translation files need
  updating), changes stay inside the task's `touches`, no logging or secrets, fictitious test data.
  The cross-feature reuse of the calendar stores has precedent and avoids duplicating the
  request-ordering logic.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Reworded doc comment line exceeds the file's 100-column wrapping**
    - File: `web/src/app/features/gtd/today/today-sections.ts:11`
    - Category: Quality
    - Problem: The edited JSDoc line is 125 characters long. The rest of the comment, and the other files in the feature, wrap at 100 columns. Prettier does not reflow comments, so `npm run check` does not catch it.
    - Why it matters: Small readability inconsistency in a shared extension-point file that other features read when they add Today sections.
    - Suggested fix: Re-wrap the comment at 100 columns, for example by moving "The component renders the whole section, heading included;" to the next line.

## Review conclusion

No material issues found. CR-1..CR-3 from the previous review are resolved. The only remaining item
is an optional P3 cleanup. The parent worker still has to record the acceptance-criteria evidence
(`npm run check` result) before `done`.
