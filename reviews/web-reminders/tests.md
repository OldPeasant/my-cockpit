# Test Report

Date: 2026-10-20

Task: web-reminders: Browser notifications for reminders

Scope: `git diff d6990d4949b5bb7f9160a0887d31e2f93a991f5b..97565d748d6ca6ce4db1e9c4b410edae44e12a9c` (task commits 864f4001, 97565d74, given by the parent worker). While I was testing, the parent committed 7497f3e3 (`web/e2e/reminders/notifications.spec.ts`, a Playwright spec only, no production code). It is outside this report's unit-test scope and was not run here. Uncommitted inputs at measurement: the spec changes listed below, which are mine.

Requirements:
- `tasks/web-reminders.md` acceptance criteria 1 and 2, and its recorded assumptions (switch stored per browser in localStorage `cockpit.reminderNotifications`; `ChangeEventsService.messages$(name)`; system notification with tag `reminder-{id}` when switched on and permitted, otherwise an in-app snackbar with an Open action)
- SPEC.md §4.6 (SSE event to web, browser Notification API with permission)
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md

## Test files changed

- `web/src/app/core/notifications/browser-notifications.spec.ts` (new)
- `web/src/app/core/notifications/reminder-notifications.service.spec.ts`
- `web/src/app/core/notifications/reminder-notifications-toggle.spec.ts`
- `web/src/app/core/events/change-events.service.spec.ts`
- `web/src/app/features/settings/general/general-settings.spec.ts` (`setup()` gained an optional `extraProviders` argument; existing tests are unchanged)

## Tests added or updated

Count: 35 added (16 service, 7 browser API adapter, 4 toggle, 6 `messages$`, 2 General settings row). No existing test was changed, weakened or skipped.

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include 'src/app/core/notifications/**/*.spec.ts' --include 'src/app/core/events/*.spec.ts' --include 'src/app/features/settings/general/*.spec.ts'` -> 5 files, 107 tests passed.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: permission is asked only from the settings toggle (user gesture), not on startup or when switching off | toggle: `asks for the permission when switched on, and is on once granted`, `switches off without asking`, `switches on without asking when the permission was granted before`; service: `does not ask again once the permission was refused or granted`; general settings: `switches browser notifications on from the settings page without saving a setting` | F | Pass |
| AC1 / permission state `default` (incl. prompt dismissed / prompt rejecting) | service: `keeps the permission undecided and the switch off when the permission prompt fails`, `asks again on the next enable after the prompt was dismissed`, `does not use a stored switch while the permission is not granted`; toggle: `springs back but stays usable when the permission prompt fails`, `disables the switch and says to answer the prompt while the browser asks` | F | Pass |
| AC1 / permission state `granted` | service: `asks for the permission on enable and remembers the switch when granted`, `restores the stored switch, and disable clears it`, `picks up a permission granted in the browser meanwhile when a reminder fires` | F | Pass |
| AC1 / permission state `denied` | service: `stays off when the permission is refused`, `falls back to a toast when the permission was revoked in the browser meanwhile`; toggle: `springs back and explains the block when the permission is refused`, `shows the permission refused earlier in the browser`, `shows a permission changed in the browser since the service started` | F | Pass |
| AC1 / permission state `unsupported` | service: `cannot be switched on without Notification support`, `toasts every fired reminder while notifications are unsupported`; toggle: `is disabled when the browser has no notifications`; browser adapter: `reports unsupported`, `answers a permission request with denied`, `refuses to show a notification`; general settings: `offers the switch, explaining that this browser has no notifications (jsdom)` | F | Pass |
| AC1: shows a notification on the `reminder` SSE event (title, body, tag per reminder, click focuses, closes and opens the deep link) | service: `listens to the reminder SSE event`, `shows a fired reminder as a browser notification when enabled, and a click opens the item`, `gives the notification a body and one tag per reminder`, `titles the notification "Reminder" when the item has no title`, `only focuses and closes on a notification click when the item is unavailable`; browser adapter: `reads the current permission each time`, `asks the browser for the permission`, `shows a notification with the given title and options` | F | Pass |
| AC1: in-app toast fallback (switch off, permission revoked, browser refuses to show, unavailable item, Open / Dismiss action) | service: `falls back to a toast with an Open action while notifications are off`, `falls back to a toast when the browser refuses to show the notification`, `toasts an unavailable item without a title or link`, `toasts the title of an unavailable item without the Open action`, `does not open the item before the toast action is used`, `toasts again after notifications were switched off`; browser adapter: `passes on the error when the browser refuses to construct one` | F | Pass |
| Malformed events and stored state (null / item-less event, unknown stored value, localStorage throwing) | service: `ignores a reminder event without an item`, `ignores a null reminder event`, `ignores a stored value other than on`, `starts switched off when reading the switch throws`, `keeps the switch for this session when storing it throws` | F | Pass |
| `ChangeEventsService.messages$(name)`: named SSE events (subscribed before login, during a pending reconnect, on later connections and a new login, once per connection, names kept apart, change vs named routing, unparsable/empty data dropped, completes on destroy) | `emits the parsed data of other named events, on the current and later connections`, `listens once per connection however often a named event is subscribed`, `delivers named events subscribed to before the login once the stream connects`, `delivers named events subscribed to while a reconnect is pending`, `keeps named events of different names apart`, `routes change events only to events$ and other named events only to messages$`, `delivers each named event once after reconnects and a new login`, `drops named events with empty data`, `completes the named event streams when the app is destroyed` | F | Pass |
| Settings > General shows the toggle row; the switch is per browser (no settings PATCH) | general settings: `offers the switch, explaining that this browser has no notifications (jsdom)`, `switches browser notifications on from the settings page without saving a setting` (`http.verify()` in `afterEach` asserts no request was sent) | F | Pass |
| Shell wiring (`provideReminderNotifications()` in `app.routes.ts`) | existing `app.spec.ts`, `app.i18n.spec.ts` (shell boots with `provideSilentEventSource()`); `provide-reminder-notifications.ts` and `app.routes.ts` executed 100% | `cd web && npm run coverage` | Pass |
| AC2: `cd web && npm run check` passes | whole web check | `cd web && npm run check` | Pass (api:check, lint, e2e:typecheck, 30 files / 491 tests, build) |
| A real browser shows a system notification (real Notification API, permission prompt) | none in unit scope (jsdom has no Notification API) | n/a | Not run: needs a real browser. The parent's Playwright spec `web/e2e/reminders/notifications.spec.ts` (commit 7497f3e3) is outside this report and I did not run it |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include 'src/app/core/notifications/**/*.spec.ts' --include 'src/app/core/events/*.spec.ts' --include 'src/app/features/settings/general/*.spec.ts'`

