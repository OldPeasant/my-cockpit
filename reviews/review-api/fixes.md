# Fix Report

Date: 2026-10-22

Task: review-api - Weekly review API

Scope: current task; findings CR-1 and CR-2 from `reviews/review-api/review.md`, as selected by the parent worker.

## Fixed findings

### CR-1 — Stale-next-actions 200 response advertised as `application/problem+json`

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/review/api/WeeklyReviewController.kt`

Problem:
`staleNextActions` produces `application/json` and `application/problem+json`, and its 200 `@ApiResponse` had no `content`. So springdoc listed the success body under both media types, and the generated web client sent `Accept: application/problem+json` for the success call.

Fix:
The 200 `@ApiResponse` now declares `content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ReviewTaskListResponse::class))]`, the same way `@ReviewOkResponse` does. The 400 response (`@ReviewBadRequestResponse`) is unchanged. `api/openapi.yaml` and `web/src/app/api/generated/` still need to be regenerated. The parent worker does this.

Validation:
- `./gradlew ktlintCheck compileTestKotlin` — Pass
- `./gradlew test --tests 'ch.sonensei.cockpit.review.*'` — Pass (36 tests, 0 failures)

### CR-2 — Projects step re-sorts titles by code point

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/review/domain/ReviewDataService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/review/WeeklyReviewEdgeCaseTests.kt`

Problem:
`projects()` re-sorted with `compareBy({ !it.stalled }, { it.project.title }, { it.project.id })`. `ProjectService.list` already orders by title, then id, in the database collation. The extra Kotlin sort by code point changed that order, so the projects step could differ from the Projects view and from the someday step.

Fix:
`projects()` is now `allProjects(ProjectStatus.ACTIVE).sortedBy { !it.stalled }`. Kotlin's sort is stable, so each group keeps the fetched database order. The KDoc now says this. A new test, `within the stalled and the other projects the order is the one of the Projects view`, creates 4 stalled and 3 non-stalled active projects with mixed-case and umlaut titles. It asserts that the step order equals the stalled items, then the other items, of `GET /api/v1/projects?status=ACTIVE`, each in API order. It does not assume a particular collation.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.review.*'` — Pass (WeeklyReviewEdgeCaseTests 27 tests and WeeklyReviewWebTests 9 tests, 0 failures; the new test is included)

## Not fixed

### CR-3 — The concurrent-start race path is not tested

Status:
Out of scope

Reason:
The parent did not include it in this fix request. The existing `WeeklyReviewEdgeCaseTests` already contain `concurrent starts create exactly one open review and all return it` and `the database rejects a second open review but allows many completed ones`. These cover what the finding asks for.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintCheck compileTestKotlin` — Pass
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.review.*'` — Pass, 36 tests, 0 failures, 0 skipped

Full test suite:
- Not run by the fixer. The parent runs it after regenerating the OpenAPI contract, because the OpenAPI contract test depends on the regenerated `api/openapi.yaml`.

Lint/type-check/build:
- `./gradlew ktlintCheck compileTestKotlin` — Pass

`.taskcheck`:
- Not run (the parent runs it after regenerating `api/openapi.yaml` and the web client)

Remaining unresolved findings:
0 of the requested findings (CR-3 was not requested and is already covered by existing tests)

## Final status

All validated findings fixed and validation passed.
