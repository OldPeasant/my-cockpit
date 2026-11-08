# Code Review

Date: 2026-11-09

Task: web-task-reminders - Reminders on tasks (web)

Scope: re-review of the final code, `git diff f1b60527..69f24631e -- web` (implementation ef7f2bedf, tests 358e07dd6, fixes 69f24631e), including a check of the fixes for the earlier findings CR-1..CR-3 described in `reviews/web-task-reminders/fixes.md`

Requirements:
- tasks/web-task-reminders.md acceptance criteria and recorded assumptions (presets mirror Android, notification firing not manually verified)
- SPEC.md section 4.6 (Reminders and notifications), section 4.2 API conventions
- AGENTS.md project notes (feature folder, locale, lint conventions)
- Existing web conventions: features/gtd/task-detail/task-checklist.ts, core/notifications

Git range:
- Branch: main
- HEAD: 69f24631e4aa64fcb2ecb8e76db70438e1a5e99f
- Base/start: f1b60527da71148ded53116921e7f7423e613727
- Commits reviewed: 3 code commits (ef7f2bedf, 358e07dd6, 69f24631e), plus board-only commits that touch only `tasks/`

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Status of earlier findings

- CR-1 (initial load overwriting an add/remove): fixed. `loadState` is set to `loading` at the start of every load, `locked` (busy or loading) disables the chips, the exact-add button and the remove buttons, and `add()` / `remove()` return early while locked, so the Enter path is covered too. Changes stay possible after a failed load, when no load is in flight. Covered by the specs "keeps the controls disabled until the list has loaded, so it cannot overwrite an add" and "does not let a reloading list bring back a reminder removed meanwhile".
- CR-2 (failed load shown as "No reminders.", no retry): fixed. The empty text shows only for `loaded`; a Retry button is shown while `failed` and survives an unrelated successful add (`change()` clears only the banner). The new i18n unit `taskDetail.reminder.retry` exists in `messages.xlf` and in `messages.de.xlf` with a German target; the other `messages.xlf` changes are line-number moves only. Covered by "offers a retry instead of claiming there are none when loading fails".
- CR-3 (task-switch guards untested): resolved by the test-writer's deferred-promise specs for late add, late add failure, late remove failure, late load and late load failure of the previous task.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Load results are matched by task id only, so a superseded load for the same task can settle the state**
    - File: `web/src/app/features/gtd/task-detail/task-reminders.ts:239`
    - Category: Correctness
    - Problem: `load()` accepts a response when `taskId === this.taskId()`. If the user goes from task A to B and back to A while A's first GET is still pending, two loads for A run. Whichever resolves first sets `loadState` to `loaded`, which unlocks the controls while the other load is still pending; that later response then replaces the list, and if it was issued before an add or remove made in between, it can drop the added reminder or bring back a removed one (the same overwrite CR-1 of the first review fixed for the single-load case). Narrow: it needs a navigation away and back within one request's latency.
    - Why it matters: an older response can still overwrite newer state in this path; a request identity closes it completely.
    - Suggested fix: keep a load counter (incremented in `load()`), and only apply a result whose counter is still the latest, instead of comparing task ids.

- [ ] **CR-2 | Retry button looks enabled while a change is running but ignores clicks**
    - File: `web/src/app/features/gtd/task-detail/task-reminders.html:300`
    - Category: Quality
    - Problem: after a failed load the user may add a reminder; while that add is in flight `retry()` returns early because `locked()` is true, but the Retry button has no `[disabled]` binding, unlike the chips and the other buttons.
    - Why it matters: a click is silently dropped with no visual feedback; inconsistent with the other controls in the section.
    - Suggested fix: add `[disabled]="locked()"` to the Retry button.

## Review conclusion

No material issues found.

Notes from verification (not findings): presets, the 09:00 default for dates without a time, the hour-before rule and future-only filtering match the recorded assumption; the service uses `listReminders` with itemType TASK and itemId, and `createReminder` with itemType TASK and channel NOTIFICATION; a 404 on delete counts as removed; the component sits in the task detail with the saved task as anchors, inside the task's touches (`web/src/app/features/gtd/task-detail`, `web/src/locale`). Browser notification firing and navigation use the unchanged `core/notifications`; criterion 2 is recorded as skipped with a reason. Focused tests ran on 69f24631e: `npm --prefix web exec -- ng test --watch=false --include='src/app/features/gtd/task-detail/**/*.spec.ts'` -> 8 files, 365 tests passed. Gradle and `sh scripts/check.sh` were not run by this reviewer (instructed not to run Gradle); criteria 1 and 4 still need their evidence recorded by the parent worker.
