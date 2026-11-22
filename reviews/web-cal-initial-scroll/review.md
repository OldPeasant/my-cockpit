# Code Review

Date: 2026-11-22

Task: web-cal-initial-scroll - Web calendar: day/week grid opens scrolled to the earliest entry

Scope: committed changes of the task, `git diff ca2b754..02d35c3` (production code and tests under
`web/src/app/features/calendar/views/`). Uncommitted working-tree changes to `calendar-view.spec.ts` and
`occurrence-store.spec.ts` (apparently the test-writer's, in progress) were noted but not reviewed as part of the scope.

Requirements:
- `tasks/web-cal-initial-scroll.md` acceptance criteria 1-6 and recorded assumptions #1, #2
- Related recorded assumptions of `and-cal-initial-scroll` (#1 "full hour before now" = current hour floored, #2 Today on the
  shown period is not a period change)
- `AGENTS.md` project notes, `docs/ARCHITECTURE.md` conventions

Git range:
- Branch: main
- HEAD: 02d35c337287a1d6e4da801a49d25806981b57af
- Base/start: ca2b75431396501e11b43e4d7c262a20cf2bdb3e
- Commits reviewed: 4 (2 code commits b6e721bd9, 02d35c337; 2 `assume` task-file commits)

Summary: 0 P0, 0 P1, 1 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Period-change vs. refresh wiring and the new `OccurrenceStore.range` signal have no committed tests**
    - File: `web/src/app/features/calendar/views/calendar-view.ts:225` (scroll effect), `:427` (`applyInitialScroll`),
      `web/src/app/features/calendar/views/occurrence-store.ts:46` (`range`)
    - Category: Tests
    - Problem: The committed tests cover only the pure function `initialScrollTime`. The behavior criterion 4 is about
      (scroll once the period's occurrences are loaded, not on a background refresh of the same period, not for a stale
      answer of an older period, nothing in month/agenda view, hidden calendars ignored) lives in the effect plus
      `scrollPending` plus `OccurrenceStore.range`, and none of it is tested in the reviewed commits. Criterion 6 asks for
      unit tests of the new behavior.
    - Why it matters: "a refresh must not move the scroll" is exactly the kind of rule that breaks silently, e.g. if
      `accept` later sets a fresh range object or the effect starts reading `items`.
    - Suggested fix: Commit component tests (spy on `getApi().scrollToTime`) for: first load scrolls; `refresh()` of the
      same range does not; a new period scrolls only after its answer; a stale older answer does not trigger it;
      month/agenda never scroll. Add store tests for `range`: undefined before the first answer, set together with
      items, unchanged by a failed load or a slower stale answer. The uncommitted spec changes in the working tree seem to
      cover these cases; review and commit them.

## P3 Low

- [ ] **CR-2 | After a failed load of a new period, a later background refresh moves the scroll**
    - File: `web/src/app/features/calendar/views/calendar-view.ts:229-230`, `web/src/app/features/calendar/views/occurrence-store.ts:86`
    - Category: Correctness
    - Problem: If the request for a newly shown period fails, `scrollPending` stays `true`. The next successful answer
      for that period then applies the initial scroll, even when it comes from an SSE change notification or resync
      rather than the user's Retry, and the user may have scrolled the grid in the meantime.
    - Why it matters: This is an edge case of criterion 4 ("a background refresh/reload of the same period does not move
      the scroll position"). It only happens after an error, so the impact is small.
    - Suggested fix: Accept it and say so in an `assume` line ("the first successful answer for a period counts as its
      load"), or clear `scrollPending` when the user scrolls the grid while it is pending.

- [ ] **CR-3 | The `HH:00:00` duration formatting is duplicated**
    - File: `web/src/app/features/calendar/views/calendar-view.ts:144`, `web/src/app/features/calendar/views/initial-scroll.ts:38`
    - Category: Quality
    - Problem: `calendar-view.ts` rebuilds the `HH:00:00` string from `DEFAULT_SCROLL_HOUR` with the same `padStart`
      expression that `initial-scroll.ts` uses internally.
    - Why it matters: The format has to stay the same in two places. This is minor.
    - Suggested fix: Export a constant such as `DEFAULT_SCROLL_TIME = '07:00:00'`, or a small `hourDuration(h)` helper,
      from `initial-scroll.ts` and use it in both places.

## Notes (not findings)

- Criteria 1-3 are implemented correctly in `initialScrollTime`. It floors to the full hour and uses the zone's wall
  clock. It ignores starts outside the shown days, which covers entries that continue from an earlier day. All-day
  entries and overlay tasks without a time are filtered out by the caller (`allDay === false`, string start). The hour
  is clamped to 0-23, and FullCalendar clamps the rest. Without an entry it falls back to the current hour when today is
  shown, else 07:00. The unit tests in `initial-scroll.spec.ts` cover all of these, including a zone/UTC date boundary.
- Out-of-order answers are handled: `OccurrenceStore.range` is set only together with accepted items, so the newest
  request wins, and the effect waits until the loaded range equals the shown range. A refresh sets the same range object,
  so the effect does not fire again, and `scrollPending` is already cleared anyway.
- None of the criteria has recorded evidence yet. Criterion 4 is about real scrolling. jsdom component tests
  only show that `scrollToTime` is called, not that the call wins over FullCalendar's own `scrollTime` reset, which the
  code works around with `setTimeout`. The evidence for criterion 4 should say this, or name a browser check that
  was actually run.

## Review conclusion

Material issues found; fix before completing the task. CR-1 needs the component and store tests committed. CR-2 and
CR-3 are optional.
