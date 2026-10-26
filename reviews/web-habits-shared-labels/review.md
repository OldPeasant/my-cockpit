# Code Review

Date: 2026-10-26

Task: web-habits-shared-labels - Share habit check label/error helpers between Habits view and Today

Scope: `git diff 8ab942ce..HEAD -- web/` (production commit 3c0acdfa and test commit 06172c40, new tests included)

Requirements:
- tasks/web-habits-shared-labels.md (acceptance criteria 1 and 2)
- reviews/web-today-habits/review.md CR-3 (origin of the task)
- AGENTS.md project notes (lint, feature folders, touches)
- docs/ARCHITECTURE.md conventions (web feature folders, i18n via `$localize`)

Git range:
- Branch: main
- HEAD: 06172c40
- Base/start: 8ab942cec4152d16c2c58d97607da2a35c116c01
- Commits reviewed: 2 (3c0acdfa, 06172c40); no uncommitted changes at start

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Verification notes

- AC1 (one shared implementation): `checkLabel()` and `errorMessage()` are now exported from
  `web/src/app/features/habits/habit-display.ts:39` and `:52`. `habits-page.ts` and
  `gtd/today/today-habits.ts` import both helpers, bind `protected readonly checkLabel = checkLabel`
  for their templates (`habits-page.html:117`, `today-habits.html:39`), and call `errorMessage()` at
  every former `message()` call site (habits-page: load, save, check; today-habits: check). A grep
  for `function message` and for the i18n ids in `web/src` finds no remaining copy outside
  `habit-display.ts`.
- i18n ids: I extracted every `$localize` literal from the removed and added lines of the diff.
  The removed lines held `@@habits.check`, `@@habits.uncheck`, `@@habits.checkCount` and
  `@@habits.error` twice each (one copy per component). The added lines hold each of them once, with
  the same source text and the same placeholder names (`name`, `count`, `target`). No id, text or
  placeholder changed. The `habits.*` ids are not in `web/src/locale/messages.xlf` yet, and this task
  does not change that file.
- Behaviour is the same as before: the moved bodies match the old ones exactly, and `ApiError` is
  still imported where the components use it for 404/409/412 handling.
- Dependency direction: `gtd/today` already imported from `features/habits/habit-display`
  (`nextCount`, `scheduleLabel`, `todayProgress`), so this adds no new coupling. All changes stay
  inside the task's `touches`.
- Tests: `habit-display.spec.ts` covers all three `checkLabel` branches (including target 2 and a
  count above the target) and both `errorMessage` branches (API error detail/title and non-API
  values, including `null`/`undefined`). `today-habits.spec.ts` adds the generic-message path for a
  non-API failure and checks that the button is enabled again afterwards. These tests check what the
  user sees, not how the code is built.
- Commands run in this review:
  - `cd web && npx ng test --watch=false --include=src/app/features/habits/habit-display.spec.ts --include=src/app/features/habits/habits-page.spec.ts --include=src/app/features/gtd/today/today-habits.spec.ts` -> 3 files, 89 tests passed.
  - `cd web && npx eslint src/app/features/habits src/app/features/gtd/today` -> clean.
  - Plain `npx vitest run ...` without the Angular builder fails before any test runs, because
    `$localize` is not set up there. That is how the project's test setup works (it runs through
    `ng test`) and is not a defect of this task.
- AC2 (`npm run check`): this review did not re-run it. The test-writer's report
  (reviews/web-habits-shared-labels/tests.md) records a passing `npm run check` with 175 files and
  3671 tests. That report cites older hashes of the production commit (e.g. 0041235a); the parent
  worker should make sure the recorded evidence matches the final code (patch-id) when it records
  `verify`.

## Review conclusion

No material issues found.
