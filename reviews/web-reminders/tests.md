# Test Report

Date: 2026-10-20

Task: web-reminders: Browser notifications for reminders

Scope: the task commits on top of `a401c4eb470b20cd5ca2cf0309aec679e0bb8078`, up to HEAD `44eb8e8fd2c17c1b9d7335df00b492f8a30da2b2`. The parent rebased the branch again, so `git merge-base HEAD origin/main` is now `7cbea514b8632a5650ddb4bd948f6fb038896de4`. That is a TFP bookkeeping commit below `a401c4eb`, and the commits between them touch only `tasks/`. The 10 task-owned commits are `302c4484`..`44eb8e8f`. The parent's original base was `8984a6af4c1635ea2baa9d8da162ea407de3ddca`. `git diff --stat a401c4eb HEAD` gives the same production file list as the earlier passes (`web/src` plus `messages.xlf`), together with the task's specs, the e2e spec and `reviews/web-reminders/`.

This is the fourth, final test pass. The only change since the third pass (written against `b2047037`) is the last task commit. The parent called it 8f70d8bb; after the rebase it is `44eb8e8f`. It commits the 3 service spec tests from the third pass and adds 4 lines to `web/e2e/reminders/notifications.spec.ts`. `git diff b2047037 HEAD` shows no change in this task's production files. The other differences come from main (`cal-local-api` server code and generated `web/src/app/api/generated/fn/calendars/*` and `models/*` files), and none of them are task files.

Uncommitted inputs at measurement: none of the test or source files. The only uncommitted files are `reviews/web-reminders/review.md` and this report, and neither is a test input.

Requirements:
- `tasks/web-reminders.md`: acceptance criteria 1 and 2, plus the recorded assumptions. The switch is stored per browser in localStorage `cockpit.reminderNotifications`. Events come through `ChangeEventsService.messages$(name)`. When the switch is on and the permission is granted, the reminder is a system notification with tag `reminder-{id}`; otherwise it is an in-app snackbar with an Open action.
- SPEC.md §4.6
- `reviews/web-reminders/review.md` / `fixes.md`: CR-1..CR-3 from the first review, and the second review's CR-1 (toasts in a burst must not replace each other)
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md "Evidence"

## Test files changed

In this pass: none. I reviewed the parent's e2e change (below) and did not edit it.

Earlier in the task (all committed now): `browser-notifications.spec.ts`, `reminder-notifications.service.spec.ts`, `reminder-notifications-toggle.spec.ts`, `core/events/change-events.service.spec.ts`, `general-settings.spec.ts`, `app.spec.ts`, `app.i18n.spec.ts`, `web/e2e/reminders/notifications.spec.ts`.

## Tests added or updated

Count: 0 in this pass. Earlier passes added the unit tests listed under "Requirement coverage". The 3 tests from the third pass (`waits for an app message that replaced another app message, then shows the next reminder`, `queues a reminder that fires while an app message holds the queue, behind the waiting ones` and `shows no queued reminder once the app is destroyed while an app message holds the queue`) are committed unchanged: the spec's sha256 prefix is still `63062f5e8c6a`. No existing test was changed, weakened or skipped.

### Review of the e2e change in 44eb8e8f

The toast test now waits until the settings switch (`Show reminders as browser notifications`) is visible before it calls `remindNow`.

The change is sound:
- It only adds a wait. The assertions are unchanged and nothing was loosened. It does not touch production code.
- The wait means something. `general-settings.html` renders the form, including `<app-reminder-notifications-toggle />`, only once `loading()` is false. So a visible switch means the app's settings request has completed and the shell has started, including its first API responses.
- It matches the first e2e test. That test already waited on the same switch (`toHaveAttribute('aria-checked', 'false')`) and then a click before it called `remindNow`, and it never hit the 403.
- It is the Playwright pattern of waiting for a user-visible state. It uses no fixed sleep and no `networkidle`. `networkidle` would be unreliable here because the SSE stream stays open.

