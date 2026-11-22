# Code Review

Date: 2026-11-22

Task: web-reminders-spec-clock - Web: task-reminders.spec.ts fails since 2026-11-21 (fixture dates in the past, real clock)

Scope: `git diff 143e2a6b a2018af8` (single task commit)

Requirements:
- tasks/web-reminders-spec-clock.md acceptance criteria
- AGENTS.md, docs/ARCHITECTURE.md, docs/QUALITY.md (conventions)

Git range:
- Branch: main
- HEAD: a2018af840b95644e4805b01aee8f86c5e3a8191
- Base/start: 143e2a6bf8e42d8815b053ad40c6a2a6d97b6721
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Notes

- The change is limited to `web/src/app/features/gtd/task-detail/task-reminders.spec.ts`, which is the task's only `touches` entry. No production code changed.
- `vi.useFakeTimers({ toFake: ['Date'] })` plus `vi.setSystemTime(now)` in `beforeEach` (line 147-148) pins `new Date()`/`Date.now()` to the suite's existing `now` (2026-11-10 12:00), which is before the fixture anchors (due 2026-11-20 14:30). So the presets computed on the first render, before `component.now` is overridden in `render()`, no longer depend on the real date. Only `Date` is faked, so `setTimeout` stays real and the `settle()` helper and `fixture.whenStable()` behave as before.
- `afterEach(() => vi.useRealTimers())` (line 181) restores the clock, so the fake clock does not leak into other suites. The pure `reminder presets` describe block passes explicit `now` values and was already independent of the real clock.
- Using the global `vi` matches other specs in the repo (for example `core/auth/auth.service.spec.ts`).
- The comment explains why the fake clock is needed, which is helpful.
- This review did not re-run the test suite. Criterion 2 (`npm run check`, `sh scripts/check.sh`) needs run evidence from the parent worker via `tasks.py verify`.

## Review conclusion

No material issues found.
