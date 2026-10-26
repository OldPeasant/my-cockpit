# Fix Report

Date: 2026-10-26

Task: a11y-audit - Accessibility audit

Scope: Current task, finding CR-2 from `reviews/a11y-audit/review.md` (CR-1 assigned to test-writer by the parent worker)

## Fixed findings

### CR-2 - Scroller patch only runs on `datesSet`

Files changed:
- `web/src/app/features/calendar/views/calendar-view.ts`

Problem:
`focusableScrollers` skipped time-grid scrollers with `scrollHeight <= clientHeight`. Because the patch runs only on `datesSet`, a grid that became scrollable later (window resized smaller, layout change) stayed non-focusable until the user changed dates or views, so keyboard users could not scroll the hours (WCAG 2.1.1).

Fix:
Removed the `el.scrollHeight <= el.clientHeight` guard. The scroller with `overflow-y: auto|scroll` right inside a `rowgroup` is the intended target; it is now made focusable regardless of current overflow, so it stays reachable after resizes. Making it focusable while it does not overflow is harmless. The doc comment was extended to say so. The other guards (already has `tabindex`, parent not a `rowgroup`, not a scrolling overflow style) are unchanged.

Validation:
- `npm --prefix web run lint` - Pass (All files pass linting)

## Not fixed

### CR-1 - `focusableScrollers` has no unit test

Status:
Out of scope

Reason:
Deferred by the parent worker to the test-writer sub-agent, which adds the unit test. The fixer was instructed not to add tests.

## Validation summary

Focused tests:
- Not run by the fixer; the parent worker runs the unit and e2e suites (calendar view tests and the axe scan in `web/e2e/a11y/a11y.spec.ts`).

Full test suite:
- Not run by the fixer (left to the parent worker).

Lint/type-check/build:
- `npm --prefix web run lint` - Pass

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
1 (CR-1, handled by test-writer)

## Final status

All validated findings fixed and validation passed. (CR-2 fixed and lint passes; CR-1 is handed to test-writer.)