Residual risk (low, no change required): the wait is a readiness signal, not a lock on the cookie. The server (`SecurityConfiguration`, `SessionCookies`) issues a fresh `XSRF-TOKEN` on any response to a request that arrived without one. It also rotates the token on login and logout. If a startup request that the app sent without the cookie answered after the settings response, it could still replace the cookie between `xsrfHeader()` and the POST. `remindNow` already reads the cookie again before each POST, which narrows the window. If this test is ever flaky with a 403 again, the robust fix is in the helper: retry once on 403 with a freshly read cookie, or make the calls from inside the page so that Angular's interceptor sets the header. I did not reproduce the original root cause. The parent's evidence is the 403 before the change and 11/11 passes after it.

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include 'src/app/core/notifications/**/*.spec.ts' --include 'src/app/core/events/*.spec.ts' --include 'src/app/features/settings/general/*.spec.ts'` -> 5 files, 130 tests passed (this pass, on HEAD 44eb8e8f).

E2E command (E), run by the parent, not by this pass: `cd web && npm run e2e -- e2e/reminders/notifications.spec.ts --repeat-each=3 --workers=1` -> 11 passed. These are the 2 reminder tests x 3 repeats plus the 5 phase demos the e2e script runs. Before the change, the toast test failed with a 403.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: the permission is asked only from the settings toggle (user gesture), never on startup or when switching off | toggle: `asks for the permission when switched on, and is on once granted`, `switches off without asking`, `switches on without asking when the permission was granted before`; service: `does not ask again once the permission was refused or granted`; general settings: `switches browser notifications on from the settings page without saving a setting` | F | Pass |
| AC1 / permission `default` (prompt pending, dismissed, rejecting, double click) | service: `keeps the permission undecided and the switch off when the permission prompt fails`, `asks again on the next enable after the prompt was dismissed`, `does not use a stored switch while the permission is not granted`; toggle: `springs back but stays usable when the permission prompt fails`, `disables the switch and says to answer the prompt while the browser asks`, `ignores a second click while the browser asks` | F | Pass |
| AC1 / permission `granted` | service: `asks for the permission on enable and remembers the switch when granted`, `restores the stored switch, and disable clears it`, `picks up a permission granted in the browser meanwhile when a reminder fires` | F | Pass |
| AC1 / permission `denied` | service: `stays off when the permission is refused`, `falls back to a toast when the permission was revoked in the browser meanwhile`; toggle: `springs back and explains the block when the permission is refused`, `shows the permission refused earlier in the browser`, `shows a permission changed in the browser since the service started` | F | Pass |
| AC1 / permission `unsupported` | service: `cannot be switched on without Notification support`, `toasts every fired reminder while notifications are unsupported`; toggle: `is disabled when the browser has no notifications`, `ignores clicks when the browser has no notifications`; browser adapter tests; general settings: `offers the switch, explaining that this browser has no notifications (jsdom)` | F | Pass |
| AC1: a notification is shown on the `reminder` SSE event (title, body, one tag per reminder; a click focuses, closes and opens the deep link) | service: `listens to the reminder SSE event`, `shows a fired reminder as a browser notification when enabled, and a click opens the item`, `gives the notification a body and one tag per reminder`, `titles the notification "Reminder" when the item has no title`, `only focuses and closes on a notification click when the item is unavailable`, `shows a burst of reminders as one browser notification each, without toasts` | F | Pass |
| AC1: in-app toast fallback | service: `falls back to a toast with an Open action while notifications are off`, `falls back to a toast when the browser refuses to show the notification`, `toasts an unavailable item without a title or link`, `toasts the title of an unavailable item without the Open action`, `does not open the item before the toast action is used`, `toasts again after notifications were switched off` | F | Pass |
| AC1 toast fallback with several reminders (second review CR-1 and its follow-up): queued in order, each toast for its full duration, its own Open link, an app message that replaces a reminder toast keeps its duration, queue drained on destroy | service: `shows reminders that fire together one after the other, in order`, `opens the item of a queued reminder from its own toast`, `opens the item of the toast on screen, not of the reminder queued behind it`, `toasts a reminder that fires after the queue ran empty right away`, `drops queued reminder toasts when the app is destroyed`, `notifies at once while a reminder toast is still queued, once notifications work`, `moves on to the queued reminder when an app message replaces the reminder toast`; real MatSnackBar: `shows a burst of reminders one after the other, each for its full duration`, `keeps an app message on screen that replaced a reminder toast, then shows the next reminder`, and the 3 tests from the third pass | F | Pass |
| Malformed events and stored state | service: `ignores a reminder event without an item`, `ignores a null reminder event`, `ignores a stored value other than on`, `starts switched off when reading the switch throws`, `keeps the switch for this session when storing it throws` | F | Pass |
| `ChangeEventsService.messages$(name)` for named SSE events | 9 tests in `change-events.service.spec.ts` | F | Pass |
| First review CR-1..CR-3 (hint linked, focusable but inert while disabled; permission re-read on focus/visibility; fallback text shown only once) | toggle and general settings tests (unchanged since the second pass) | F | Pass |
| Shell wiring (`provideReminderNotifications()` in `app.routes.ts`) | `app.spec.ts`, `app.i18n.spec.ts`; `provide-reminder-notifications.ts` and `app.routes.ts` executed 100% | `cd web && npm run coverage` | Pass |
| AC2: `cd web && npm run check` passes | whole web check | `cd web && npm run check` | Pass (this pass, HEAD 44eb8e8f) |
| AC1 in a real browser against the e2e stack: the settings switch asks for the permission from the click, a fired reminder shows as a browser notification (Notification API stubbed, because headless Chromium shows no prompt and no notification), and without notifications a toast opens the item | `web/e2e/reminders/notifications.spec.ts`: `the settings switch asks from the click, then a fired reminder shows as a browser notification`, `without browser notifications, a fired reminder shows as a toast that opens the item` | E (parent) | Pass, as reported by the parent: 11 passed with `--repeat-each=3 --workers=1`. Not rerun here. This pass checked the change by review, and its typecheck ran in `npm run check` |
| A real system notification with the native permission prompt (no stub) | none | n/a | Not testable here: headless Chromium shows neither. The e2e test stubs `window.Notification` |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include 'src/app/core/notifications/**/*.spec.ts' --include 'src/app/core/events/*.spec.ts' --include 'src/app/features/settings/general/*.spec.ts'`

