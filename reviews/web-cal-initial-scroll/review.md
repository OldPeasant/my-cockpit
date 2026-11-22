# Code Review

Date: 2026-11-22

Task: web-cal-initial-scroll - Web calendar: day/week grid opens scrolled to the earliest entry

Scope: re-review of the final revision. All committed changes of the task, `git diff 1805add58..e8bb80ed7`
(production code and tests under `web/src/app/features/calendar/views/`, plus the review and test reports). The first
review covered the production code in b6e721bd9 and 02d35c337. e8bb80ed7 adds tests only:
`calendar-view.spec.ts` (`describe('initial scroll position')`, 10 tests) and `occurrence-store.spec.ts`
(`describe('range')`, 6 tests). `git diff --stat 02d35c337 e8bb80ed7` shows no change to `calendar-view.ts`,
`occurrence-store.ts` or `initial-scroll.ts`. The working tree was clean.

Requirements:
- `tasks/web-cal-initial-scroll.md` acceptance criteria 1-6 and recorded assumptions #1, #2
- Related recorded assumptions of `and-cal-initial-scroll` (#1 "full hour before now" = current hour floored, #2 Today on the
  shown period is not a period change)
- `AGENTS.md` project notes, `docs/ARCHITECTURE.md` conventions

Git range:
- Branch: main
- HEAD: e8bb80ed7f0497d34c89b241f3db962185fe4012
- Base/start: 1805add582fb6cb6a38f522e04956cd02a4ad601 (`git merge-base HEAD origin/main`; the claim commit was ca2b75431)
- Commits reviewed: 3 code/test commits (b6e721bd9, 02d35c337, e8bb80ed7), plus the task's `assume` commits

Summary: 0 P0, 0 P1, 0 P2, 2 P3 (CR-1 resolved)

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [x] **CR-1 | Period-change vs. refresh wiring and the new `OccurrenceStore.range` signal have no committed tests**
  (RESOLVED in e8bb80ed7)
    - File: `web/src/app/features/calendar/views/calendar-view.ts:225` (scroll effect), `:427` (`applyInitialScroll`),
      `web/src/app/features/calendar/views/occurrence-store.ts:46` (`range`)
    - Category: Tests
    - Problem (original): Only the pure function `initialScrollTime` was tested. The criterion 4 wiring (effect,
      `scrollPending`, `OccurrenceStore.range`) had no tests.
    - Resolution: e8bb80ed7 adds component tests that spy on `getApi().scrollToTime`. They check that:
      - the first load scrolls only after the occurrences arrive (`14:00:00` for 14:20);
      - a resync and an SSE change of the same period do not scroll again;
      - a new period does not scroll while the old period's items are still shown, and does scroll once its answer
        arrives (with a DST case);
      - a view switch to a different range (day) waits for its own answer;
      - a past week with only all-day and continuing entries falls back to 07:00;
      - hidden calendars are ignored;
      - month and agenda views never scroll;
      - a stale answer for an older period does not scroll;
      - a pending scroll timeout does nothing after destroy.

      The store tests cover `range`: it is undefined before the first answer and set together with the items, kept
      while the next range loads, not overwritten by a slower stale answer, set when there are no calendars, kept when
      a load fails, and unchanged by a refresh. The refresh tests really issue a new request:
      `OccurrenceStore` subscribes to `resync$` and `events$`, so `callsOf(...).at(-1)` resolves a new call. I ran the
      three specs, and they pass (see Notes). This covers the behavior part of criterion 6 and resolves the finding.

## P3 Low

- [ ] **CR-2 | After a failed load of a new period, a later background refresh moves the scroll** (OPEN, optional)
    - File: `web/src/app/features/calendar/views/calendar-view.ts:229-230`, `web/src/app/features/calendar/views/occurrence-store.ts:86`
    - Category: Correctness
    - Problem: If the request for a newly shown period fails, `scrollPending` stays `true`. The next successful answer
      for that period then applies the initial scroll, even when it comes from an SSE change notification or resync
      rather than the user's Retry. The user may have scrolled the grid in the meantime. e8bb80ed7 changes no
      production code, so this is still the case, and no test covers it.
    - Why it matters: This is an edge case of criterion 4 ("a background refresh/reload of the same period does not move
      the scroll position"). It only happens after an error, so the impact is small.
    - Suggested fix: Accept it and record that with `assume` ("the first successful answer for a period counts as its
      load"), or clear `scrollPending` when the user scrolls the grid while a scroll is pending.

- [ ] **CR-3 | The `HH:00:00` duration formatting is duplicated** (OPEN, optional)
    - File: `web/src/app/features/calendar/views/calendar-view.ts:144`, `web/src/app/features/calendar/views/initial-scroll.ts:38`
    - Category: Quality
    - Problem: `calendar-view.ts` builds the `HH:00:00` string from `DEFAULT_SCROLL_HOUR` with the same `padStart`
      expression that `initial-scroll.ts` uses internally.
    - Why it matters: The format has to stay the same in two places. This is minor.
    - Suggested fix: Export `DEFAULT_SCROLL_TIME = '07:00:00'`, or a small `hourDuration(h)` helper, from
      `initial-scroll.ts` and use it in both places.

## Notes (not findings)

- Criteria 1-3 are implemented correctly in `initialScrollTime`, and `initial-scroll.spec.ts` covers them (unchanged
  since the first review). The new component tests also check them end to end (14:20 -> 14:00, the 07:00 fallback,
  all-day and continuing entries ignored).
- Focused run by the reviewer:
  `cd web && npx ng test --watch=false --include .../calendar-view.spec.ts --include .../occurrence-store.spec.ts --include .../initial-scroll.spec.ts`
  -> 3 files, 106 tests passed. I did not run the full `npm run check` or `sh scripts/check.sh` (the second half of
  criterion 6). The parent worker must run them and record the result.
- `python tasks.py verify web-cal-initial-scroll` still shows all six criteria as unverified. Criterion 4 is about
  real scrolling. The jsdom tests only show that `scrollToTime` is called with the right value after the
  `setTimeout`, not that the call wins over FullCalendar's own `scrollTime` reset in a browser. The evidence for
  criterion 4 should say this, or name a browser check that was actually run.
- The test "Today" on the same period (no new range, so no scroll, per `and-cal-initial-scroll` assumption #2) is not
  tested on its own. It follows from the same range-equality path that the refresh test checks, so it is not a
  finding.

## Review conclusion

No material issues found. CR-1 is resolved by e8bb80ed7. CR-2 and CR-3 are optional P3 items and do not block
completion. The parent worker still needs to record the criterion evidence and run the full checks.
