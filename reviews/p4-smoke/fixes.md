# Fix Report

Date: 2026-10-21

Task: p4-smoke - Phase 4 demo script

Scope: current task, range df0186ecc..1e0068034 (final revision 1e0068034). Findings from `reviews/p4-smoke/review.md`: CR-1 (P1), CR-2 (P2), CR-3 (P3). The fixer verified each one by reading the code at 1e0068034. The fixer made no code changes: the test-writer's commit 1e0068034 and the parent's validation runs had already resolved all three. The fixer could not run Gradle or the e2e stack. Every validation result below was run by the parent worker and is credited to it.

## Fixed findings

### CR-1 - No evidence for "earlier phase specs pass"

Files changed:
- None. This was a missing-evidence finding, not a code defect.

Problem:
The first reported e2e run was filtered to `phase4/`, so the phase 0-2 demo specs (and phase 3, Android) had not been run for this task.

Fix:
The parent worker ran the earlier phases. In `web/playwright.config.ts`, the `phase-demos` project matches `/phase\d+\/.*\.spec\.ts$/`. Without a filter it therefore runs `web/e2e/phase0`, `phase1`, `phase2` and `phase4`. Phase 3 is the Android smoke test, which the parent ran against the e2e stack.

Validation (run by the parent worker):
- `cd web && npm run e2e -- --project=phase-demos` (no filter: phases 0, 1, 2, 4) - Pass (24 passed)
- Phase 3 against the e2e stack: `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.smoke.*' -Pe2eBaseUrl=http://127.0.0.1:18390/` - Pass (1 passed)
- `./gradlew integrationTest --tests 'ch.sonensei.cockpit.calendar.smoke.*'` (steps 2-3) - Pass (4 passed), as reported in review.md
- The re-review (`reviews/p4-smoke/review.md`) attaches a condition: the result of the full server `./gradlew integrationTest` run must be recorded by the parent with `tasks.py verify`. If that run fails, CR-1 is open again.

### CR-2 - The day view is checked on only one side of the DST change

Files changed:
- `web/e2e/phase4/demo.spec.ts` (test-writer, commit 1e0068034)

Problem:
The day view was checked only after the DST change on 2026-03-29.

Fix:
- `web/e2e/phase4/demo.spec.ts:94-95`: the day view of 2026-03-23 (CET) checks `9:00 – 10:00`.
- Lines 96-97: the day view of 2026-04-06 (CEST) checks `9:00 – 10:00`.
- Lines 100-101: the day view of 2026-03-31 shows the moved occurrence at `2:00 – 3:00`. This matches the 14:00 move at line 74 and the same format used at line 78.

Validation (run by the parent worker):
- `cd web && npm run e2e -- --project=phase-demos` - Pass (24 passed, includes phase4)
- lint, tsc and prettier on `web/e2e/phase4` - Pass (clean)

### CR-3 - The absence check in the day view can pass before the view has loaded

Files changed:
- `web/e2e/phase4/demo.spec.ts` (test-writer, commit 1e0068034)

Problem:
On the day view of 30 March, the check that the event is absent could pass on an empty grid before the occurrences had loaded.

Fix:
- `web/e2e/phase4/demo.spec.ts:102-105`: a `waitForResponse` for `GET /api/v1/calendar/events` is registered before `page.goto` (line 106).
- Line 107: the response is awaited and its success asserted.
- Line 108: the day column `[data-date="2026-03-30"]` must be visible.
- Line 109: only after that is the absence of the event asserted.
- The 31 March view's request already finished before the listener was registered, because the assertion at line 101 needs its data. The listener can therefore only match the request for the 30 March view.

Validation (run by the parent worker):
- `cd web && npm run e2e -- --project=phase-demos` - Pass (24 passed)
- lint, tsc and prettier on `web/e2e/phase4` - Pass (clean)

## Not fixed

None.

## Validation summary

Focused tests (all run by the parent worker):
- `cd web && npm run e2e -- --project=phase-demos` - 24 passed (phases 0, 1, 2, 4)
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.smoke.*' -Pe2eBaseUrl=http://127.0.0.1:18390/` against the e2e stack - 1 passed (phase 3)
- `./gradlew integrationTest --tests 'ch.sonensei.cockpit.calendar.smoke.*'` - 4 passed (as reported in review.md)

Full test suite:
- Full server `./gradlew integrationTest`: the result had not yet been reported when this report was written. The parent worker records it with `tasks.py verify` (condition of CR-1).

Lint/type-check/build:
- lint, tsc and prettier on `web/e2e/phase4` - clean (parent worker)

`.taskcheck`:
- Not run by the fixer (it cannot run Gradle here). The parent worker runs it before `tasks.py done`.

Remaining unresolved findings:
0

## Final status

No valid findings required code changes. CR-2 and CR-3 were resolved in commit 1e0068034, and the fixer confirmed this by reading the code. CR-1 was resolved by the parent's validation runs. One condition remains: the parent must record a passing full server `integrationTest` run.
