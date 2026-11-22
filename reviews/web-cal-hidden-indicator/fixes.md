# Fix Report

Date: 2026-11-22

Task: web-cal-hidden-indicator - Web calendar: day/week show which entries are scrolled out of view

Scope: current task; findings CR-1 to CR-4 of `reviews/web-cal-hidden-indicator/review.md`

## Fixed findings

### CR-1 — Chips are not recomputed when the period changes

Files changed:
- `web/src/app/features/calendar/views/calendar-view.ts`

Problem:
The effect that schedules a chip update tracked only `timedEntries()`, `viewType()` and `settings()`. Prev/next in the same view changes only `range`, so stale chips from the previous period could stay until the new occurrences arrived, or forever if the load failed.

Fix:
The scheduling effect now also reads `this.range()`. When the period changes, the chips are measured again against the new columns. Old items whose dates no longer match a shown column produce no chips.

Validation:
- `npx ng test --watch=false --include src/app/features/calendar/views/calendar-view.spec.ts --filter "linked event"` — Pass (4 passed)
- `npm --prefix web run check`: lint and e2e typecheck pass. For the unit tests, see the validation summary.
- No regression test added: a component test needs the setup in `calendar-view.spec.ts`, which the test-writer is editing right now. The test-writer should add a test like this: navigate, keep the occurrences unchanged, and check that the chips are recomputed or cleared.

### CR-2 — The singular count reads ungrammatically

Files changed:
- `web/src/app/features/calendar/views/calendar-view.ts`
- `web/src/locale/messages.xlf` (regenerated with `npm run i18n:extract`)
- `web/src/locale/messages.de.xlf` (translated by hand)

Problem:
"1 events earlier, show" / "1 frühere Termine, anzeigen".

Fix:
`chipLabel` now picks a separate message for count 1 (`calendar.hidden.aboveLabel.one`, `calendar.hidden.belowLabel.one`) and keeps the existing ids for more than 1, because `$localize` in TS has no ICU plurals. German: "1 früher: verborgenen Termin anzeigen" / "2 früher: verborgene Termine anzeigen", and the same with "später".

Validation:
- `npm --prefix web run i18n:extract` — Pass (only the four chip label units and line numbers changed)
- `npx ng build` — Pass (no missing-translation warnings)

### CR-3 — The wide chip's visible text is not part of its accessible name (WCAG 2.5.3)

Files changed:
- `web/src/app/features/calendar/views/calendar-view.ts`
- `web/src/locale/messages.xlf`, `web/src/locale/messages.de.xlf`

Problem:
The visible text "2 earlier" was not one piece of the label "2 events earlier, show".

Fix:
The labels now start with the visible wide text: "2 earlier: show hidden events" / "1 later: show hidden event". German: "2 früher: …" / "1 später: …". The narrow chip's visible "▲ 2" (count) is still contained. The template did not need a change.

Validation:
- `npx ng build` — Pass. Label strings checked in the failing test output: "1 earlier: show hidden event".

### CR-4 — The deferred chip update is not cancelled on destroy

Files changed:
- `web/src/app/features/calendar/views/calendar-view.ts`

Problem:
The effect's `setTimeout` was never cleared, so after destroy it could still queue a frame and run `updateChips` on the destroyed component.

Fix:
The timeout handle is kept in `chipTimeout`. A pending timeout is cleared before a new one is queued, and `ngOnDestroy` clears it next to the existing `cancelAnimationFrame`.

Validation:
- `npm --prefix web run lint` (part of `npm run check`) — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `npx ng test --watch=false --include src/app/features/gtd/task-detail/task-reminders.spec.ts --include src/app/features/calendar/views/calendar-view.spec.ts` — 102 passed, 9 failed:
  - 4 failures in the test-writer's uncommitted "hidden-entry chips" block assert the old label text ("1 events earlier, show"). These failures are expected after CR-2/CR-3. The test-writer must update the expected labels to "1 earlier: show hidden event" / "1 later: show hidden event" / "2 later: show hidden events".
  - 1 failure "linked event > shows an all-day linked event at its start date and refetches after a change" (3 occurrence loads instead of 2) passes alone (`--filter "linked event"`: 4 passed), so it depends on test order. The test-writer's new uncommitted block seems to leave state behind for later tests. The test-writer should check this.
  - 4 failures in `gtd/task-detail/task-reminders.spec.ts` are unrelated to this task. That file was not changed here; its last changes came from task web-task-new-project.

Full test suite:
- `npm --prefix web run check` — Fail at `test:ci`: 9 failed / 4945 passed, the same 9 failures as above. `api:check`, `lint` and `e2e:typecheck` passed.

Lint/type-check/build:
- `ng lint --max-warnings=0` — Pass
- `npx ng build` — Pass. Budget warnings for initial bundle and `calendar-view.scss` (4.61 kB > 4 kB). The scss warning comes from the chip styles in commit c0a1ad40d, not from this fix.

`.taskcheck`:
- Not run. It would fail on the same unit tests until the test-writer's spec is updated.

Remaining unresolved findings:
0

## Final status

Fixes completed, but validation still fails. All four findings are fixed in production code. The remaining unit test failures come from the test-writer's in-progress spec (old label text, a test that depends on order) and from the unrelated task-reminders spec.
