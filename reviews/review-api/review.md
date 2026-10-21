# Code Review

Date: 2026-10-22

Task: review-api - Weekly review API

Scope: re-review of the final revision 25e7d874b97bb68c868ecd19b9d5c96b2c2e5bc2. The task changes are `git diff 6c86ac42e..25e7d874b` restricted to the review module, its migration, its tests and the generated contract and client. Since the first review two commits were added: 12f706f1d (edge-case tests) and 25e7d874b (fixes for CR-1 and CR-2). Local main has since been rebased to 84eb634fb. `git diff 25e7d874b 84eb634fb` touches only another task's files (`mail/backup`, `reviews/backup-contrib-mail`, `tasks/*`) and no review-api file, so the reviewed code is unchanged at 84eb634fb. Generated files were checked only at the contract level.

Reviewed revision: 25e7d874b97bb68c868ecd19b9d5c96b2c2e5bc2 (code identical at 84eb634fb)

Requirements:
- tasks/review-api.md (acceptance criteria and the 6 recorded assumptions)
- SPEC.md §8.6 (Weekly Review wizard), §8.7 (dashboard "Last weekly review")
- docs/ARCHITECTURE.md "Conventions" (module boundaries, migration naming)
- AGENTS.md "Project notes (My Cockpit)"
- reviews/review-api/fixes.md
- Existing code conventions (gtd ProjectService/TaskService, calendar CalendarEventService, core ETags/ProblemDetailsAdvice)

Git range:
- Branch: main
- HEAD: 25e7d874b97bb68c868ecd19b9d5c96b2c2e5bc2 (reviewed; rebased as 84eb634fb with identical task code)
- Base/start: 6c86ac42e673c9616c64dd7128b42c53d3aec152
- Commits reviewed: 4. Two are code commits (2e90a66c7 and dc5ada32f, later 2807cb285 and 884196cc2), plus 12f706f1d and 25e7d874b. `tasks:` and `assume:` commits were excluded.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None open.

## Dispositions of earlier findings

- **CR-1 | Stale-next-actions 200 response advertised as `application/problem+json`: resolved.**
  - `WeeklyReviewController.kt:173-177`: the 200 `@ApiResponse` now declares `application/json` content with `ReviewTaskListResponse`.
  - `api/openapi.yaml` no longer lists `application/problem+json` for the 200 response. The 400 response keeps its Problem Details content.
  - The regenerated client sends `accept: 'application/json'` (`web/src/app/api/generated/fn/weekly-review/get-review-stale-next-actions.ts:27`).
  - Per the parent, the contract test passes with and without `UPDATE_OPENAPI`.
- **CR-2 | Projects step re-sorted titles by code point: resolved.**
  - `ReviewDataService.kt:64` is now `allProjects(ProjectStatus.ACTIVE).sortedBy { !it.stalled }`. Kotlin's `sortedBy` is stable, so each group keeps the database order of `ProjectService.list`: title in the collation, then id. The KDoc was updated.
  - Covered by the new test `within the stalled and the other projects the order is the one of the Projects view` (`WeeklyReviewEdgeCaseTests.kt:474-490`). It uses mixed-case and umlaut titles and compares against `GET /api/v1/projects?status=ACTIVE` without assuming a collation.
- **CR-3 | Concurrent-start race path untested: resolved by tests.**
  - `WeeklyReviewEdgeCaseTests.kt:195-206` runs 4 threads behind a barrier, 10 rounds. It asserts one review id, exactly one `created`, and one row.
  - Lines 208-217 assert that the partial unique index rejects a second open review and allows many completed ones. Lines 219-223 cover the step check constraint.

## Notes (not findings)

- `WeeklyReviewEdgeCaseTests.kt` (27 tests) was read in full. It also covers:
  - the status choosing the most recently completed review, the 0-day case, and time-zone-dependent day counts;
  - If-Match `*`, lists, weak tags and blank values;
  - 404s without If-Match;
  - tickler, Trash and done items left out of the steps;
  - the exact 30-day boundary;
  - paging beyond `CursorRequest.MAX_LIMIT`;
  - hidden calendars, recurring occurrences with cancelled or deleted instances, and window-boundary overlaps;
  - non-Zurich time zones and DST changes.
- The tests assert behavior through the HTTP API or the service, not implementation details. The time-zone changes are undone by `CalendarApi.resetCalendarTables` in `@BeforeEach`/`@AfterEach`.
- I could not run Gradle here. Test results come from the parent: `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.review.*' jacocoTestReport` -> 36 passed, 0 failed, and the OpenAPI contract test passes with and without the flag. That count matches the sources: 9 web tests plus 27 edge-case tests.
- `python tasks.py verify review-api` now shows all three criteria recorded.
- `reviews/review-api/` is untracked at the time of this review. The parent still has to commit `fixes.md`, `tests.md`, `review.md` and `quality.json` with `reviewed_revision` set to the hash of the final code.
- The missing backup contributor is a recorded assumption with a follow-up task (`tasks/backup-contrib-review.md`), so it is not a finding for this task.

## Review conclusion

No material issues found.
