# Code Review

Date: 2026-10-22

Task: web-today-habits - Today view: habits due

Scope: final code of task web-today-habits, 1c092335a..97b339fa2 (second pass after fixes; production and test code under web/src/app/features/gtd/today/)

Requirements:
- tasks/web-today-habits.md acceptance criteria (1) habits due today with one-tap check, (2) Vitest component/service tests cover the habits section; `cd web && npm run check` passes
- SPEC.md §5.3 (Today view: habits due), §8.5 (one-tap check)
- AGENTS.md project notes (feature folders, touches, lint/i18n conventions)
- Existing conventions: web/src/app/features/habits/habits-page.ts (`refresh()`, `reloadQuietly()`, `run()`), habit-display.ts, habits.service.ts; gtd/today/today-sections.ts, today-view.ts
- First review (this file's previous revision) and reviews/web-today-habits/fixes.md

Git range:
- Branch: main
- HEAD: 97b339fa29322566c54b2e18c50450301569460d
- Base/start: 1c092335a2909e97cec38e57d680049129ca72ef
- Commits reviewed: 4 (12badc077 task notes, a6613d25e implementation, 4145de0ae tests and first reports, 97b339fa2 fix for CR-1/TW-1/TW-2)
- Uncommitted changes: none at review time

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Disposition of first-pass findings

- **CR-1 (P2, reload on return could leave the section loading or keep a stale error): resolved.** `today-habits.ts:81-88` adds `refresh()`, called from the `visibilitychange` handler (`today-habits.ts:55-57`). It skips while a check runs, runs a full `load()` after a failed load (this clears `failed`), reloads quietly only when habits are on screen, and does nothing while the first load or a retry is running (`habits` is null and `failed` is false then). This matches `HabitsPage.refresh()` (`habits-page.ts:218-225`). I traced both paths from the first review. Path 1: no second request, so the first load's own result or error ends the loading note. Path 2: the full load clears the error. Tests: `today-habits.spec.ts:386` (shown again during the first load, no second GET, habits show), `:400` (first load fails after a return, error with retry shows), `:413` (return after a failed load shows the habits).
- **CR-2 (P2, overlap and conflict handling untested): resolved.** Tests now cover a 409/412 conflict with a quiet reload that keeps the habits on screen (`:214`), a failed conflict reload (`:243`), no reload for a non-conflict error (`:260`), a double tap (`:274`), two checks at once answered in reverse order (`:287`), a quiet reload dropped after a check that started meanwhile (`:326`, `:341`), and no reload on return while a check runs (`:357`).
- **CR-3 (P3, check logic copied from HabitsPage): still open, accepted as a follow-up.** `checkLabel()` (`today-habits.ts:131-141`) and `message()` (`:144-148`) are still copies of `habits-page.ts:198-208` and its `message()`. Moving them into `habit-display.ts` would change `web/src/app/features/habits/`, which is outside this task's `touches`, so leaving them here is reasonable. It is listed again below so the decision stays visible.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-3 | Check label and error message copied from HabitsPage instead of shared (carried over, accepted as out of touches)**
    - File: `web/src/app/features/gtd/today/today-habits.ts:131-148`
    - Category: Quality
    - Problem: `checkLabel()` and `message()` are near-verbatim copies of the same helpers in `web/src/app/features/habits/habits-page.ts`. The `check()` flow (busy set, 404 drop, conflict reload, sequence counter) also copies `HabitsPage.run()`.
    - Why it matters: Both views promise the same one-tap check. If someone changes a label or the error mapping in one copy, the two views drift apart. The first pass showed this already: the refresh guard had not been copied.
    - Suggested fix: Follow-up task touching `web/src/app/features/habits/`: move `checkLabel(habit)` and the error-message helper into `habit-display.ts` and use them from both components.

## Notes (no finding)

- The conflict reload is dropped when another habit's check finishes while it runs, because `check()` raises `seq` at the end (`today-habits.ts:98, 103`). The habit with the conflict then keeps its stale state and row error until the next tap or return. `HabitsPage.run()` behaves the same way via `changeSeq`, so this is consistent and a safe choice: a stale list must not undo the other check. Not reported.
- Acceptance criterion 1: only habits with `stats.dueToday` are listed (`today-habits.ts:42`). The one-tap check goes through `HabitsService.setCheck` with `nextCount` (`:97`). The section is registered via `provideTodaySection` in `today.routes.ts`, and `today-view.spec.ts` checks that the routed Today view renders it.
- Acceptance criterion 2: I did not run the tests in this pass. fixes.md records `npx ng test --include='src/app/features/gtd/today/*.spec.ts'` -> 82 passed and `cd web && npm run check` -> lint clean, 2856 tests passed. The parent should record its own run of `npm run check` on the final revision as the evidence.

## Review conclusion

No material issues found.

CR-1 and CR-2 are resolved in the final code. CR-3 (P3) stays open as an accepted follow-up outside this task's touches.