Result:

Pass (5 files, 130 tests), on HEAD 44eb8e8f.

## Full test execution

Command:

`cd web && npm run check`

Result:

Pass, exit 0, on HEAD 44eb8e8f. It ran api:check ("src/app/api/generated is up to date."), `ng lint --max-warnings=0` ("All files pass linting."), e2e:typecheck (covers the changed e2e spec), test:ci (33 files, 549 tests passed) and `ng build`. The build gave its usual budget warning: the initial bundle is 599.10 kB against the 500 kB budget. This is a warning, not an error. It grew from 596.53 kB because main merged the generated calendar API client, not because of this task.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` (repo root) exited 0 on HEAD 44eb8e8f and printed "== all checks passed". The web part reported 33 files and 549 tests passed.

## Bugs found

None. Every test passed against the final production code.

Observation, not a defect against the criteria (unchanged from the third pass): the queue waits for an app message only for reminders that are already queued. When no reminder toast is shown or waited for, a newly fired reminder still opens at once and so replaces an app message that is on screen. Neither SPEC.md nor the task defines a priority between the two, so I did not write a test that requires either behavior. The parent can decide whether this needs an assumption entry.

## Coverage

Status: verified (fresh run in this pass on HEAD 44eb8e8f, exit 0; 33 files and 549 tests passed).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured with a diff-intersection tool. Every scoped file with changed code has 100% provider line coverage, so every executable added or modified line in them was executed. Changed-branch coverage is not inferred from this.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-reminders`. The parent's original base is `8984a6af4c1635ea2baa9d8da162ea407de3ddca`. After the rebase, the task commits are `302c4484`..`44eb8e8f` on top of `a401c4eb470b20cd5ca2cf0309aec679e0bb8078`. The current `git merge-base HEAD origin/main` is `7cbea514b8632a5650ddb4bd948f6fb038896de4`, and the commits from there up to `a401c4eb` touch only `tasks/`. The measured HEAD is `44eb8e8fd2c17c1b9d7335df00b492f8a30da2b2`. The file list comes from `git diff --stat a401c4eb HEAD`, without specs, reviews and generated i18n, and it is the same as in the earlier passes.
- Source/test snapshot identifier (including uncommitted inputs): all inputs are committed at 44eb8e8f. sha256 prefixes were taken before and after the run and are unchanged: app.routes.ts 3280379f3e3d, change-events.service.ts abbee73c6c25, events/index.ts b2da4a386d37, events/testing.ts 780e01314146, browser-notifications.ts c27b691af447, browser-notifications.spec.ts 83081621d575, notifications/index.ts 2f3f26b40e15, provide-reminder-notifications.ts ad5dcbb2337f, reminder-notifications.service.ts 15c2c3063570, reminder-notifications.service.spec.ts 63062f5e8c6a, reminder-notifications-toggle.ts f962c5df1642, reminder-notifications-toggle.html 3d4c7cadc747, reminder-notifications-toggle.spec.ts de3253ad5414, notifications/testing.ts ecbe8049d7f4, general-settings.ts e10aab33e655, general-settings.html 74f92372191e, general-settings.spec.ts 8610df05f59d, change-events.service.spec.ts 20e9fdd487f8, app.spec.ts e30d23924cbc, app.i18n.spec.ts 5f6a1a3b107a, e2e/reminders/notifications.spec.ts 0e1d95311d18. The unit-test inputs are byte-identical to the third pass's snapshot.
- Exact included files; renamed/deleted/excluded files and reasons: included are `web/src/app/app.routes.ts`, `core/events/change-events.service.ts`, `core/notifications/browser-notifications.ts`, `core/notifications/provide-reminder-notifications.ts`, `core/notifications/reminder-notifications-toggle.ts`, `core/notifications/reminder-notifications.service.ts` and `features/settings/general/general-settings.ts`. Excluded:
  - `core/notifications/index.ts`: re-exports only, no executable code, absent from the v8 report.
  - `core/events/testing.ts` and `core/notifications/testing.ts`: test doubles, both 100% (5/5 and 16/16 statements).
  - the `.html`/`.scss` templates: not instrumented separately; the DOM assertions check them.
  - `web/src/locale/messages.xlf`: generated i18n.
  - the spec files and `web/e2e/reminders/notifications.spec.ts`: tests, not production code.

  There are no renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 with `@vitest/coverage-v8` 5.0.5, command `npm run coverage`, run in `web/`, full web unit suite.
