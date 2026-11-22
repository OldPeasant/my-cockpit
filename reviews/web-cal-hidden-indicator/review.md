# Code Review

Date: 2026-11-22

Task: web-cal-hidden-indicator - Web calendar: day/week show which entries are scrolled out of view

Scope: final revision 3f2853bf94badf83d1471c2773d99ecd52efe0fc. This is a re-review. The only code change since the previous review (af6a82f22) is commit 3f2853bf9, a test-only change in `web/src/app/features/calendar/views/calendar-view.spec.ts`. I also re-checked the whole task diff against the acceptance criteria: `git diff f8cec873d 3f2853bf9 -- web/` (10 files: calendar-view html/scss/ts/spec, hidden-chips, hidden-entries, both xlf files). HEAD equals 3f2853bf9, and there are no further `web/` changes.

Requirements:
- tasks/web-cal-hidden-indicator.md (acceptance criteria and recorded assumptions)
- AGENTS.md project notes
- Previous review (CR-1 to CR-4, all resolved) and fixes.md

Git range:
- Branch: main
- HEAD: 3f2853bf9
- Base/start: f8cec873d (main before the task; current merge-base 907999ac8)
- Commits reviewed: 1 new (3f2853bf9), plus the full task diff re-checked

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-5 | Fixed 50 ms wait stands in for "range load finished"**
    - File: `web/src/app/features/calendar/views/calendar-view.spec.ts:1443`
    - Category: Tests
    - Problem: The baseline load count is taken after `setTimeout(50)`. The test assumes the range load triggered by `gotoDate('2026-04-10')` has happened by then. Nothing asserts that it has.
    - Why it matters: Suppose the range load arrives after 50 ms on a slow runner. If the refetch works, the count reaches loads + 2 and the test fails (flaky, but loud). Only if the refetch is also broken could the late range load be counted as the "+1" and hide the regression. That needs two things to go wrong at once, so the risk is low.
    - Suggested fix (optional): Replace the fixed wait with a wait on a condition. For example, wait until a `listEventOccurrences` call covering 2026-04-10 exists, or until the count stops changing across two `settle()` calls. Then take the baseline.

## Assessment of 3f2853bf9

- The test still proves refetch-after-change. The editor promise is now resolved by hand (`closeEditor(true)`) right after the baseline is measured, so the asserted `loads + 1` comes only from closing the editor with `changed = true`. The `gotoDate` range load no longer falls inside the measured window. The test still asserts `gotoDate('2026-04-10')` and the editor arguments (`eventId`, `allDay: true`).
- The test is more robust than before, which failed deterministically with 3 loads vs 2. The one remaining timing assumption is CR-5.
- No production code changed.

## Overall task diff

The earlier conclusions still hold for `git diff f8cec873d 3f2853bf9 -- web/`:
- Per-column top and bottom chips with counts, computed in pure `hidden-entries.ts` with unit tests. Partly visible entries count as visible.
- The chips update on scroll, resize, event changes, view changes and period changes (CR-1).
- The chips are real buttons with WCAG-conformant labels in singular and plural, in EN and DE (CR-2, CR-3).
- Timers, the animation frame, listeners and the ResizeObserver are cleaned up (CR-4).

## Review conclusion

No material issues found. CR-5 is a low-priority, optional hardening of the test. The parent must still record evidence for each criterion with `tasks.py verify`, including the browser check and a green `sh scripts/check.sh` on merged main.
