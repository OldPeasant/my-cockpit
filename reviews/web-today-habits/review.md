# Code Review

Date: 2026-10-22

Task: web-today-habits - Today view: habits due

Scope: commits of task web-today-habits on top of base 1c092335a (12badc077 assume, a6613d25e implementation)

Requirements:
- tasks/web-today-habits.md acceptance criteria (1) habits due today with one-tap check, (2) Vitest tests cover the habits section; `cd web && npm run check` passes
- SPEC.md §5.3 (Today view: habits due), §8.5 (one-tap check)
- AGENTS.md project notes (feature folders, touches, i18n/lint conventions)
- Existing conventions: web/src/app/features/habits/habits-page.ts, habit-display.ts, habits.service.ts; gtd/today/today-sections.ts, today-view.ts

Git range:
- Branch: main
- HEAD: a6613d25eb3780a079810997efc0c60cbddf3e5a (the parent named 553be17ef; the commit at HEAD is the same task commit after a rebase, with the same subject and files)
- Base/start: 1c092335a2909e97cec38e57d680049129ca72ef
- Commits reviewed: 2 (one task-notes commit, one implementation commit)

Summary: 0 P0, 0 P1, 2 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Quiet reload on visibilitychange can leave the section stuck on "Loading habits…" or showing a stale error**
    - File: `web/src/app/features/gtd/today/today-habits.ts:55-74`
    - Category: Correctness
    - Problem: The `visibilitychange` handler calls `load(true)` whenever no check is running. It does not look at whether the habits have loaded or whether the last load failed. Two paths go wrong:
      1. The first load (seq 1, `habits` null) is still running when the tab becomes visible again. `load(true)` raises `seq` to 2, so the first response is dropped even if it succeeds. If the quiet request then fails, `quietly` stops `failed` from being set, and `habits` stays null. The template stays on "Loading habits…" (`!loaded()`) with no retry and no error.
      2. The section shows the load error (`failed` is true), and the user comes back to the tab. The quiet reload succeeds and sets `habits`, but `failed` stays true. The `@if (failed())` branch comes first in the template, so the error still shows even though the data has loaded.
      `habits-page.ts:218-225` (`refresh()`) avoids both cases. It runs a full `load()` after a load error and reloads quietly only once habits are on screen.
    - Why it matters: The review rule says every path must end loading and leave a screen the user can recover from. Path 1 leaves a permanent spinner in the Today view until the user leaves the page.
    - Suggested fix: Do the same as `HabitsPage.refresh()`. When `failed()` is true, call `load()`. When `habits()` is not null, call `load(true)`. Otherwise do nothing, because a load is already running. Add a Vitest case for "visible during initial load" and one for "visible after a failed load".

- [ ] **CR-2 | The overlap and conflict handling has no tests**
    - File: `web/src/app/features/gtd/today/today-habits.spec.ts:139-211`
    - Category: Tests
    - Problem: `check()` handles a 409/412 conflict with a row error plus a quiet reload (`today-habits.ts:95-97`). The shared `seq` counter is there so that a reload overlapping a check is dropped (`today-habits.ts:48-49, 82-90`). No test covers either one. The visibility test only covers a quiet reload with nothing else running. The tests also do not cover the visibility handler being skipped while a check is busy, or the CR-1 paths.
    - Why it matters: Out-of-order async results are a known source of regressions. Without tests, a change to the `seq` bookkeeping could let a stale list undo a check the user just made, and no test would fail.
    - Suggested fix: Add tests for these cases: (a) a quiet reload starts, a check starts and finishes, then the old list response arrives and must not overwrite the checked habit; (b) a check gets a 412, so the row error shows and the reload request is sent and applied; (c) visibilitychange while a check is busy sends no list request.

## P3 Low

- [ ] **CR-3 | Check logic copied from HabitsPage instead of shared**
    - File: `web/src/app/features/gtd/today/today-habits.ts:78-135`
    - Category: Quality
    - Problem: These parts are near-verbatim copies of `habits-page.ts:197-208, 244-285`: `checkLabel()`, `message()`, the busy-set and 404/conflict handling in `check()`, and the unstyled `[class.partial]` binding in the template. `habit-display.ts` already holds the shared display helpers (`nextCount`, `todayProgress`, `scheduleLabel`), so `checkLabel` and the error-message helper would fit there too.
    - Why it matters: The two views promise "the same one-tap check". If someone changes the labels or error handling in one copy, the views drift apart. CR-1 already shows this: the refresh guard in HabitsPage was not copied.
    - Suggested fix: Move `checkLabel(habit)` and the error-message function into `habit-display.ts` and use them from both components. Keeping the `check()` flow per component is fine. The habits folder is outside this task's `touches`, so this can be a follow-up.

## Review conclusion

Material issues found; fix before completing the task.

Both acceptance criteria are otherwise met. The section lists only habits with `stats.dueToday`. Each habit has a one-tap check through `HabitsService.setCheck` with `nextCount`, and the section is registered through `provideTodaySection` in `today.routes.ts`, inside the task's `touches`. Vitest covers listing, check, uncheck, target counting, check errors, 404, the empty and hidden states, load failure with retry, and the visibility reload. CR-1 should be fixed, and CR-2's tests added, before the task is completed.
