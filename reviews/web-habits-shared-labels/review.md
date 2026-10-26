# Code Review

Date: 2026-10-26

Task: web-habits-shared-labels - Share habit check label/error helpers between Habits view and Today

Scope: task commit 00a417b0 on top of base 9890aa29d3d2627e01210f7659eefc5e0c0cb33b, limited to `web/src/app/features/habits/habit-display.ts`, `web/src/app/features/habits/habits-page.ts`, `web/src/app/features/gtd/today/today-habits.ts` (plus their specs as evidence)

Requirements:
- tasks/web-habits-shared-labels.md acceptance criteria (1) one shared implementation of the check label and error message, used by both components; (2) existing Vitest tests of both still pass, `cd web && npm run check` passes
- reviews/web-today-habits/review.md CR-3 (origin of the task)
- AGENTS.md project notes (feature folders, touches, i18n/lint conventions)
- Parent instructions: i18n ids unchanged, maintainability

Git range:
- Branch: main
- HEAD: 00a417b0
- Base/start: 9890aa29d3d2627e01210f7659eefc5e0c0cb33b
- Commits reviewed: 1 (00a417b0 "web-habits-shared-labels: share habit check label and error message helpers")
- Uncommitted changes: not checked (see limitation below)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Review limitation

The Bash tool was denied in this review session, so I could not run `git diff 9890aa29..00a417b0`, `git log`, `git status` or the tests. I reviewed the current files in the working tree (HEAD 00a417b0 according to the session's git snapshot) in full, plus their specs. I could not see the removed lines. This affects one check:

- **i18n ids unchanged.** The shared helpers use `@@habits.check`, `@@habits.uncheck`, `@@habits.checkCount` and `@@habits.error` (`habit-display.ts:43,44,48,55`). `habits-page.ts` has no `$localize` copies of these left, and `today-habits.ts` has no `$localize` calls at all. So the Habits view now uses one id per message. I could not confirm from the old code whether the removed Today copies used these ids or their own (for example `@@gtd.today.*`). Impact is nil either way: `web/src/locale/messages.xlf` has no `habits.*` or `gtd.today.*` units, and there is no translation file, so no translation can be lost. The parent should still run `git diff 9890aa29..00a417b0 | grep '@@'` to confirm that the removed lines have no ids missing from the added lines, and record the result.

I did not run the test suites myself. Criterion 2 needs the parent's or the test-writer's recorded `npm run check` run.

## Verification

- **Criterion 1 (one shared implementation, used by both):** met in the current code.
  - `habit-display.ts:38-56` defines `checkLabel(habit)` and `errorMessage(error)` once.
  - `habits-page.ts:15-25` imports both. The component uses `checkLabel` as a template alias (`:200`) and calls `errorMessage` in `load()` (`:106`), `save()` (`:161`) and `run()` (`:251`). No private `message()` or `checkLabel()` copy is left.
  - `today-habits.ts:14-20` imports both. The component uses `checkLabel` as a template alias (`:137`, bound in `today-habits.html:39`) and calls `errorMessage` in `check()` (`:113`). No local copy is left.
- **Behavior unchanged:** the label texts the specs assert match the shared helper. Examples: `today-habits.spec.ts:85,91,116` (`“Water”: 1 of 3 today`, `Check “Read 20 pages” for today`, `Uncheck …`) and the error detail at `:224-226`. `ApiError` is still imported and used in both components (404 and conflict handling), so no import was left unused.
- **Tests:** `habit-display.spec.ts:16-56` adds direct unit tests for `checkLabel` (once a day, unchecked and checked; a target above 1, including reaching and passing the target) and `errorMessage` (ApiError detail/title; generic text for Error, string, undefined, null, `{}`). These cover the shared helpers where they now live.
- **Architecture/conventions:** the helpers live in `features/habits/habit-display.ts` next to `nextCount`, `todayProgress` and `scheduleLabel`, which Today already imported from there. The changes stay inside the task's `touches` (`features/habits`, `features/gtd/today`). The dependency direction (gtd/today -> habits) matches the existing imports. Nothing is in scope that the task excludes.
- **Security:** no new input, output or logging paths. `errorMessage` returns only `ApiError.userMessage` or a fixed text, as before.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Notes (no finding)

- Copies remain of the check flow (busy set, 404 drop, 409/412 conflict reload, sequence counter) in `HabitsPage.run()`/`isConflict()` (`habits-page.ts:237-271`) and `TodayHabits.check()` (`today-habits.ts:97-125`). CR-3 in web-today-habits also mentioned these, but this task's goal and criteria name only the label and the error message, so they are out of scope. A further follow-up could share `isConflict`.
- In both components, `checkLabel` is declared as a class field below the methods (`habits-page.ts:200`, `today-habits.ts:137`), while the other template aliases (`scheduleLabel`, `todayProgress`) sit at the top of the class. This is a cosmetic inconsistency only.

## Review conclusion

No material issues found.

The code meets criterion 1. The i18n-id check of the removed lines and criterion 2 (test run) could not be verified in this session because Bash was unavailable. The parent must confirm both through its own recorded commands before `done`.
