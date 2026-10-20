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

---

# Second review — fixes

Date: 2026-10-20

Task: web-reminders - Browser notifications for reminders

Scope: current task, finding CR-1 (P2) from the second review in `reviews/web-reminders/review.md` (revision 37685c7f), approved by the parent worker

## Fixed findings

### Second review — CR-1 — The toast fallback shows only the last of several reminders that fire together

Files changed:
- `web/src/app/core/notifications/reminder-notifications.service.ts`
- `web/src/app/core/notifications/reminder-notifications.service.spec.ts`

Problem:
`toast()` called `MatSnackBar.open()` for each reminder. MatSnackBar shows one snackbar at a time, and `open()` dismisses the current one. The server fires due reminders in batches, so with browser notifications off, blocked, unsupported or refused, a burst of reminders lost every toast except the last one.

Fix:
The service now queues reminder toasts. `toast()` adds the reminder (title, link) to a private queue and opens it at once only when no reminder toast is showing. Each opened toast subscribes to `afterDismissed()` and then opens the next queued one (by timeout, the Open/Dismiss action, or another snackbar replacing it). The message, the Open/Dismiss action, the 15 s duration and the per-reminder `navigateByUrl(deepLink)` on Open are unchanged. No new message was added, so `i18n:extract` was not needed. `DestroyRef.onDestroy` clears the queue, so nothing queued opens after the app is torn down.

Tests: the `MatSnackBar` double in the spec now models the real one-at-a-time behavior (opening a toast dismisses the shown one and emits its `afterDismissed`, and each toast has its own action). New specs:
- three reminders in a burst open one toast at a time, in order, with the right action each (Open, Open, Dismiss), and nothing more opens after the last one;
- Open on a queued reminder's toast navigates to that reminder's own deep link;
- a reminder after the queue ran empty is toasted right away;
- queued toasts are dropped when the service is destroyed.
The existing spec "toasts every fired reminder while notifications are unsupported" now dismisses the first toast before checking that both were shown. With the double, a second toast cannot appear while the first is still showing, and that is the behavior this fix requires.
Regression check: with the HEAD version of the service and the new specs, "shows reminders that fire together one after the other, in order" and "drops queued reminder toasts when the app is destroyed" fail. With the fix, they pass.

Validation:
- `cd web && npx ng test --watch=false --include src/app/core/notifications` — Pass (3 files, 57 tests)
- `cd web && npm run check` — Pass

## Not fixed

None. Note: a later non-reminder app toast (`NotificationService.info/error`) still replaces a reminder toast on screen. The reminder is no longer lost from the queue: the next queued reminder opens when it is dismissed. The replaced reminder's toast itself was not re-shown, because the preferred fix is limited to queuing reminder toasts.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include src/app/core/notifications` — Pass, 57 tests
- `npx prettier --check` on both changed files — Pass

Full test suite:
- `cd web && npm run check` (api:check, lint `--max-warnings=0`, e2e typecheck, `ng test`, `ng build`) — Pass, 33 test files / 540 tests. The build shows the existing initial-bundle budget warning (591 kB vs 500 kB). It is a warning, not an error, and this fix did not cause it.

Lint/type-check/build:
- Included in `npm run check` — Pass. The Playwright e2e toast spec (one reminder) was not run, because it needs the running stack. Its path is unchanged for a single reminder: the toast opens at once.

`.taskcheck`:
- Pass (`sh scripts/check.sh` -> "all checks passed")

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
