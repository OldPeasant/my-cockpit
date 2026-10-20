# Test Report

Date: 2026-10-20

Task: web-reminders: Browser notifications for reminders

Scope: `git diff d6990d4949b5bb7f9160a0887d31e2f93a991f5b..350d39e2f992da42f5698f3845910e854e85ec43`. The parent gave these as the task commits: 2121dc58, 436c593e, 6167cae5, 15303cef, 350d39e2. The other commits in the range only change `tasks/*.md` (TFP bookkeeping). This is the second test pass. It runs on the final code after the fixes for review findings CR-1..CR-3 (`reviews/web-reminders/fixes.md`, commit 350d39e2). Uncommitted inputs at measurement: my spec additions in `reminder-notifications-toggle.spec.ts` and `general-settings.spec.ts` (listed below). The parent's uncommitted `reviews/web-reminders/review.md` is not a test input.

Requirements:
- `tasks/web-reminders.md`: acceptance criteria 1 and 2, plus the recorded assumptions. The switch is stored per browser in localStorage `cockpit.reminderNotifications`. Events come through `ChangeEventsService.messages$(name)`. When the switch is on and the permission is granted, the reminder is a system notification with tag `reminder-{id}`; otherwise it is an in-app snackbar with an Open action.
- SPEC.md §4.6
- `reviews/web-reminders/review.md` / `fixes.md` CR-1 (hint linked through `aria-describedby`, `disabledInteractive`), CR-2 (permission re-read on focus/visibility), CR-3 (fallback text shown only once)
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md

## Test files changed

In this pass (uncommitted):
- `web/src/app/core/notifications/reminder-notifications-toggle.spec.ts`
- `web/src/app/features/settings/general/general-settings.spec.ts`

Earlier in the task (committed in 2121dc58/15303cef/350d39e2): `browser-notifications.spec.ts`, `reminder-notifications.service.spec.ts`, `reminder-notifications-toggle.spec.ts`, `core/events/change-events.service.spec.ts`, `general-settings.spec.ts`, `app.spec.ts`, `app.i18n.spec.ts`.

## Tests added or updated

Count: 6 added in this pass, and no existing test was changed, weakened or skipped.
- toggle: `does not re-read the permission when the page is hidden` (CR-2, only `visible` triggers a refresh)
- toggle: `can be switched on without a prompt once the permission was allowed in the site settings` (CR-2 end to end: denied, then focus, then click enables with no second prompt)
- toggle: `gives each switch its own hint id` (CR-1, the ids are per instance and `aria-describedby` follows them)
- toggle: `ignores a second click while the browser asks` (CR-1. With `disabledInteractive` the switch stays clickable while the prompt is open, but a second click must not ask twice)
- toggle: `ignores clicks when the browser has no notifications` (CR-1, the `unsupported` state stays inert although focusable)
- general settings: `explains the in-app fallback only once while the switch is off` (CR-3)

