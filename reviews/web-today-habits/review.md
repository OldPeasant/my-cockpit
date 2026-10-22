# Code Review

Date: 2026-10-22

Task: web-today-habits - Today view: habits due

Scope: final revision of task web-today-habits, 1c092335a..9f65bc0dc66d6c73294d2b113e8ebd19b11691df. Third pass: the earlier passes covered 1c092335a..97b339fa2; this pass reviews `git diff 97b339fa2 9f65bc0dc -- web` (3 tests added to `web/src/app/features/gtd/today/today-habits.spec.ts`; production code unchanged).

Requirements:
- tasks/web-today-habits.md acceptance criteria (1) habits due today with one-tap check, (2) Vitest component/service tests cover the habits section; `cd web && npm run check` passes
- SPEC.md §5.3 (Today view: habits due), §8.5 (one-tap check)
- AGENTS.md project notes (feature folders, touches, lint/i18n conventions)
- Existing conventions: web/src/app/features/habits/habits-page.ts (`refresh()`, `reloadQuietly()`, `run()`), habit-display.ts, habits.service.ts; gtd/today/today-sections.ts, today-view.ts
- Earlier passes of this review, reviews/web-today-habits/fixes.md, reviews/web-today-habits/tests.md

Git range:
- Branch: main
- HEAD: 9f65bc0dc66d6c73294d2b113e8ebd19b11691df
- Base/start: 1c092335a2909e97cec38e57d680049129ca72ef
- Commits reviewed: 5 (12badc077 task notes, a6613d25e implementation, 4145de0ae tests and first reports, 97b339fa2 fix for CR-1/TW-1/TW-2, 9f65bc0dc second-pass tests and reports)
- Uncommitted changes: none at review time (apart from this report)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Final pass: 97b339fa2..9f65bc0dc

`git diff --stat 97b339fa2 9f65bc0dc` touches only `today-habits.spec.ts` (+50 lines) and the two reports. No production file changed, so the earlier findings and the production review still apply. I checked each new test against `refresh()` and `load()` in `today-habits.ts:62-88`:

- `today-habits.spec.ts:426` (full load on return after a failed load): `failed` is true, so `refresh()` runs `load()`. This clears `failed` and sets `habits` to null, so the error goes and the loading note shows, as asserted. During that load `failed` is false and `habits` is null, so a second return does nothing, which matches `http.expectNone`. The reload fails as well and the error shows again. This covers the first branch of `refresh()` and its guard during the reload.
- `:451` (quiet reload on return fails): `load(true)` ignores the failure. The habits stay on screen and no error or loading note shows. This covers the `!quietly` guard in the `catch` branch.
- `:463` (return while the section is hidden with no habits): `habits` is `[]`, which is truthy, so a quiet reload runs and the section stays hidden while it runs. A habit added meanwhile then shows. This covers the case where someone adds their first habit in another view and comes back.

The assertions check rendered behavior (DOM, HTTP requests), not internals. `afterEach(() => http.verify())` ensures no request is left unanswered. The spec at HEAD has sha256 `1f687008ba4f6545...`, the same hash that tests.md records for its coverage and full-suite runs (85 tests in the today specs, 2859 in the web suite, lint clean). So that evidence is for this exact test file. I did not run the tests myself in this pass.

No new findings.

## Disposition of earlier findings

- **CR-1 (P2, reload on return could leave the section loading or keep a stale error): resolved** in 97b339fa2. `today-habits.ts:81-88` adds `refresh()`, called from the `visibilitychange` handler (`:53-57`). It skips while a check runs, runs a full `load()` after a failed load, reloads quietly only when habits are on screen, and does nothing during the first load or a retry. This matches `HabitsPage.refresh()`. Tests: `today-habits.spec.ts:386`, `:400`, `:413`. The final pass adds `:426`, `:451` and `:463`.
- **CR-2 (P2, overlap and conflict handling untested): resolved.** Tests cover a 409/412 conflict with a quiet reload (`:214`), a failed conflict reload (`:243`), a non-conflict error (`:260`), a double tap (`:274`), two checks answered in reverse order (`:287`), a quiet reload dropped after a check that started meanwhile (`:326`, `:341`), and no reload on return while a check runs (`:357`).
- **CR-3 (P3, check logic copied from HabitsPage): open, deferred to a follow-up task.** The parent is deferring it, because the fix would change `web/src/app/features/habits/`, which is outside this task's `touches`. It is listed below so the decision stays visible.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-3 | Check label and error message copied from HabitsPage instead of shared (carried over, deferred to a follow-up task)**
    - File: `web/src/app/features/gtd/today/today-habits.ts:131-148`
    - Category: Quality
    - Problem: `checkLabel()` and `message()` are near-verbatim copies of the same helpers in `web/src/app/features/habits/habits-page.ts`. The `check()` flow (busy set, 404 drop, conflict reload, sequence counter) also copies `HabitsPage.run()`.
    - Why it matters: Both views promise the same one-tap check. If someone changes a label or the error mapping in one copy, the two views drift apart. The first pass already showed this: the refresh guard had not been copied.
    - Suggested fix: In a follow-up task touching `web/src/app/features/habits/`, move `checkLabel(habit)` and the error-message helper into `habit-display.ts` and use them from both components.

## Notes (no finding)

- The conflict reload is dropped when another habit's check finishes while it runs, because `check()` raises `seq` at the end. The habit with the conflict keeps its stale state and row error until the next tap or return. `HabitsPage.run()` behaves the same way via `changeSeq`, so this is consistent and safe: a stale list must not undo the other check.
- Acceptance criterion 1: only habits with `stats.dueToday` are listed (`today-habits.ts:41`). The one-tap check goes through `HabitsService.setCheck` with `nextCount`. The section is registered via `provideTodaySection` in `today.routes.ts`, and `today-view.spec.ts` checks that the routed Today view renders it.
- Acceptance criterion 2: tests.md records `npm run coverage` (2859 passed) and `ng lint --max-warnings=0` (clean) on this spec file. The parent should record its own run of `cd web && npm run check` (or `.taskcheck`) on 9f65bc0dc as the evidence for the criterion.

## Review conclusion

No material issues found.

This review covers the final revision 9f65bc0dc66d6c73294d2b113e8ebd19b11691df. CR-1 and CR-2 are resolved. CR-3 (P3) stays open and is deferred to a follow-up task outside this task's touches.
