# Code Review

Date: 2026-10-21

Task: web-today-events - Today view: calendar events

Scope: `git diff 3ed76da7..b3dc10fb` (task commit b3dc10fb "web-today-events: today's calendar events above the tasks"): new `web/src/app/features/gtd/today/today-events.{ts,html,scss,spec.ts}`, wiring in `today-view.{ts,html,scss}`, `today-view.spec.ts`, doc comment in `today-sections.ts`.

Requirements:
- `tasks/web-today-events.md` acceptance criteria and recorded assumption
- SPEC.md §5.3 (Today view: "today's calendar events")
- docs/ARCHITECTURE.md (web conventions, i18n, Prettier/lint)
- AGENTS.md project notes (conventions, feature folders, test data fictitious)
- Reused contracts: `calendar/views/calendar-store.ts`, `calendar/views/occurrence-store.ts`, generated `OccurrenceResponse`, server `OccurrenceExpander` / `CalendarEventService.listOccurrences`

Git range:
- Branch: main
- HEAD: b3dc10fb
- Base/start: 3ed76da7c9f369ff3f2667f10c88807cbf19691e
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 3 P3

Notes on verification: the review read the current files in full plus the reused stores, the
generated model and the server range semantics. Shell access in this review session was limited to
read-only grep/inspection, so `npm run check` was not re-run here; `python tasks.py verify
web-today-events` shows both criteria still unrecorded, which the parent worker must record before
`done`.

Criterion 1 (events listed time-ordered above tasks): met in code. `<app-today-events>` is rendered
in `today-view.html:7` before the task sections; `compareEvents` orders all-day/day-spanning first,
then by start, then by title; only visible calendars are requested and shown. Covered by
`today-events.spec.ts` ("lists the events time-ordered, all-day first") and `today-view.spec.ts:375`
(DOM position check against the Overdue section).

Criterion 2 (Vitest tests cover the events section): component tests exist for the request
(range + visible calendar ids), ordering, empty state and occurrence-failure retry; see CR-3 for
uncovered branches.

Checked and found fine: out-of-order responses (OccurrenceStore/CalendarStore request sequence
numbers), loading/error/empty states and retry, no visible calendars (store short-circuits to an
empty list), SSE refresh via the component-scoped OccurrenceStore, i18n of all static strings,
OnPush + signals, no secrets or logging, fictitious test data. Cross-feature import of the calendar
stores has precedent in the codebase (`settings/gtd`, `files/preview`, `capture`) and avoids
duplicating the range/request-ordering logic.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Cancelled/tentative status is conveyed only visually**
    - File: `web/src/app/features/gtd/today/today-events.html:25`
    - Category: Quality
    - Problem: A cancelled occurrence is only marked by the `cancelled` class (strike-through and muted color, `today-events.scss:73-76`), a tentative one only by italics (`today-events.scss:69-71`). There is no text alternative, so screen readers announce a cancelled event exactly like a confirmed one.
    - Why it matters: The Today list is a primary "what is happening today" surface; a user relying on assistive technology may go to a cancelled meeting. Information conveyed by styling alone fails WCAG 1.3.1/1.4.1, and the project runs axe in its E2E tooling.
    - Suggested fix: Add a visually hidden, i18n'd status text (for example `<span class="visually-hidden" i18n="@@gtd.today.eventCancelled">Cancelled</span>` and a "Tentative" counterpart) inside the row for those statuses.

- [ ] **CR-2 | Adjacent-day all-day events appear as "All day" when browser and calendar zone differ**
    - File: `web/src/app/features/gtd/today/today-events.ts:29` (with `eventSpan` at line 36)
    - Category: Correctness
    - Problem: The range is the browser's local day, but the server selects all-day occurrences by the days the instant range touches in the calendar settings time zone (`server/.../calendar/recurrence/OccurrenceExpander.kt:19-39`, `CalendarEventService.kt:92`). If the two zones differ, the local-day range touches two days in the settings zone, so yesterday's or tomorrow's all-day events are returned, and `eventSpan` labels every `allDay` occurrence "All day" without checking `startDate`/`endDate` against the local date.
    - Why it matters: The recorded assumption chooses the browser's day, but this side effect (events of another day listed under Today) is not stated in it. Impact is limited to users whose browser zone differs from their calendar setting.
    - Suggested fix: Either filter all-day occurrences client-side to `startDate <= localDate() < endDate` (the `localDate()` helper Today already uses), or extend the assumption note so a human can decide knowingly.

- [ ] **CR-3 | Several branches of the events section are untested**
    - File: `web/src/app/features/gtd/today/today-events.spec.ts:91`
    - Category: Tests
    - Problem: The component tests do not exercise: the `from`/`until` time labels in the template (`today-events.html:34-43`, only `eventSpan` itself is unit-tested); the cancelled/tentative row classes; the calendar-list failure path (`failed` via `calendars.error()` at `today-events.ts:96` and `retry()` reloading calendars at line 110); and the client-side filter that drops occurrences of a calendar hidden after the request (`today-events.ts:80`).
    - Why it matters: These are the regression-prone branches (error recovery and day-crossing display); a wrong branch in `retry()` would leave the section stuck on an error with no working retry.
    - Suggested fix: Add tests for a failing `listCalendars` followed by a successful retry, for rendered "From"/"Until" labels and the `cancelled` class, and for an occurrence whose calendar is not visible.

## Review conclusion

No material issues found. The three P3 findings are optional improvements; the acceptance criteria
are met in code, pending the parent worker's recorded `npm run check` evidence.
