# Code Review

Date: 2026-10-20

Task: web-reminders - Browser notifications for reminders

Scope: `git diff 80976ef160809da48d1e5d90270a2d1250159ce1..37685c7fcfa16449d8f715f872f5fec7def605f2` (merge-base with origin/main .. HEAD), committed changes only. This is the final review of the final code. Task commits: 5fcd9c69, 0e181738, 9ff169c4, 2e0a39b1, ba96656e (fixes for the earlier CR-1..CR-3), 37685c7f (more tests, final tests.md).

Requirements:
- tasks/web-reminders.md acceptance criteria 1-2 and its recorded assumptions (switch stored in localStorage, `ChangeEventsService.messages$`, notification-or-toast policy, wiring outside the touches)
- SPEC.md §4.6 (the server fires reminders, sends an SSE event to the web, browser Notification API with permission)
- docs/ARCHITECTURE.md and AGENTS.md project notes (Angular conventions, i18n, tests, lint)
- Server contract: `ReminderController` `EVENT_NAME = "reminder"`, `ReminderService.fireDue()` (batch of up to `FIRE_BATCH = 100`), generated `ReminderResponse` / `ReminderItemResponse`
- reviews/web-reminders/fixes.md (fixes for the earlier CR-1..CR-3)

Git range:
- Branch: main
- HEAD: 37685c7fcfa16449d8f715f872f5fec7def605f2
- Base/start: 80976ef160809da48d1e5d90270a2d1250159ce1
- Commits reviewed: 6
- Uncommitted changes: none (clean working tree)

Summary: 0 P0, 0 P1, 1 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | The toast fallback shows only the last of several reminders that fire together**
    - File: `web/src/app/core/notifications/reminder-notifications.service.ts:112`
    - Category: Correctness
    - Problem: `toast()` calls `MatSnackBar.open()` for each reminder. MatSnackBar shows only one snackbar at a time: `open()` dismisses the current one (`_openedSnackBarRef.dismiss()` in `@angular/material` snack-bar). The server fires due reminders in batches (`ReminderService.fireDue()` sends up to 100 per run, every 15 s), so reminders due in the same poll window arrive back to back. Examples: two reminders set for 09:00, or overdue reminders after a server restart. With browser notifications off, denied, unsupported, or refused by the browser, the user sees only the last toast, and the others are gone after a few milliseconds. Fired reminders are not replayed, so the user never sees the earlier ones. Any later app toast (`NotificationService.info/error`) also replaces a reminder toast that is still showing. The spec "toasts every fired reminder while notifications are unsupported" (`reminder-notifications.service.spec.ts:347`) replaces `MatSnackBar` with a mock, so it cannot catch this.
    - Why it matters: The in-app toast is the required fallback (criterion 1), and on browsers without page notifications, such as Chrome on Android, it is the only channel. Losing reminders without notice in a common case defeats the feature.
    - Suggested fix: Don't let one reminder replace another. For example, queue reminder toasts and open the next one in `afterDismissed()`, or show one combined toast for reminders that arrive together ("3 reminders", with Open going to the first one or to a list). Add a test that uses the real `MatSnackBar`, or a mock that dismisses on `open()`, and checks that every reminder in a burst is shown.

## P3 Low

None.

## Notes (verified, no finding)

- Criterion 1, permission from a gesture: `change()` calls `service.enable()`, and `enable()` calls `requestPermission()` synchronously, before its first `await`, so the request stays inside the click gesture. The e2e spec checks `navigator.userActivation.isActive` at the request. A refused or failed prompt leaves the switch off: `event.source.checked = this.checked()`, and the rejection falls back to the current permission. While the browser is asking, `disabledInteractive` keeps the switch focusable but ignores clicks (spec "ignores a second click while the browser asks").
- Criterion 1, notification on the `reminder` SSE event: the event name matches the server's `EVENT_NAME`. `messages$` adds its listener to the current EventSource and to every later connection, with no duplicate listeners. Unparsable data is dropped, and the subjects complete on destroy. `provideReminderNotifications()` in the authenticated shell route creates the root service. It re-reads the permission for each reminder, and `tag: reminder-<id>` removes duplicates across tabs. Navigation uses the server-supplied `deepLink` via `Router.navigateByUrl`, but only when `available`. Titles are rendered as text.
- Stored state: localStorage reads and writes are wrapped in try/catch, and only `on` counts as on. Specs cover failing storage.
- The earlier CR-1..CR-3 (aria-describedby/disabledInteractive, re-reading the permission on focus/visibility, the duplicate hint) are still fixed in the current code. The specs added in 37685c7f test behavior (focus refresh, hidden page, unique hint ids, unsupported clicks, a single fallback text) and pass.
- `messages.xlf`: the large diff comes from a full `i18n:extract`, which also added `settings.*` units that were missing before. All new task ids are present.
- Tests run by this reviewer: `cd web && npx ng test --watch=false --include src/app/core/notifications --include src/app/core/events --include src/app/features/settings/general` -> 5 files, 117 tests passed. I did not run the full `npm run check` or the Playwright spec myself.
- Evidence: `python tasks.py verify web-reminders` still shows both criteria unrecorded. Nothing is claimed without evidence yet. The parent worker records it before `done`.

## Review conclusion

Material issues found; fix before completing the task.
