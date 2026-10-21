# Fix Report

Date: 2026-10-21

Task: web-today-events - Today view: calendar events

Scope: current task; findings CR-1, CR-2, CR-3 from `reviews/web-today-events/review.md` and the
midnight-to-midnight observation from `reviews/web-today-events/tests.md`, as approved by the
parent worker (base 3ed76da7c9f369ff3f2667f10c88807cbf19691e, HEAD b383dbf3).

## Fixed findings

### CR-1 — Cancelled/tentative status is conveyed only visually

Files changed:
- `web/src/app/features/gtd/today/today-events.html`
- `web/src/app/features/gtd/today/today-events.scss`
- `web/src/app/features/gtd/today/today-events.spec.ts`

Problem:
Cancelled and tentative occurrences were marked only by styling (strike-through, italics), so screen
readers announced them like confirmed events.

Fix:
After the title, the row now has visually hidden, i18n'd status text: "Cancelled"
(`@@gtd.today.eventCancelled`) or "Tentative" (`@@gtd.today.eventTentative`). The project has no
global visually-hidden utility (`src/styles.scss` defines none, and `cdk-visually-hidden` is not
used). Instead, each component defines its own local `.visually-hidden` rule (`inbox-page.scss`,
`mail-list-page.scss`, `task-detail/controls.scss`). The same rule was added to
`today-events.scss`. New test: "tells screen readers that an event is cancelled or tentative".

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/gtd/today/*.spec.ts'` — Pass

### CR-2 — Adjacent-day all-day events appear as "All day" when browser and calendar zone differ

Files changed:
- `web/src/app/features/gtd/today/today-events.ts`
- `web/src/app/features/gtd/today/today-events.spec.ts`

Problem:
The server picks all-day occurrences in the calendar settings time zone. When the browser zone differs,
the local-day range can also return yesterday's or tomorrow's all-day events, and they were shown as
today's.

Fix:
New exported `isOnDate(occurrence, date)`. It keeps an all-day occurrence only when
`startDate <= date < endDate` (endDate exclusive). A missing `endDate` counts as one day. Timed
occurrences, and all-day ones without `startDate`, always pass. The component computes `now` once,
derives both `range` (`todayRange(now)`) and `today` (`localDate(now)` from `../data`) from it, and
filters `events()` with `isOnDate(occurrence, this.today)`. New tests: `isOnDate` (3 cases), plus
the component test "leaves out all-day events of yesterday and tomorrow the server returns for
another zone".

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/gtd/today/*.spec.ts'` — Pass

### tests.md observation — Timed event covering exactly the whole local day labelled "12:00 AM – 12:00 AM"

Files changed:
- `web/src/app/features/gtd/today/today-events.ts`
- `web/src/app/features/gtd/today/today-events.spec.ts`

Problem:
A timed occurrence from local midnight to the next local midnight was classified as `timed` and
shown as "12:00 AM – 12:00 AM".

Fix:
`eventSpan` now returns `'allDay'` when `start <= range.from && end >= range.to`. This replaces the
strict "starts before and ends after" check, which it includes. The `until`/`from`/`timed` rules are
unchanged. The parent approved this behavior change, so the existing test "treats an event exactly
from midnight to midnight as inside today" was replaced with "treats a timed event covering the whole
day exactly as all day". The new test covers exact, start-at-midnight and end-at-midnight cases. Also
added: "counts an event starting at midnight but ending before the next one as inside today" (the
boundaries stay `timed`), and the component test "labels a timed event covering the whole day as all
day".

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/gtd/today/*.spec.ts'` — Pass

## Not fixed

### CR-3 — Several branches of the events section are untested

Status:
Invalid (already resolved)

Reason:
The test-writer's tests already cover the named branches: "labels the part of an event inside
today", "marks cancelled and tentative events", "offers a retry when the calendars could not be
loaded", and "never shows occurrences of a hidden calendar". The parent worker confirmed that no
code change is needed.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/gtd/today/*.spec.ts'` — Pass (2 files, 53 tests)
- `cd web && npx eslint src/app/features/gtd/today` — Pass (no output)
- `cd web && npx prettier --check src/app/features/gtd/today` — Pass

Full test suite:
- `cd web && npm run check` — Pass (107 files, 2129 tests; build printed the existing initial-bundle budget warning, 657.07 kB > 500 kB, not caused by this task)

Lint/type-check/build:
- Included in `npm run check` (api:check, ng lint, e2e typecheck, tests, ng build) — Pass

`.taskcheck`:
- Pass. `sh scripts/check.sh` ended with "== all checks passed". An earlier run reported 1 Vitest
  error after 302 s of test time, against about 40 s for the same web suite in `npm run check` just
  before. Nothing changed between the runs, and the immediate re-run passed fully. This looks like a
  load-related timeout flake. The error details were not captured.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