Web suite total after this pass: 30 files, 501 tests. The fixer's run had 495.

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include 'src/app/core/notifications/**/*.spec.ts' --include 'src/app/core/events/*.spec.ts' --include 'src/app/features/settings/general/*.spec.ts'` -> 5 files, 117 tests passed.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: the permission is asked only from the settings toggle (user gesture), never on startup or when switching off | toggle: `asks for the permission when switched on, and is on once granted`, `switches off without asking`, `switches on without asking when the permission was granted before`; service: `does not ask again once the permission was refused or granted`; general settings: `switches browser notifications on from the settings page without saving a setting` | F | Pass |
| AC1 / permission `default` (prompt pending, dismissed, rejecting, double click) | service: `keeps the permission undecided and the switch off when the permission prompt fails`, `asks again on the next enable after the prompt was dismissed`, `does not use a stored switch while the permission is not granted`; toggle: `springs back but stays usable when the permission prompt fails`, `disables the switch and says to answer the prompt while the browser asks`, `ignores a second click while the browser asks` | F | Pass |
| AC1 / permission `granted` | service: `asks for the permission on enable and remembers the switch when granted`, `restores the stored switch, and disable clears it`, `picks up a permission granted in the browser meanwhile when a reminder fires` | F | Pass |
| AC1 / permission `denied` | service: `stays off when the permission is refused`, `falls back to a toast when the permission was revoked in the browser meanwhile`; toggle: `springs back and explains the block when the permission is refused`, `shows the permission refused earlier in the browser`, `shows a permission changed in the browser since the service started` | F | Pass |
| AC1 / permission `unsupported` | service: `cannot be switched on without Notification support`, `toasts every fired reminder while notifications are unsupported`; toggle: `is disabled when the browser has no notifications`, `ignores clicks when the browser has no notifications`; browser adapter: `reports unsupported`, `answers a permission request with denied`, `refuses to show a notification`; general settings: `offers the switch, explaining that this browser has no notifications (jsdom)` | F | Pass |
| AC1: a notification is shown on the `reminder` SSE event (title, body, one tag per reminder; a click focuses, closes and opens the deep link) | service: `listens to the reminder SSE event`, `shows a fired reminder as a browser notification when enabled, and a click opens the item`, `gives the notification a body and one tag per reminder`, `titles the notification "Reminder" when the item has no title`, `only focuses and closes on a notification click when the item is unavailable`; browser adapter tests | F | Pass |
| AC1: in-app toast fallback | service: `falls back to a toast with an Open action while notifications are off`, `falls back to a toast when the browser refuses to show the notification`, `toasts an unavailable item without a title or link`, `toasts the title of an unavailable item without the Open action`, `does not open the item before the toast action is used`, `toasts again after notifications were switched off` | F | Pass |
| Malformed events and stored state | service: `ignores a reminder event without an item`, `ignores a null reminder event`, `ignores a stored value other than on`, `starts switched off when reading the switch throws`, `keeps the switch for this session when storing it throws` | F | Pass |
| `ChangeEventsService.messages$(name)` for named SSE events | 9 tests in `change-events.service.spec.ts` (before login, pending reconnect, later connections, once per connection, names kept apart, routing, empty data, completes on destroy) | F | Pass |
| CR-1: the hint is linked to the switch, and the switch stays focusable but inert while disabled | toggle: `is described by its hint and stays focusable while disabled`, `gives each switch its own hint id`, `ignores a second click while the browser asks`, `ignores clicks when the browser has no notifications`; the `aria-disabled` assertions in the other toggle and general settings tests | F | Pass |
| CR-2: the permission is re-read on window focus and when the page becomes visible; the listeners are removed on destroy | toggle: `re-reads the permission when the window gets the focus back`, `re-reads the permission when the page becomes visible again`, `does not re-read the permission when the page is hidden`, `can be switched on without a prompt once the permission was allowed in the site settings`, `stops listening for focus and visibility once destroyed` | F | Pass |
| CR-3: the fallback text appears only once in the settings row | general settings: `explains the in-app fallback only once while the switch is off`; the zero-count assertion in `switches browser notifications on from the settings page without saving a setting` | F | Pass |
| Shell wiring (`provideReminderNotifications()` in `app.routes.ts`) | `app.spec.ts`, `app.i18n.spec.ts`; `provide-reminder-notifications.ts` and `app.routes.ts` executed 100% | `cd web && npm run coverage` | Pass |
| AC2: `cd web && npm run check` passes | whole web check | `cd web && npm run check` | Pass |
| A real browser shows a system notification (real Notification API, native prompt) | Playwright `web/e2e/reminders/notifications.spec.ts` (parent's commit 6167cae5) | n/a | Not run: it needs a real browser and the running stack. Only its typecheck (`e2e:typecheck` in `npm run check`) ran here |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include 'src/app/core/notifications/**/*.spec.ts' --include 'src/app/core/events/*.spec.ts' --include 'src/app/features/settings/general/*.spec.ts'`

