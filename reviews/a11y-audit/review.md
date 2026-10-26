# Code Review

Date: 2026-10-26

Task: a11y-audit - Accessibility audit

Scope: task commit 9e770e525 (`git diff c89e62d59..9e770e525`)

Requirements:
- tasks/a11y-audit.md acceptance criterion and recorded assumptions (scope of "main pages", edits outside touches, FullCalendar DOM patch)
- SPEC.md, docs/ARCHITECTURE.md (stack table already lists Playwright + `@axe-core/playwright`), AGENTS.md project notes
- Parent-supplied evidence: `npm --prefix web run e2e -- --no-deps a11y/` against the podman e2e stack -> 5 passed; `cd web && npm run check` -> lint ok, 3672 unit tests passed (not re-run by the reviewer)

Git range:
- Branch: main
- HEAD: 9e770e525
- Base/start: c89e62d590697fac12ca274222dfbdb4b932362d
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | `focusableScrollers` has no unit test**
    - File: `web/src/app/features/calendar/views/calendar-view.ts:317`
    - Category: Tests
    - Problem: The new DOM patch (role swap `rowgroup` -> `none` on the parent, `role=rowgroup` + `tabindex=0` on the scroller, skip when already focusable / not scrollable / parent not a rowgroup) is covered only indirectly by the axe scan of the day/week views in `web/e2e/a11y/a11y.spec.ts`. No calendar-view spec references it, so its guard conditions (idempotence, non-rowgroup parent left alone) are unverified at unit level.
    - Why it matters: The patch depends on FullCalendar 7's internal DOM; a library update that changes it would only show up in the slow e2e suite, which is not part of `.taskcheck`.
    - Suggested fix: Add a small unit test that builds a fake `rowgroup > div[style*=overflow]` structure (with stubbed scroll sizes) and asserts the attributes after the call, plus that a second call changes nothing.

- [ ] **CR-2 | Scroller patch only runs on `datesSet`**
    - File: `web/src/app/features/calendar/views/calendar-view.ts:295`
    - Category: Correctness
    - Problem: `focusableScrollers` runs once per `datesSet` and skips scrollers with `scrollHeight <= clientHeight`. If the time grid becomes scrollable later without a date change (window resized smaller, layout change), the scroller stays non-focusable until the user navigates dates or views.
    - Why it matters: Keyboard users in that situation cannot scroll the hours (WCAG 2.1.1), the exact case the patch targets; it is an edge case, the normal load path is covered by the e2e scan.
    - Suggested fix: Either drop the `scrollHeight` guard (the time-grid scroller is the intended target and making it focusable when it does not overflow is harmless), or also re-run the patch on FullCalendar's `windowResize` callback.

Notes (not findings):
- Pages scanned match the recorded assumption (all shell sections of `app.routes.ts`/`gtd.routes.ts`, 4 calendar views, login, capture dialog, command palette, processing mode). Detail routes (`/tasks/:id`, opened note/mail/contact) and `/setup`, `/enroll-totp` are not scanned; that scope decision is already recorded as an assumption for human review.
- The duplicate `$localize` id `calendar.today` for `text` and `hint` uses identical text, which Angular extraction accepts.
- `afterAll` restores the shared account's theme to `system`, not to its previous value; acceptable because the e2e account is test-only and this is documented in the assumption.

## Review conclusion

No material issues found.
