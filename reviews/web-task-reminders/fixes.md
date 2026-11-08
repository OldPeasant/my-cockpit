# Fix Report

Date: 2026-11-09

Task: web-task-reminders - Reminders on tasks (web)

Scope: current task; findings CR-1, CR-2, CR-3 from `reviews/web-task-reminders/review.md` (base f1b60527da71148ded53116921e7f7423e613727, HEAD 358e07dd6 plus the uncommitted fixes below)

## Fixed findings

### CR-1 — Initial reminder load can overwrite an add or remove made while it was in flight

Files changed:
- `web/src/app/features/gtd/task-detail/task-reminders.ts`
- `web/src/app/features/gtd/task-detail/task-reminders.html`
- `web/src/app/features/gtd/task-detail/task-reminders.spec.ts`

Problem:
The preset chips, the exact-time add and the remove buttons could be used before `load()` returned. A GET that answered after the POST/DELETE replaced the list with the older snapshot, so an added reminder disappeared or a removed one came back.

Fix:
New `loadState` signal (`loading` / `loaded` / `failed`), set to `loading` at the start of every load (task switch or retry) and settled only by the load for the current task. A computed `locked` (busy or loading) replaces `busy` in the disabled bindings of the chips, the exact-add button and the remove buttons, and `add()` / `remove()` return early while it is set, so the Enter key path is covered too. Changes stay allowed after a failed load, since no load is in flight then.

Validation:
- New spec "keeps the controls disabled until the list has loaded, so it cannot overwrite an add" (load gated with a deferred promise; chip and exact button disabled, Enter and a direct `add()` call send nothing; after the load resolves the list shows the server reminder and a later add appends to it)
- New spec "does not let a reloading list bring back a reminder removed meanwhile" (while a retry load is pending the remove button is disabled and `remove()` sends nothing)
- `npm --prefix web run test:ci -- --include='src/app/features/gtd/task-detail/**/*.spec.ts' --include='src/locale/**/*.spec.ts'` — Pass

### CR-2 — After a failed load the section claims "No reminders." and a later success hides the error

Files changed:
- `web/src/app/features/gtd/task-detail/task-reminders.ts`
- `web/src/app/features/gtd/task-detail/task-reminders.html`
- `web/src/app/features/gtd/task-detail/task-reminders.spec.ts`
- `web/src/locale/messages.xlf`
- `web/src/locale/messages.de.xlf`

Problem:
A failed load left an empty list, so "No reminders." showed next to the error, and nothing offered to load again. A later successful add cleared the banner, leaving no hint that the other reminders were never loaded.

Fix:
"No reminders." shows only when the load succeeded (`loadState === 'loaded'`), not while loading or after a failure. While `loadState` is `failed`, a "Retry" button (i18n id `taskDetail.reminder.retry`) is shown. It stays visible after an unrelated successful add (`change()` clears only the banner, not the load state) and reloads the list via `retry()`. English unit added by `npm run i18n:extract` (diff checked: only the new unit plus line-number moves in this component); German unit "Erneut versuchen" inserted by hand into `messages.de.xlf` with `target state="translated"`.

Validation:
- New spec "offers a retry instead of claiming there are none when loading fails" (no empty text, Retry shown, still shown after a successful add, a successful retry shows the server list and hides Retry and the banner)
- `npm --prefix web run test:ci -- --include='src/app/features/gtd/task-detail/**/*.spec.ts' --include='src/locale/**/*.spec.ts'` — Pass (including the locale spec)

### CR-3 — Stale-response guards for task switches are untested

Files changed:
- none (covered by the test-writer)

Problem:
At review time no spec answered a request for the previous task after the task switched.

Fix:
No change needed: the test-writer's specs in `task-reminders.spec.ts` now cover each guard with deferred promises: "does not add a reminder to another task when the task changed while adding", "shows no error on another task when an add of the previous one fails late", "does not put a reminder back on another task when a remove fails late", "ignores a late answer for the previous task when loading", "ignores a late load failure for the previous task". These specs still pass after the CR-1 and CR-2 changes.