Result:

Pass (5 files, 117 tests).

Format: `cd web && npx prettier --check src/app/core/notifications src/app/core/events src/app/features/settings/general` -> all files use Prettier code style.

## Full test execution

Command:

`cd web && npm run check`

Result:

Pass, exit 0. It ran api:check ("src/app/api/generated is up to date"), `ng lint --max-warnings=0` ("All files pass linting"), e2e:typecheck, test:ci (30 files, 501 tests passed) and `ng build`. The build gave its usual warning: the initial bundle is 590.01 kB against the 500 kB budget. This is a warning, not an error, and spec changes cannot affect it.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` ended with "== all checks passed" (server, web, android).

## Bugs found

None. Every new test passed against the final production code (350d39e2).

## Coverage

Status: verified (fresh run; 30 files and 501 tests passed; the v8 summary was printed and the reports were rewritten during the run).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured with a diff-intersection tool. Every measured in-scope file has 100% provider line coverage, so every executable added or modified line in those files was executed. Changed-branch coverage is not inferred from this.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-reminders`, base `d6990d4949b5bb7f9160a0887d31e2f93a991f5b`, measured HEAD `350d39e2f992da42f5698f3845910e854e85ec43`. The parent gave the task commits 2121dc58, 436c593e, 6167cae5, 15303cef and 350d39e2. The file list comes from `git diff --name-only d6990d49..HEAD -- web/src`, without specs.
- Source/test snapshot identifier (including uncommitted inputs): sha256 prefixes, taken before `npm run check`/coverage and checked again afterwards with `sha256sum -c`, unchanged: app.routes.ts 3280379f3e3d, change-events.service.ts abbee73c6c25, change-events.service.spec.ts 20e9fdd487f8, events/testing.ts 780e01314146, browser-notifications.ts c27b691af447, browser-notifications.spec.ts 83081621d575, notifications/index.ts 2f3f26b40e15, provide-reminder-notifications.ts ad5dcbb2337f, reminder-notifications.service.ts 4baf567f8fc0, reminder-notifications.service.spec.ts eeb7f6f7b780, reminder-notifications-toggle.ts f962c5df1642, reminder-notifications-toggle.html 3d4c7cadc747, reminder-notifications-toggle.spec.ts de3253ad5414 (uncommitted), notifications/testing.ts ecbe8049d7f4, general-settings.ts e10aab33e655, general-settings.html 74f92372191e, general-settings.spec.ts 8610df05f59d (uncommitted), app.spec.ts 3158145f42ed, app.i18n.spec.ts 5f6a1a3b107a.
- Exact included files; renamed/deleted/excluded files and reasons: included are `web/src/app/app.routes.ts`, `core/events/change-events.service.ts`, `core/notifications/browser-notifications.ts`, `core/notifications/provide-reminder-notifications.ts`, `core/notifications/reminder-notifications-toggle.ts`, `core/notifications/reminder-notifications.service.ts` and `features/settings/general/general-settings.ts`. Excluded:
  - `core/notifications/index.ts`: re-exports only, no executable code, absent from the v8 report.
  - `core/events/testing.ts` and `core/notifications/testing.ts`: test doubles, both 100%.
  - the `.html`/`.scss` templates: not instrumented separately; the DOM assertions check them.
  - `web/src/locale/messages.xlf`: generated i18n.
  - the spec files.

  There are no renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 with `@vitest/coverage-v8`, command `npm run coverage`, run in `web/`, full web unit suite.
