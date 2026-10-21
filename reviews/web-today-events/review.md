# Code Review

Date: 2026-10-21

Task: web-today-events - Today view: calendar events

Scope: re-review of the final revision, `git diff 3ed76da7..e42e6ed7 -- web`. Commits: 5c72edf0 (implementation), 6c7efdba (tests), 80efa0cb (fixes for the first review's CR-1, CR-2 and the whole-day span), dab36870 (doc-comment rewrap resolving this review's CR-1), e42e6ed7 (test-only: one more component test in `today-events.spec.ts`, plus review and test reports). Files: `web/src/app/features/gtd/today/today-events.{ts,html,scss,spec.ts}`, `today-view.{ts,html,scss,spec.ts}`, `today-sections.ts`. Heartbeat/extend commits in the range touch only `tasks/` and are outside the review.

Requirements:
- `tasks/web-today-events.md` acceptance criteria and recorded assumption
- SPEC.md §5.3 (Today view: "today's calendar events")
- docs/ARCHITECTURE.md and AGENTS.md project notes (web conventions, i18n, lint, feature folders, fictitious test data)
- First review (CR-1..CR-3) and `reviews/web-today-events/fixes.md`
- Reused contracts: `calendar/views/calendar-store.ts`, `calendar/views/occurrence-store.ts`, generated `OccurrenceResponse`, server `CalendarDtos.kt` (exclusive `endDate`) and `OccurrenceExpander.kt` (range selection)

Git range:
- Branch: main
- HEAD: e42e6ed7c1580728b49d6e1d5c4930478b9af7d7
- Base/start: 3ed76da7c9f369ff3f2667f10c88807cbf19691e
- Commits reviewed: 5 (task commits; the remaining commits in the range are heartbeat/extend task-state commits)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Verification of previous findings

First review:

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

Second review:

- CR-1 (doc comment line over 100 columns in `today-sections.ts:11`): fixed in dab36870. The commit
  only re-wraps the JSDoc of `TodaySection`, with the wording unchanged. No line in
  `today-sections.ts` exceeds 100 characters now. Comment-only change; no behavior affected.

## Test-only commit e42e6ed7

The only `web/` change is one new component test, "picks all-day events by the browser's local
date, also just after and before midnight" (`today-events.spec.ts:319-337`). It fixes the system
time at 00:30 and 23:30 local time on 2026-10-20. It then checks that only the all-day occurrences
covering that local date are listed: Birthday (no `endDate`, one day), Holiday and Vacation
(multi-day). Yesterday's and tomorrow's occurrences are left out, and the result is title-ordered.

- The test asserts rendered behavior, not implementation details.
- It exercises the real `isOnDate` and `localDate` path through the component, with no extra
  mocking.
- It destroys each fixture before the next render.
- It uses fictitious data.

Reusing the root `CalendarStore` across the two renders in one TestBed does not affect the
assertion, since the calendars are the same. No production code changed, and no existing test was
weakened or removed. No issues found.

## Acceptance criteria

1. Today lists today's events, time-ordered, above tasks: met in code. `<app-today-events>` is
   rendered at `today-view.html:7`, before the task sections. `compareEvents` puts all-day and
   day-spanning events first, then events running since yesterday, then sorts by start and then by
   title. Covered by `today-events.spec.ts` (ordering tests) and `today-view.spec.ts` ("shows today's
   calendar events above the task sections", DOM position against the first task section).
2. Vitest tests cover the events section: met. They cover the request (range and visible ids),
   ordering, labels, status, color and name, the hidden-calendar filter, local-date selection of
   all-day events, loading, empty and error states with retry for both stores, reloading on a
   visibility change, out-of-order answers, SSE refresh and resync, and a failed refresh keeping
   stale data. `npm run check` passing was not re-run in this review session, because shell access
   was limited to read-only inspection. The parent worker must record the `npm run check` result for
   this final revision with `tasks.py verify`.

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

None.

## Review conclusion

No material issues found. All findings from both reviews are resolved in revision
e42e6ed7c1580728b49d6e1d5c4930478b9af7d7. The parent worker still has to record the
acceptance-criteria evidence (`npm run check` result on this revision) before `done`.
