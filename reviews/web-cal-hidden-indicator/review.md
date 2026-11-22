# Code Review

Date: 2026-11-22

Task: web-cal-hidden-indicator - Web calendar: day/week show which entries are scrolled out of view

Scope: re-review of the final code at HEAD af6a82f22. `git diff 8cf62154b^..af6a82f22 -- web` (task commits 8cf62154b, 8818208fe, c0a1ad40d, 4bd88909f, fixes f86cb06e3, tests 6923fb36b), plus the committed browser-check script `reviews/web-cal-hidden-indicator/browser-check.spec.ts.txt`. Commits after 6923fb36b change only `tasks/` and `reviews/`, so they add no production code. The untracked file `web/e2e/calendar-views/zz-hidden-check.spec.ts` is not committed and was not reviewed.

Requirements:
- tasks/web-cal-hidden-indicator.md (acceptance criteria and recorded assumptions)
- AGENTS.md project notes (i18n, conventions, evidence rules)
- Previous review (CR-1 to CR-4) and fixes.md
- Existing calendar-view conventions

Git range:
- Branch: main
- HEAD: af6a82f22 (reviewed revision)
- Base/start: 8cf62154b^
- Commits reviewed: 6 task code commits (4 implementation, 1 fix, 1 test) plus task/review-only commits

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Earlier findings: status

- CR-1 (no chip update on a period change): resolved. The scheduling effect in `calendar-view.ts` now also reads `this.range()`. The regression test is `calendar-view.spec.ts:1349` ("recomputes the chips when the period changes, before its events load").
- CR-2 (singular labels): resolved. `chipLabel` picks separate `.one` messages for count 1. English and German sources and targets are present in `messages.xlf` and `messages.de.xlf`.
- CR-3 (WCAG 2.5.3): resolved. The labels start with the wide chip's visible text ("2 earlier: show hidden events" / "2 früher: verborgene Termine anzeigen"). The narrow chip's visible count is also in the label.
- CR-4 (timer cleanup): resolved. `chipTimeout` is cleared before it is re-queued and in `ngOnDestroy`, along with the animation frame, the listeners and the ResizeObserver. A test covers it: `calendar-view.spec.ts:1391`.

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

Notes (not findings):
- Criterion coverage in code: per-column top and bottom chips with counts, and partly visible entries count as visible (`hiddenEntries`). Chips update on scroll (capture listener), on resize (window and ResizeObserver), and on events, view and period changes. Chips are real `<button>`s with an aria-label, so Enter and Space work natively, and a click scrolls smoothly to the nearest hidden entry. The visibility calculation is a pure function in `hidden-entries.ts` with unit tests. The DOM measuring is in `hidden-chips.ts` with its own tests.
- The 360px fix in af6a82f22 changes only the browser-check script (separate dates per viewport). Production code did not change after f86cb06e3. The committed script checks: chip top below the all-day event, chips inside the viewport width, Enter and click scrolling, and chips vanishing on a tall viewport. That fits criteria 1-4. The parent should record its run (command, stack, result) with `tasks.py verify`.
- Criterion 7 ("`npm run check` and `sh scripts/check.sh` pass") is not shown yet. tests.md reports an intermittent existing `linked event` test and 4 `task-reminders.spec.ts` failures outside this task's scope. The parent must get `.taskcheck` green, or rule these out on current main, before `done`. Nothing in this task's chip effect triggers occurrence loads, so it is not a plausible cause of the extra `listEventOccurrences` call.
- Do not commit the untracked `web/e2e/calendar-views/zz-hidden-check.spec.ts`. It is a scratch copy of the browser check.
