# Code Review

Date: 2026-10-26

Task: a11y-audit - Accessibility audit

Scope: final task range `git diff 4682b60fe..5336d8cce -- . ':!tasks' ':!reviews'` (implementation, CR-2 fix, CR-1 unit tests)

Requirements:
- tasks/a11y-audit.md acceptance criterion and its three recorded assumptions (scan scope, fixes outside `touches`, FullCalendar scroller DOM patch)
- SPEC.md, docs/ARCHITECTURE.md, AGENTS.md project notes (lint, conventions, slow suites outside `.taskcheck`)
- Previous review `reviews/a11y-audit/review.md` (CR-1, CR-2) and `reviews/a11y-audit/fixes.md`
- Parent-supplied evidence (not re-run by the reviewer): `npm --prefix web run check` -> lint clean, 3680 tests passed; focused calendar-view spec -> 59 passed; e2e a11y Playwright suite against the podman stack being re-run by the parent on this revision

Git range:
- Branch: main
- HEAD: 5336d8cce3e8ccb956b379b1e1dffcc26f16f3c8
- Base/start: 4682b60fecefe51e45ac2f2483ed93739bc09297
- Commits reviewed: 3 (29b12262d, 4c6070ef9, 5336d8cce); no uncommitted changes

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Status of earlier findings

- CR-1 (`focusableScrollers` has no unit test): resolved. `web/src/app/features/calendar/views/calendar-view.spec.ts` ("keyboard access to the time grid") covers the patch through the real `datesSet` callback: role swap and `tabindex=0`, a non-overflowing `overflow-y: scroll` scroller, idempotence on a second `datesSet`, parents that are not a `rowgroup` (none / `grid`), an existing `tabindex`, non-vertical scrollers (`hidden`, horizontal `auto`), scrollers outside `full-calendar`, plus the today button's accessible name containing its visible text.
- CR-2 (patch skipped non-overflowing scrollers, only runs on `datesSet`): resolved. The `scrollHeight <= clientHeight` guard is removed in `calendar-view.ts` `focusableScrollers`, so the time-grid scroller is focusable from the first render and stays reachable after a resize. Checked against the installed FullCalendar 7 bundle (`node_modules/fullcalendar/chunks/08b6f884.js`): the only `Scroller` that is a direct child of `role="rowgroup"` with vertical overflow is the time-grid body scroller (`timeScrollerRef`); the day-grid (month) body scroller is not inside a `rowgroup`, and the header/all-day scrollers are horizontal-only (`overflow-y: hidden`) or not direct rowgroup children, so dropping the guard adds no unintended tab stops in other views.

Notes (not findings):
- Pages scanned, theme handling (`PATCH /settings`, restored to `system` in `afterAll`), and running `a11y/` in the `inbox-exclusive` project match the recorded assumptions. Detail routes and `/setup`, `/enroll-totp` are outside the recorded scan scope; humans review that assumption.
- The CSS fixes (`calendar-view.scss` faint foreground contrast, `inbox-page.scss` `kbd` opacity, `markdown-editor.scss` placeholder specificity) are small, commented, and use existing theme tokens.
- `setTimeout` in `onDatesSet` is not cleared on destroy; running it on a detached host is a harmless no-op.
- The criterion is a running-behaviour criterion (Playwright + axe against the stack). Evidence for it must come from the e2e run on this final revision that the parent is performing; unit tests alone do not satisfy it.

## Review conclusion

No material issues found.