- Run start/completion time and exit status: started 2026-10-20T17:13:45Z, tests started 19:13:51 +02:00 and took 15.50 s, finished 17:14:07Z. 30 files and 501 tests passed and the coverage summary was printed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 59527a884b3c5aa7ea30e7f0b681b1ffa7271d8126ab27aee6b3808d3a6c70a7 (mtime 2026-10-20 19:14:07.358 +0200); `web/coverage/web/coverage-final.json` sha256 3b78b8f1442e54a2309ecec5168fcd904049757310afd81378495dba77d73937 (mtime 19:14:07.363 +0200).
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report mtimes fall inside the run, and the input hashes matched before and after.
- Final run after latest test additions and production fixes: yes. It includes the CR-1..CR-3 fixes (350d39e2) and every test added in this pass.

Values below are provider-native counters from `coverage-summary.json`, shown as covered/total (percentage). A zero-denominator metric is N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/app.routes.ts` | 18/18 (100%) | N/A (0/0) | 17/17 (100%) | 18/18 (100%) |
| `web/src/app/core/events/change-events.service.ts` | 88/89 (98.88%) | 24/25 (96.00%) | 26/26 (100%) | 75/75 (100%) |
| `web/src/app/core/notifications/browser-notifications.ts` | 9/9 (100%) | 10/10 (100%) | 5/5 (100%) | 8/8 (100%) |
| `web/src/app/core/notifications/provide-reminder-notifications.ts` | 2/2 (100%) | N/A (0/0) | 2/2 (100%) | 2/2 (100%) |
| `web/src/app/core/notifications/reminder-notifications-toggle.ts` | 35/35 (100%) | 16/17 (94.12%) | 8/8 (100%) | 29/29 (100%) |
| `web/src/app/core/notifications/reminder-notifications.service.ts` | 58/58 (100%) | 31/31 (100%) | 14/14 (100%) | 49/49 (100%) |
| `web/src/app/features/settings/general/general-settings.ts` | 110/110 (100%) | 56/61 (91.80%) | 26/26 (100%) | 88/88 (100%) |
| **Aggregate (the 7 files above, summed counters)** | 320/321 (99.69%) | 137/144 (95.14%) | 98/98 (100%) | 269/269 (100%) |

The aggregate leaves out `core/notifications/index.ts` (no executable code, not in the report) and the two `testing.ts` test doubles (5/5 and 16/16 statements, both 100%). No scoped file is missing instrumentation.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/core/events/change-events.service.ts:100` (true branch of `if (this.source \|\| this.reconnectTimer !== undefined) return;`) | Guard against connecting twice. The line predates this task, and the task did not change it. | Left uncovered: not task behavior, and it is hard to reach because `connect()` only runs on login-state changes. |
| `web/src/app/core/notifications/reminder-notifications-toggle.ts:28[0]` (`export class ReminderNotificationsToggle {`) | A branch that v8 maps to the class declaration (Angular compiler output), not a source condition | Left uncovered: no source-level behavior behind it. Every condition in the constructor listeners (`visible` / `hidden`, focus, destroy), in `change()`, `checked` and `blocked` is executed and asserted. |
| `web/src/app/features/settings/general/general-settings.ts:74[0], 149[1], 150[1], 192[1], 202[1]` | Settings save/queue logic that predates this task. In this file the task only imports the toggle. | Left uncovered: outside this task's behavior. The reminder row is covered by 3 general settings tests. |

## Unable to test

- A real browser showing a system notification and the native permission prompt cannot run in Vitest/jsdom, which has no Notification API. The adapter is tested against a stubbed global `Notification`. Follow-up: run `web/e2e/reminders/notifications.spec.ts` with Playwright against the stack the criterion names. This report did not run it.
- Chrome on Android refusing `new Notification()` is only simulated (a throwing constructor, `refuseToShow`). The toast fallback for that case is tested.
- How a screen reader actually announces the `aria-describedby` hint (CR-1) is not tested. Only the DOM wiring is (ids, `aria-describedby`, `aria-disabled`, `tabIndex`).

## Summary

Tests added and all validation passed. Task-scoped coverage of the final revision (350d39e2) is sufficient, and no production defects were found.
