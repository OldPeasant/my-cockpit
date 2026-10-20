# Code Review

Date: 2026-10-20

Task: web-reminders - Browser notifications for reminders

Scope: `git diff d6990d49..HEAD` (task commits 864f4001, 97565d74), committed changes only

Requirements:
- tasks/web-reminders.md acceptance criteria 1-2 and its recorded assumptions (localStorage switch, `ChangeEventsService.messages$`, notification-or-toast policy)
- SPEC.md §4.6 (reminders: SSE event to web, browser Notification API with permission), §4.7
- docs/ARCHITECTURE.md (Angular conventions, i18n, testing), AGENTS.md project notes
- Server contract: `ReminderController.kt` (`ReminderNotifier.EVENT_NAME = "reminder"`, `ReminderResponse`, `ReminderItemResponse.deepLink`)

Git range:
- Branch: main
- HEAD: 97565d74
- Base/start: d6990d4949b5bb7f9160a0887d31e2f93a991f5b
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 0 P2, 3 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Permission hint is not tied to the switch for assistive technology**
    - File: `web/src/app/core/notifications/reminder-notifications-toggle.html:1`
    - Category: Quality (a11y)
    - Problem: The `<p class="hint" role="status">` explains why the switch is disabled ("can't show notifications", "blocked for this site"), but the `mat-slide-toggle` has no `aria-describedby` that points to it. A disabled `mat-slide-toggle` also drops out of the Tab order, so a keyboard or screen-reader user does not hear why the setting is unavailable when they reach the control.
    - Why it matters: The reason the switch is unavailable is only conveyed visually next to the control.
    - Suggested fix: Give the hint an id and bind `[aria-describedby]` on the slide toggle (it supports the input). Optionally use `disabledInteractive` so the disabled switch stays focusable and announces its description.

- [ ] **CR-2 | Permission state is only re-read when the toggle is created or a reminder fires**
    - File: `web/src/app/core/notifications/reminder-notifications-toggle.ts:22`
    - Category: Correctness
    - Problem: `refresh()` runs only in the toggle's constructor (and in `remind()`). The denied hint tells the user to allow notifications in the browser's site settings. When they do that and come back to the open Settings page, the switch stays disabled with the "blocked" text until they leave the page and come back.
    - Why it matters: The UI keeps showing an outdated state in exactly the recovery flow it describes.
    - Suggested fix: Call `refresh()` again on window `focus`/`visibilitychange` while the toggle is shown, or listen to `navigator.permissions.query({ name: 'notifications' })` `change` where it is available, behind the `BROWSER_NOTIFICATIONS` abstraction.

- [ ] **CR-3 | The "reminders show inside My Cockpit" text appears twice in the same settings row**
    - File: `web/src/app/features/settings/general/general-settings.html:89`
    - Category: Quality
    - Problem: When the switch is off, the row description says "Only for this browser. Without them, reminders show inside My Cockpit." and the toggle's hint right next to it says "Reminders appear inside My Cockpit while it is open." (`reminder-notifications-toggle.html:356-358`).
    - Why it matters: The same message twice in one row is redundant, and translators have to translate it twice.
    - Suggested fix: Keep the fallback explanation in one place. For example, reduce the row description to "Only for this browser." or drop the `off` hint.

## Notes (verified, no finding)

- Criterion 1: `requestPermission()` is called synchronously inside the `mat-slide-toggle` `(change)` handler (before the first `await` in `enable()`), so the call keeps the user gesture. `remind()` shows a `Notification` (tag `reminder-<id>`) when the user switched it on and the permission is granted. In every other case, including a throwing constructor such as Chrome on Android, it falls back to a `MatSnackBar` toast with an Open/Dismiss action.
- `ChangeEventsService.messages$` attaches the named listener to the current connection and to every later one. Each connection gets one listener per name. The streams complete on destroy. Unparsable data is dropped.
- Deep links: the code only follows a link when `item.available`, and it uses `Router.navigateByUrl`. That keeps navigation inside the SPA router, and the value comes from the server's `ItemResolver.deepLink`. The title is rendered as text (notification title, snackbar message), not as HTML.
- localStorage reads and writes are wrapped in try/catch, and any value other than `on` reads as off.
- i18n: every new `$localize`/`i18n` id (`reminders.*`, `settings.reminders*`, reused `notification.dismiss`) is in `web/src/locale/messages.xlf`. The xlf diff also adds about 60 units from earlier features that had not been extracted. I could not re-run `npm run i18n:extract` here (permission denied), so I did not confirm that the file matches a fresh extraction byte for byte.
- Tests: the service and component specs cover the permission states default, granted, denied and unsupported, the refused prompt, a permission revoked after it was granted, the browser refusing to show a notification, an unavailable item, a malformed event, and the switch stored in localStorage. The `requestPermission()` rejection path (`.catch` in `enable()`) is not tested, which is minor. Neither criterion has `tasks.py verify` evidence yet. The parent worker records it.

## Review conclusion

No material issues found.
