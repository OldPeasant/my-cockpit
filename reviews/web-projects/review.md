# Code Review

Date: 2026-10-21

Task: web-projects - Projects views

Scope: final revision review at HEAD `417409b39bb697627d8a4ce0b660c78eab5cfe09`. Task commits `131583651a38061f536a590dae38436b1eab4257..417409b3` (10 commits, base = `git merge-base HEAD origin/main`). Production code was reviewed in full in the earlier review (then at 942dc560, before the rebase). This pass checked that production code has not changed since then: `git diff 942dc560 HEAD` over `web/src` and `web/e2e` without `*.spec.ts` is empty, and `git diff ab495c67 HEAD` ("fix report" to HEAD) touches only `projects-states.spec.ts`, `project-groups.spec.ts`, `reviews/web-projects/tests.md` and this report. It also reviewed the 7 tests added in 417409b3 against the production code they exercise (`project-detail-page.ts/.html`, `project-form.html`, `project-groups.ts`, `ui/task-row.html`).

Requirements:
- tasks/web-projects.md acceptance criteria and recorded assumptions
- SPEC.md §5.2, §5.3, §9.1
- docs/ARCHITECTURE.md, AGENTS.md project notes, docs/QUALITY.md
- Earlier review of this task (CR-1 to CR-5, all fixed) and reviews/web-projects/fixes.md

Git range:
- Branch: main
- HEAD: 417409b39bb697627d8a4ce0b660c78eab5cfe09
- Base/start: 131583651a38061f536a590dae38436b1eab4257
- Commits reviewed: 10 (no uncommitted changes)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## New tests checked (commit 417409b3)

- `statusLabel` unknown status: `project-groups.ts:15` falls back to the raw value (`?? status`). The test asserts this fallback.
- List inline-edit cancel and detail edit-form cancel: the Cancel button in `project-form.html:68` emits `cancelled`. The tests check that the form closes, that `updateProject` is never called and that the shown title is unchanged. The detail test selects `app-project-form button.quiet`, which is only the form's Cancel button because the submit button is `.primary`.
- Tasks loading state: the test holds `listTasks` open with a deferred promise. It checks that the project header renders, that "Loading…" (`project-detail-page.html:184`) shows and that `.tasks` is absent, then resolves the request and checks the ordered titles.
- Double submit of "Add task" and "Add as next step": the second submit is dispatched straight on the form while `createTask` is still pending. The input still holds its text at that point, so only the `run()` busy guard (`project-detail-page.ts:271`) can stop a second create. The tests would fail without that guard. "Add as next step" also asserts that exactly one `setProjectNextStep` is sent, with the created task id.
- Completing the next step twice: `task-row.html:8` emits `toggleDone` on every click (it has no disabled binding), so both clicks reach `complete()` and the busy guard is what is tested. The test asserts one `completeTask` call and one prompt.

The assertions check user-visible behavior and the requests sent, not internals. The deferred promises are resolved inside each test, so no request is left pending.

## Notes (not findings)

- `reviews/web-projects/tests.md` still names the pre-rebase hashes (base c2621889, HEAD 942dc560, fix d99e65f2). Production code is identical to the current tree (checked above), so its coverage numbers still apply to HEAD. The parent should note the rebase mapping in `quality.json`.
- `python tasks.py verify web-projects` shows criteria 1, 2 and 4 recorded and criterion 3 (`npm run check`) still unrecorded. The parent must record it before `done`.
- I did not run the test suites for this review: I had read-only access.

## Review conclusion

No material issues found.
