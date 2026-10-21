# Code Review

Date: 2026-10-21

Task: p4-smoke - Phase 4 demo script

Scope: the final revision 1e0068034, reviewed as `git diff df0186ecc..1e0068034 -- server web`. This is a test-only change: `server/src/test/kotlin/ch/sonensei/cockpit/calendar/smoke/Phase4CalDavDemoTests.kt`, `server/src/test/kotlin/ch/sonensei/cockpit/calendar/smoke/Phase4OAuthProvidersDemoTests.kt` and `web/e2e/phase4/demo.spec.ts`. Bash was not available to the reviewer, so it could not print the diff. The changed files were read in full from the working tree. This re-review covers the follow-up changes to `web/e2e/phase4/demo.spec.ts` (the day-view checks and the waiting before the absence check). Per the coordinator, those were the only changes since the first review.

Requirements:
- tasks/p4-smoke.md acceptance criterion and the assumptions in its Notes
- SPEC.md §13.2 Phase 4 (steps 1-5) and the §13.2 preamble (each script also runs the earlier phases' scripts)
- web/e2e/README.md (phase-demo conventions), web/playwright.config.ts
- AGENTS.md project notes (fictitious test data, @ExternalTest / integrationTest)
- Existing reference tests: GoogleCalendarSyncTests (the provider "this and following" split), web/e2e/phase1/demo.spec.ts (conventions)
- Results reported by the parent:
  - `./gradlew integrationTest --tests 'ch.sonensei.cockpit.calendar.smoke.*'` -> 4 passed
  - `cd web && npm run e2e -- --project=phase-demos` (no filter; phases 0, 1, 2, 4) -> 24 passed
  - Phase 3 (Android): `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.smoke.*' -Pe2eBaseUrl=...` against the e2e stack -> 1 passed
  - The full server `./gradlew integrationTest` was still running when this review was written

Git range:
- Branch: main
- HEAD: 1e0068034
- Base/start: df0186eccdd19be6f8b20a043ea39587b6617a18
- Commits reviewed: all task commits from df0186ecc to 1e0068034 (first review: a0c1c6726, 07efd18dc, 75142a88a; then the test-writer's follow-up)

Summary: 0 P0, 0 P1, 0 P2, 0 P3 (open)

## P0 Critical

None.

## P1 High

- [x] **CR-1 | No evidence for "earlier phase specs pass"** - resolved
    - File: `tasks/p4-smoke.md:14`
    - Category: Tests
    - Problem (first review): The only reported e2e run was filtered to `phase4/`, so the phase 0-2 demo specs had not run.
    - Disposition: Resolved by running the earlier phases:
      - `npm run e2e -- --project=phase-demos` without a filter (phases 0, 1, 2 and 4) -> 24 passed.
      - Phase 3 (Android) against the e2e stack -> 1 passed.
      - The full server `./gradlew integrationTest` was still running when this review was written. The parent worker must wait for its result and record it with `tasks.py verify`. If the run fails, this finding is open again.

## P2 Medium

- [x] **CR-2 | The day view is checked on only one side of the DST change** - resolved
    - File: `web/e2e/phase4/demo.spec.ts:93-101`
    - Category: Tests
    - Disposition: Resolved. The day view now checks `9:00 – 10:00` on 2026-03-23 (CET, line 95) and on 2026-04-06 (CEST, line 97), and shows the moved occurrence on 2026-03-31 at `2:00 – 3:00` (line 101).

## P3 Low

- [x] **CR-3 | The absence check in the day view can pass before the view has loaded** - resolved
    - File: `web/e2e/phase4/demo.spec.ts:102-109`
    - Category: Tests
    - Disposition: Resolved, with these steps:
      - A `waitForResponse` for `GET /api/v1/calendar/events` is registered before `page.goto` (line 102) and its success is asserted (line 107).
      - The day column `[data-date="2026-03-30"]` must be visible (line 108).
      - Only then is the absence of the event asserted (line 109).
    - The previous page's request finished before the listener was registered: the assertion at line 101 needed that data. So the listener can only match the request of the 30 March view.

## New findings

None. The follow-up only adds assertions to step 1. It does not change the steps that were already reviewed, the helpers, or the server tests.

## Notes (verified, no finding)

- Step 1 (web): local calendar created through the UI, weekly series, one occurrence moved with "Only this event". Week and day views are checked on both sides of the change, the month view checks the per-day counts. The server's UTC instants (08:00Z before, 07:00Z after) are checked independently of the UI.
- Step 4: the web test checks the occurrences and both RRULEs, `Phase4CalDavDemoTests` checks the split against Radicale, and `GoogleCalendarSyncTests` covers the Google split in `check`, as the task's assumption says.
- Step 2 (Radicale) and step 3 (Google/Microsoft against WireMock): the first sync, the incremental sync, and the 412 -> 409 path with a refetch are all asserted.
- web/e2e/README.md phase-demo conventions are followed. All test data is fictitious, and `@ExternalTest` keeps both server classes out of `check`.

## Review conclusion

No material issues found. One condition remains: the parent worker must record the result of the full server `./gradlew integrationTest` run that was still running (see CR-1).