Result:

Pass (5 test files, 107 tests).

Format check: `cd web && npx prettier --check src/app/core/notifications/ src/app/core/events/ src/app/features/settings/general/` -> all files use Prettier code style.

## Full test execution

Command:

`cd web && npm run check`

Result:

Pass. api:check, `ng lint --max-warnings=0` ("All files pass linting"), e2e:typecheck, test:ci (30 files, 491 tests passed), and `ng build` all ran. The build printed one warning: the initial bundle is 589.32 kB against a 500 kB budget. It is only a warning, the build still produced its output, and these spec changes do not affect it. I did not check whether the warning is older than this task.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` (server, web and android) ended with "== all checks passed".

## Bugs found

None. Every added test passed against the current production code, and no production defect was found.

## Coverage

Status: verified (fresh run, exit status success, all 491 tests passed).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured by a diff-intersection tool. Every measured in-scope file has 100% line coverage, though, so every executable added or modified line in those files was executed. This says nothing about changed-branch coverage.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-reminders`. Base `d6990d4949b5bb7f9160a0887d31e2f93a991f5b`. Task commits `864f4001`, `97565d74` (task head `97565d748d6ca6ce4db1e9c4b410edae44e12a9c`), as given by the parent. At measurement, HEAD was `7497f3e377aeefc3032f2d228186fbb66896e877`. That commit only adds `web/e2e/reminders/notifications.spec.ts`, which `ng test` does not run, so every production file in scope is identical to the task head.
- Source/test snapshot identifier (including uncommitted inputs): sha256 prefixes, taken before the run and checked again after it with identical results: app.routes.ts 3280379f3e3d, change-events.service.ts abbee73c6c25, change-events.service.spec.ts 20e9fdd487f8, browser-notifications.spec.ts 83081621d575, browser-notifications.ts c27b691af447, index.ts 2f3f26b40e15, provide-reminder-notifications.ts ad5dcbb2337f, reminder-notifications.service.spec.ts eeb7f6f7b780, reminder-notifications.service.ts 4baf567f8fc0, reminder-notifications-toggle.spec.ts 5e0628b90afe, reminder-notifications-toggle.ts c2fb53758cf3, notifications/testing.ts ecbe8049d7f4, reminder-notifications-toggle.html a390950b93e9, general-settings.ts e10aab33e655, general-settings.html d806c252e97b, general-settings.spec.ts ac3ae9a70891.
- Exact included files; renamed/deleted/excluded files and reasons: included are `web/src/app/app.routes.ts`, `core/events/change-events.service.ts`, `core/notifications/browser-notifications.ts`, `core/notifications/provide-reminder-notifications.ts`, `core/notifications/reminder-notifications-toggle.ts`, `core/notifications/reminder-notifications.service.ts` and `features/settings/general/general-settings.ts`. Excluded are `core/notifications/index.ts` (re-exports only, no executable statements; absent from the v8 report), `core/events/testing.ts` and `core/notifications/testing.ts` (test doubles, both 100% executed), the `.html`/`.scss` templates (not instrumented separately; the toggle template is checked through the DOM assertions above), `web/src/locale/messages.xlf` (generated i18n) and the spec files. No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 with the v8 provider (`@vitest/coverage-v8`), command `npm run coverage` (`ng test --watch=false --coverage ...`), run in `web/`, full web unit suite (30 files, 491 tests).
- Run start/completion time and exit status: bundle built 2026-10-20T16:52:14Z; tests started 18:52:14 local (+02:00) and took 14.39 s; report written 16:52:28Z. Success: 30 files and 491 tests passed and the summary printed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 159826a278703770ada6994266fc36b6d7c4d51be433535dbc43509af9daaf3e (mtime 2026-10-20T16:52:28.657Z), `web/coverage/web/coverage-final.json` sha256 fbabf9e3a21987b441691a274302503d98e777360f7006ddbeba38a2bfc15d6c (mtime 16:52:28.660Z).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Vitest rewrote the reports during this run (their mtimes fall inside the run), and the input hashes matched before and after.
- Final run after latest test additions and production fixes: yes for the test additions; no production fixes were made. If the fixer changes production code, this coverage is stale.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/app.routes.ts` | 18/18 (100%) | N/A (0/0) | 17/17 (100%) | 18/18 (100%) |
| `web/src/app/core/events/change-events.service.ts` | 88/89 (98.87%) | 24/25 (96%) | 26/26 (100%) | 75/75 (100%) |
| `web/src/app/core/notifications/browser-notifications.ts` | 9/9 (100%) | 10/10 (100%) | 5/5 (100%) | 8/8 (100%) |
| `web/src/app/core/notifications/provide-reminder-notifications.ts` | 2/2 (100%) | N/A (0/0) | 2/2 (100%) | 2/2 (100%) |
| `web/src/app/core/notifications/reminder-notifications-toggle.ts` | 21/21 (100%) | 14/15 (93.33%) | 5/5 (100%) | 17/17 (100%) |
| `web/src/app/core/notifications/reminder-notifications.service.ts` | 58/58 (100%) | 31/31 (100%) | 14/14 (100%) | 49/49 (100%) |
| `web/src/app/features/settings/general/general-settings.ts` | 110/110 (100%) | 56/61 (91.80%) | 26/26 (100%) | 88/88 (100%) |
| **Aggregate (7 files above, summed counters)** | 306/307 (99.67%) | 135/142 (95.07%) | 95/95 (100%) | 257/257 (100%) |

The aggregate leaves out `core/notifications/index.ts`, which has no executable code and is not in the report, and the two `testing.ts` test doubles (both 100%).

Before my additions, `browser-notifications.ts` (the real Notification API adapter) had no tests. It is now covered 100% in every metric.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/core/events/change-events.service.ts:100` (true branch of `if (this.source \|\| this.reconnectTimer !== undefined) return;`) | Guard against connecting twice. The line predates this task; the task did not change it. | Left uncovered: not task behavior. The effect only calls `connect()` when the login state changes, so the guard is hard to reach from tests. |
| `web/src/app/core/notifications/reminder-notifications-toggle.ts:17[0]` | Branch that v8 maps to the class declaration line (Angular compiler output), not a source condition | Left uncovered: no source-level behavior. Every condition in `change()`, `checked` and `blocked` is executed. |
| `web/src/app/features/settings/general/general-settings.ts:74[0], 149[1], 150[1], 192[1], 202[1]` | Settings save/queue logic that predates this task. The task only added the `ReminderNotificationsToggle` import. | Left uncovered: outside this task's behavior. The toggle row itself is covered by the 2 new General settings tests. |

## Unable to test

- A real browser showing a system notification and the native permission prompt cannot be tested in Vitest/jsdom (no Notification API). The `BrowserNotifications` adapter is tested against a stubbed global `Notification`. Follow-up: run the parent's Playwright spec `web/e2e/reminders/notifications.spec.ts` against the stack the criterion names. This report did not run it.
- Chrome on Android refusing `new Notification()` (it needs a service worker) is only simulated: a constructor that throws `TypeError`, and `refuseToShow` in the fake. The toast fallback for it is tested.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient and no production defects were found.