- Run start/completion time and exit status: started 2026-10-20T19:00:10Z and finished 19:00:27Z, exit 0. 33 files and 549 tests passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 5dad433a78da89564fc83798a41cfd50be2be4799b3f206034b07b3446c4962f (mtime 2026-10-20 21:00:27.758 +0200); `web/coverage/web/coverage-final.json` sha256 e6bbf9860ff536e21f876ba9a2891204b7ec07f2ee929889835f7cf954427344 (mtime 21:00:27.762 +0200).
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report mtimes fall inside the run, the input hashes matched before and after, and HEAD was 44eb8e8f before and after.
- Final run after latest test additions and production fixes: yes. There were no production or unit-test changes after this run.

Values below are provider-native counters from `coverage-summary.json`, shown as covered/total (percentage). A zero-denominator metric is N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/app.routes.ts` | 18/18 (100%) | N/A (0/0) | 17/17 (100%) | 18/18 (100%) |
| `web/src/app/core/events/change-events.service.ts` | 88/89 (98.87%) | 24/25 (96.00%) | 26/26 (100%) | 75/75 (100%) |
| `web/src/app/core/notifications/browser-notifications.ts` | 9/9 (100%) | 10/10 (100%) | 5/5 (100%) | 8/8 (100%) |
| `web/src/app/core/notifications/provide-reminder-notifications.ts` | 2/2 (100%) | N/A (0/0) | 2/2 (100%) | 2/2 (100%) |
| `web/src/app/core/notifications/reminder-notifications-toggle.ts` | 35/35 (100%) | 16/17 (94.11%) | 8/8 (100%) | 29/29 (100%) |
| `web/src/app/core/notifications/reminder-notifications.service.ts` | 78/78 (100%) | 37/37 (100%) | 19/19 (100%) | 63/63 (100%) |
| `web/src/app/features/settings/general/general-settings.ts` | 110/110 (100%) | 56/61 (91.80%) | 26/26 (100%) | 88/88 (100%) |
| **Aggregate (the 7 files above, summed counters)** | 340/341 (99.71%) | 143/150 (95.33%) | 103/103 (100%) | 283/283 (100%) |

The aggregate leaves out `core/notifications/index.ts` (no executable code, not in the report) and the two `testing.ts` test doubles (both 100%). No scoped file is missing instrumentation. The e2e spec does not contribute to these numbers.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/core/events/change-events.service.ts:100` (true branch of `if (this.source \|\| this.reconnectTimer !== undefined) return;`) | Guard against connecting twice. It predates this task, and the task did not change it. | Left uncovered: not task behavior, and hard to reach because `connect()` only runs on login-state changes. |
| `web/src/app/core/notifications/reminder-notifications-toggle.ts:28[0]` (`export class ReminderNotificationsToggle {`) | A branch that v8 maps to the class declaration (Angular compiler output), not to a source condition | Left uncovered: no source-level behavior behind it. |
| `web/src/app/features/settings/general/general-settings.ts:74[0], 149[1], 150[1], 192[1], 202[1]` | Settings save/queue logic that predates this task. In this file the task only imports the toggle. | Left uncovered: outside this task's behavior. The reminder row is covered by 3 general settings tests. |

## Unable to test

- A real system notification with the native permission prompt: headless Chromium shows neither, so the e2e test stubs `window.Notification`. The e2e run covers the real stack: SSE, the server's reminder poller, the settings page, the toast and its Open navigation. It does not cover the operating system's notification surface. No follow-up is needed unless a manual check on a desktop browser is wanted.
- This pass did not rerun the e2e suite. Its result is the parent's run (above). This pass reviewed the change and typechecked it.
- Chrome on Android refusing `new Notification()` is only simulated (a throwing constructor, `refuseToShow`). The toast fallback for that case is tested.
- `_openedSnackBarRef` is an internal (underscore) MatSnackBar member. The real-MatSnackBar tests pin its behavior for `@angular/material` ~22.2.3. An upgrade that changes it would make those tests fail, which is the intended alarm.
- How a screen reader actually announces the `aria-describedby` hint is not tested. Only the DOM wiring is.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. On final HEAD 44eb8e8f, focused tests (130), `npm run check` (549 tests), `sh scripts/check.sh` and a fresh coverage run all passed. The e2e change in 44eb8e8f is a sound, test-only readiness wait. Its e2e pass (11/11 with `--repeat-each=3`) was run by the parent.
