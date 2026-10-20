# Fix Report

Date: 2026-10-20

Task: web-reminders - Browser notifications for reminders

Scope: current task, findings CR-1, CR-2, CR-3 (all P3) from `reviews/web-reminders/review.md`, approved by the parent worker

## Fixed findings

### CR-1 — Permission hint is not tied to the switch for assistive technology

Files changed:
- `web/src/app/core/notifications/reminder-notifications-toggle.html`
- `web/src/app/core/notifications/reminder-notifications-toggle.ts`
- `web/src/app/core/notifications/reminder-notifications-toggle.spec.ts`
- `web/src/app/features/settings/general/general-settings.spec.ts`

Problem:
The hint that explains why the switch is unavailable was not linked to the `mat-slide-toggle`. A disabled toggle also dropped out of the Tab order, so keyboard and screen-reader users never heard the reason.

Fix:
The hint gets a per-instance id (`app-reminder-notifications-hint-N`). The toggle binds `[aria-describedby]` to it and uses `disabledInteractive`. When disabled, the switch now stays focusable with `aria-disabled="true"`, announces its description, and still ignores clicks (Material's `_handleClick` checks `disabled`). The specs now check `aria-disabled` instead of the native `disabled` property. A new spec checks that `aria-describedby` points at the hint, that the button is focusable (`tabIndex` 0, not natively disabled) while denied, and that a click does nothing.

Validation:
- `cd web && npm run check` — Pass (30 files, 495 tests)

### CR-2 — Permission state is only re-read when the toggle is created or a reminder fires

Files changed:
- `web/src/app/core/notifications/reminder-notifications-toggle.ts`
- `web/src/app/core/notifications/reminder-notifications-toggle.spec.ts`

Problem:
After the user allowed notifications in the site settings and came back to the open Settings page, the switch stayed disabled with the "blocked" text.

Fix:
While the toggle is shown, it calls `ReminderNotificationsService.refresh()` on window `focus` and on document `visibilitychange` (only when the page becomes `visible`). Both listeners are removed through `DestroyRef.onDestroy`. The Permissions API `change` listener was not added. It would mean extending the `BROWSER_NOTIFICATIONS` abstraction, and focus/visibility already covers the recovery flow. New specs cover: denied, then focus, then enabled; denied, then visibilitychange, then granted; and no refresh after destroy.

Validation:
- `cd web && npm run check` — Pass

### CR-3 — The "reminders show inside My Cockpit" text appears twice in the same settings row

Files changed:
- `web/src/app/features/settings/general/general-settings.html`
- `web/src/app/features/settings/general/general-settings.spec.ts`
- `web/src/locale/messages.xlf` (regenerated)

Problem:
When the switch was off, the row description and the toggle's `off` hint gave the same fallback message.

Fix:
The row description (`@@settings.reminders.hint`) is shortened to "Only for this browser.". The fallback explanation is now only in the toggle's hints, which depend on the permission state and are linked to the switch (CR-1). `npm run i18n:extract` regenerated `messages.xlf`: only the changed source text and line numbers moved. The settings spec checks that "inside My Cockpit" appears exactly once in the off state.

Validation:
- `cd web && npm run i18n:extract` — Pass (209 messages)
- `cd web && npm run check` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npm run check` (api:check, lint `--max-warnings=0`, e2e typecheck, `ng test --watch=false`, `ng build`) — Pass, 30 test files / 495 tests. The build shows the existing initial-bundle budget warning (590 kB vs 500 kB). It is a warning, not an error, and these fixes did not cause it.
- `npx prettier --check src/app/core/notifications src/app/features/settings/general` — Pass

Full test suite:
- `sh scripts/check.sh` — Pass ("all checks passed": server, web, android)

Lint/type-check/build:
- Included in `npm run check` above — Pass. `web/e2e/reminders/notifications.spec.ts` still type-checks. The switch name ("Show reminders as browser notifications") and toast text ("Reminder: " plus the item title) did not change, and in the e2e flow (permission `default`) the switch is not disabled, so its click flow did not change. The Playwright e2e suite was not run here, because it needs the running stack.

`.taskcheck`:
- Pass (`sh scripts/check.sh`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
