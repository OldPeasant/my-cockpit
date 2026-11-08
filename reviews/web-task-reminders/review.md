# Code Review

Date: 2026-11-09

Task: web-task-reminders - Reminders on tasks (web)

Scope: implementation commit 25d8f589d on top of task base f1b60527 (`git diff f1b60527..25d8f589d`); HEAD 75a4a8ec7 differs from 25d8f589d only in `tasks/web-task-reminders.md`

Requirements:
- tasks/web-task-reminders.md acceptance criteria and recorded assumption (presets mirror Android)
- SPEC.md section 4.6 (Reminders and notifications), section 4.2 API conventions
- AGENTS.md project notes (feature folder, locale, lint conventions)
- Android reference: android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskReminders.kt
- Existing web conventions: features/gtd/task-detail/task-checklist.ts, core/notifications

Git range:
- Branch: main
- HEAD: 75a4a8ec712ca56fd40114f66d8a9d1863821d57 (code identical to 25d8f589d80b5f05ab135363020ab83223099656)
- Base/start: f1b60527da71148ded53116921e7f7423e613727
- Commits reviewed: 1 code commit (25d8f589d), plus board-only commits faf57e942, b160a1866

Summary: 0 P0, 0 P1, 1 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Initial reminder load can overwrite an add or remove made while it was in flight**
    - File: `web/src/app/features/gtd/task-detail/task-reminders.ts:224`
    - Category: Correctness
    - Problem: `load(taskId)` (started from the constructor effect, line 135) ends with `this.reminders.set(sortByTime(reminders))` guarded only by the task id. The preset chips and the exact-time input are usable before the load returns (presets derive from the parent's `anchors`, not from the list). If the user adds a reminder and the GET (issued before the POST committed) resolves after the POST, the list is replaced by the older server snapshot and the new reminder disappears from the UI although it exists on the server. Likewise, a reminder removed optimistically during the load reappears after the GET resolves although it was deleted.
    - Why it matters: an older response overwrites newer state (the out-of-order case the review checklist names); the user sees a list that does not match the server and may add a duplicate or believe a delete failed.
    - Suggested fix: give the list a request identity or a "mutated since load started" marker (e.g. a counter bumped by `add`/`remove`, checked before `set` in `load`), or keep the add/remove controls disabled until the initial load has settled. Add a spec where the load resolves after an add.

## P3 Low

- [ ] **CR-2 | After a failed load the section claims "No reminders." and a later success hides the error**
    - File: `web/src/app/features/gtd/task-detail/task-reminders.html:40`
    - Category: Correctness
    - Problem: when `load` fails, `reminders` stays `[]`, so the template shows the error banner together with "No reminders.". The next successful `add` clears the error in `change()` (line 211) and shows a list containing only the new reminder, with nothing indicating that the existing reminders were never loaded. There is no retry for the load other than switching tasks or reloading the page.
    - Why it matters: the user can be misled into thinking the task has no other reminders and add duplicates. The sibling `task-checklist` behaves the same way, so this is consistent with existing code, hence low priority.
    - Suggested fix: track a load-failed state; hide the empty text (or show a retry action) while it is set, and do not clear it on an unrelated successful change.

- [ ] **CR-3 | Stale-response guards for task switches are untested**
    - File: `web/src/app/features/gtd/task-detail/task-reminders.spec.ts:240`
    - Category: Tests
    - Problem: the component has explicit guards so that an add, a failed change, or a load for the previous task does not touch the new task's list (`task-reminders.ts` lines 175, 214, 227-229), but the only task-switch spec ("reloads for another task") switches after everything has settled. No spec resolves a request for `t1` after the host switched to `t2`.
    - Why it matters: these guards are the regression-prone part of the component; a refactor could drop them without any test failing.
    - Suggested fix: add a spec with a deferred `addReminder`/`reminders` promise that resolves after `host.id.set('t2')` and assert the `t2` list and banner are unaffected.

## Review conclusion

Material issues found; fix before completing the task.

Notes from verification (not findings): presets, 09:00 default, the hour-before rule and future-only filtering match the Android implementation and the recorded assumption; the service calls use itemType TASK and channel NOTIFICATION as the generated API expects; a 404 on delete is treated as removed, as on Android; all 12 new message ids exist in both `messages.xlf` and `messages.de.xlf` (the `messages.xlf` deletions are context-group line moves only); browser notification firing and navigation to `item.deepLink` are handled by unchanged `core/notifications/reminder-notifications.service.ts`, and criterion 2 is recorded as skipped. Focused tests ran: `cd web && npx ng test --watch=false --include='src/app/features/gtd/task-detail/**/*.spec.ts'` -> 8 files, 343 tests passed. Criteria 1 and 4 had no recorded evidence yet at review time; that is the parent worker's step.
