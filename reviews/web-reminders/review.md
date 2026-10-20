# Code Review

Date: 2026-10-20

Task: web-reminders - Browser notifications for reminders

Scope: `git diff d6990d49..HEAD` at 0e3b1afd17940a33b12500e6bce19e5d8c7a2195, committed changes only. This is the second review, on the final code after the fixes for the first review's CR-1..CR-3 (`reviews/web-reminders/fixes.md`). Task commits: cece25d3, 42c7e20a, 39e0ad6f, cf02535f, 0e3b1afd. The other commits in the range are TFP heartbeat, assume, claim and release commits for other tasks, outside this scope.

Requirements:
- tasks/web-reminders.md acceptance criteria 1-2 and its recorded assumptions (localStorage switch, `ChangeEventsService.messages$`, notification-or-toast policy, wiring outside the touches)
- SPEC.md §4.6 (reminders: SSE event to web, browser Notification API with permission), §4.7
- docs/ARCHITECTURE.md (Angular conventions, i18n, testing), AGENTS.md project notes
- Server contract: `ReminderNotifier.EVENT_NAME = "reminder"`, `ReminderResponse` / `ReminderItemResponse` (generated client)
- reviews/web-reminders/fixes.md (fixes claimed for CR-1..CR-3)

Git range:
- Branch: main
- HEAD: 0e3b1afd17940a33b12500e6bce19e5d8c7a2195
- Base/start: d6990d4949b5bb7f9160a0887d31e2f93a991f5b
- Commits reviewed: 5 task commits (plus non-task TFP bookkeeping commits in the range)
- Uncommitted changes: when this review was written, the working tree had uncommitted edits to `reminder-notifications-toggle.spec.ts` and `general-settings.spec.ts`, probably from a helper running at the same time. They are not part of this review.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Verification of earlier findings

- CR-1 (hint not tied to the switch): fixed. `reminder-notifications-toggle.html` gives the hint a per-instance `id` (`app-reminder-notifications-hint-N`). The `mat-slide-toggle` binds `[aria-describedby]` to that id and sets `disabledInteractive`. While blocked or asking, the switch keeps focus with `aria-disabled="true"`, and Material still ignores clicks on it. The committed spec "is described by its hint and stays focusable while disabled" checks the `aria-describedby` link, `tabIndex` 0, that the button is not natively disabled, and that a click does not request permission.
- CR-2 (permission only re-read on create or on a reminder): fixed. The toggle constructor adds window `focus` and document `visibilitychange` listeners. The `visibilitychange` listener only reacts when the page becomes `visible`. Both call `service.refresh()` and are removed in `DestroyRef.onDestroy`. Committed specs cover the focus path, the visibility path, and that nothing is refreshed after destroy.
- CR-3 (duplicate fallback text): fixed. The row description `@@settings.reminders.hint` now reads "Only for this browser.", and `messages.xlf` has the new source. The settings spec checks the count of "inside My Cockpit" in the row.

## Notes (verified, no finding)

- Criterion 1, permission from a gesture: in `change()` the code sets `asking` to true and calls `service.enable()`. `enable()` reads `permission()` and calls `requestPermission()` synchronously, before its first `await`, so the request stays inside the toggle's click gesture. The e2e spec records `navigator.userActivation.isActive` at the request and expects `[true]`. A refused or failed prompt (`.catch` falls back to the current permission) leaves the switch off: the code resets `event.source.checked` to `checked()`. A component spec covers the rejection path.
- Criterion 1, notification on the `reminder` SSE event: `ChangeEventsService.messages$(name)` adds a listener for each name to the current EventSource and to every later connection (`connect()` loops over `named.keys()`), with no duplicate listeners. Unparsable data is dropped, and the subjects complete on destroy. `ReminderNotificationsService` subscribes to `messages$('reminder')` and is created by `provideReminderNotifications()` in the authenticated shell's route providers. It re-reads the permission on every reminder. It shows a `Notification` (tag `reminder-<id>`) when the switch is on and the permission is granted.
- Criterion 1, toast fallback: if notifications are off or not permitted, or `show()` throws (as in Chrome on Android), a `MatSnackBar` toast is shown. It has an "Open" action when `item.available` and a deep link exist, and "Dismiss" otherwise. Navigation goes through `Router.navigateByUrl`, the title is rendered as text, and an item without a title falls back to "Reminder".
- Stored state: localStorage reads and writes are wrapped in try/catch, and only `on` counts as on. If storage is unavailable, the switch still works for the session.
- Tests: I ran `npx ng test --watch=false` on `core/notifications`, `core/events` and `features/settings/general` -> 5 files, 115 tests passed. That run used the working tree, which included the uncommitted spec edits mentioned above, and the committed specs are a subset of it. The specs cover the permission states default, granted, denied and unsupported, a refused prompt, a failed prompt, a permission revoked after it was granted, the browser refusing to show a notification, an unavailable item, a malformed event, the stored switch, and the CR-1/CR-2 behaviors. I did not run the full `npm run check` myself. The parent and the fixer report it passing (30 files / 495 tests). The parent reports that the Playwright spec `web/e2e/reminders/notifications.spec.ts` passed (2 tests) against the real e2e stack. It covers the gesture-based permission request, the browser notification, and the toast with Open navigating to `/tasks/<id>`.
- Evidence: `python tasks.py verify web-reminders` still shows both criteria without recorded evidence. Nothing is claimed without evidence. The parent worker records it before `done`.
- i18n: I could not confirm that `messages.xlf` matches a fresh `npm run i18n:extract` byte for byte, because the extraction writes into the repository. The changed `settings.reminders.hint` source is present.

## Review conclusion

No material issues found.