Validation:
- `npm --prefix web run test:ci -- --include='src/app/features/gtd/task-detail/**/*.spec.ts' --include='src/locale/**/*.spec.ts'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `npm --prefix web run test:ci -- --include='src/app/features/gtd/task-detail/**/*.spec.ts' --include='src/locale/**/*.spec.ts'` (same as `cd web && npx ng test --watch=false ...`) — 9 files, 375 tests passed

Full test suite:
- Not run (the parent runs the full web suite and `.taskcheck`)

Lint/type-check/build:
- `npm --prefix web run lint` (`ng lint --max-warnings=0`) — All files pass linting
- `npx prettier --write` on the three edited component files — unchanged (already formatted)
- Type-check runs as part of the vitest build above — Pass

`.taskcheck`:
- Not run (it includes Gradle, which the parent told this helper not to run)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.

---

# Fix Report (round 2)

Date: 2026-11-09

Task: web-task-reminders - Reminders on tasks (web)

Scope: current task; findings TW-1 from `reviews/web-task-reminders/tests.md` and CR-1, CR-2 (P3) from the re-review in `reviews/web-task-reminders/review.md` (base f1b60527da71148ded53116921e7f7423e613727, HEAD 428d572f0 plus the uncommitted fixes below)

## Fixed findings

### TW-1 / CR-1 — A load of an earlier visit of the same task can settle the state and overwrite the list

Files changed:
- `web/src/app/features/gtd/task-detail/task-reminders.ts`
- `web/src/locale/messages.xlf` (line-number moves only, from `npm run i18n:extract`)

Problem:
`load()` accepted a result when its task id matched the current one. After t1 -> t2 -> t1 with the first t1 GET still pending, two loads for t1 ran: the first to answer unlocked the controls, and the late one then replaced the list (dropping a reminder added meanwhile) or showed its failure. The two test-writer specs "ignores a late answer from an earlier visit of the same task" and "ignores a late load failure from an earlier visit of the same task" failed.

Fix:
A private `loadSeq` counter, incremented at the start of every `load()` (task switch or retry), as `TaskDetail.load` does. A success or failure is applied, and `loadState` settled (which unlocks the controls), only when its sequence number is still the latest. The two specs are unchanged.

Validation:
- `npm --prefix web run test:ci -- --include='src/app/features/gtd/task-detail/**/*.spec.ts' --include='src/locale/**/*.spec.ts'` — Pass (both TW-1 specs now pass)

### CR-2 — Retry button looks enabled while a change is running but ignores clicks

Files changed:
- `web/src/app/features/gtd/task-detail/task-reminders.html`
- `web/src/app/features/gtd/task-detail/task-reminders.spec.ts`

Problem:
`retry()` returns early while `locked()` is true, but the Retry button had no `[disabled]` binding, so a click during a pending add was silently dropped.

Fix:
Added `[disabled]="locked()"` to the Retry button, like the chips and the other buttons. The spec "does not reload while an add after a failed load is pending" now also asserts that Retry is disabled while the add is pending and enabled again after it finished.

Validation:
- `npm --prefix web run test:ci -- --include='src/app/features/gtd/task-detail/**/*.spec.ts' --include='src/locale/**/*.spec.ts'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `npm --prefix web run test:ci -- --include='src/app/features/gtd/task-detail/**/*.spec.ts' --include='src/locale/**/*.spec.ts'` — 9 files, 380 tests passed (run again after the i18n extract, same result)

Full test suite:
- Not run (the parent runs the full web suite and `.taskcheck`)

Lint/type-check/build:
- `npm --prefix web run lint` — All files pass linting
- `npm --prefix web exec -- prettier --check` on the three edited component files — All matched files use Prettier code style
- `npm --prefix web run i18n:extract` — only line-number moves for `task-reminders.ts` / `task-reminders.html` in `messages.xlf`; no new or changed units, `messages.de.xlf` untouched
- Type-check runs as part of the vitest build above — Pass

`.taskcheck`:
- Not run (it includes Gradle, which the parent told this helper not to run)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
