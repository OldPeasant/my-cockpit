# Code Review

Date: 2026-10-21

Task: p1-smoke - Phase 1 demo script

Scope: committed changes of the task, `git diff c0dd91a4 HEAD` (base = merge-base with origin/main)

Requirements:
- tasks/p1-smoke.md acceptance criteria (1: `web/e2e/phase1/*.spec.ts` covers demo steps 1-9 incl. tickler with the e2e clock and recurrence and passes together with phase 0; 2: defects fixed here or recorded as tasks)
- SPEC.md §13.2 Phase 1 steps 1-9, §4.5 (capture syntax), §5.1-5.3 (GTD rules and UI)
- web/e2e/README.md (phase demo conventions, `resetStack`/`setClock`)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- Existing code: CaptureParser.kt (`#project` only matches existing projects), ProjectStore, ListStore

Git range:
- Branch: main
- HEAD: 2b1d34bb
- Base/start: c0dd91a4da72c2358296bbc26f789277f65de013
- Commits reviewed: 3 (17e6235b, 193f1eec, 2b1d34bb)
- Uncommitted changes: none

Files reviewed: web/e2e/phase1/demo.spec.ts (new, full), web/playwright.config.ts,
web/src/app/features/gtd/inbox/inbox-page.ts (full) and inbox-page.spec.ts (diff), plus
project-store.ts, web/e2e/phase0/demo.spec.ts structure, CaptureParser.kt, TicklerJob.kt.

Commands run by the reviewer:
- `npx ng test --watch=false --include src/app/features/gtd/inbox/inbox-page.spec.ts` -> 1 file, 61 tests passed
- `npm run e2e:typecheck` -> passed (no errors)
- Playwright 1.64.1 installed: per-project `workers` is supported.
- The e2e run itself was not repeated by the reviewer; the parent reports `npm run e2e -- --project=phase-demos` -> 15 passed (phase 0: 5 tests, phase 1: 10 tests, consistent with the spec files).

Criterion check:
- Steps 1, 3, 4, 5 (tickler with `setClock` and page clock), 6 (weekly FIXED and AFTER_COMPLETION, with clock moved to a Monday), 7, 8, 9 are covered with UI and API assertions that match SPEC.md §13.2.
- Step 2 is covered with a deviation, see CR-1.
- The phase 0 and phase 1 scripts run in the same `phase-demos` project with `workers: 1`, in file (phase) order, before the other specs. `afterAll` resets the stack and clock as the README requires.
- Criterion 2: the defect found (To project did not make the item the next step of a stalled or new project, and the captured project was not preselected) is fixed in inbox-page.ts with unit tests for the existing-stalled, new-project, project-with-next-step and failure paths. The fix is outside the task's `touches`, but criterion 2 allows fixes here.

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Step 2 "new project Holiday" is run against a pre-created project, and no assumption records this**
    - File: `web/e2e/phase1/demo.spec.ts:44-58`, `web/e2e/phase1/demo.spec.ts:109-116`
    - Category: Tests
    - Problem: SPEC.md §13.2 Phase 1 step 2 says "Plan holiday" -> **new** project "Holiday", which becomes its next step. The script creates "Holiday" in a set-up step 0, so step 2 only picks an existing project and the "New project" branch of To project is never run end to end. The reason is real: step 1 wants `#Holiday` parsed, and `CaptureParser` only matches existing projects (CaptureParser.kt:39), so steps 1 and 2 conflict in the spec. The file header explains the set-up, but tasks/p1-smoke.md has no `ASSUMPTION` line (and neither does origin/main), even though AGENTS.md requires `tasks.py assume` for a deviation from SPEC.md.
    - Why it matters: A human reading the done task would think the demo proves the "new project" path. That path (create project, move, then `setNextStep` in inbox-page.ts:279-288) is covered only by a mocked unit test. The spec conflict also stays hidden.
    - Suggested fix: Record the deviation and the spec conflict with `python tasks.py assume p1-smoke "..."`. If possible, also run the new-project path end to end: for example, process one more captured item with "New project" and check that the project's `nextTaskId` is that task, so the "becomes its next step" part of step 2 is shown for a really new project.

## P3 Low

- [ ] **CR-2 | The "hidden from all lists" UI checks can pass before the list has loaded**
    - File: `web/e2e/phase1/demo.spec.ts:206-210`
    - Category: Tests
    - Problem: For each view, the loop only waits for the static `h1` and then asserts `getByRole('button', { name: 'Renew passport' })` has count 0. A negative assertion like this passes at once while the list is still empty or loading. So the UI half of the check proves nothing. The API checks right after it (lines 211-215) do prove the requirement for the server lists.
    - Why it matters: The UI check looks like evidence that every view hides the tickled item, but it would not catch a view that shows it.
    - Suggested fix: Before the negative assertion, wait for a positive signal that the view has loaded, such as a known row of that view (e.g. "Buy milk" in Next/Today) or the view's empty-state text. Or drop the UI loop and keep the API checks.

## Review conclusion

Material issues found; fix before completing the task. No defect was found in the production fix or in the behaviour the script checks. The P2 finding (CR-1) needs at least the assumption recorded before the task is done. CR-2 is optional.
