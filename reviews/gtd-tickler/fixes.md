# Fix Report

Date: 2026-10-20

Task: gtd-tickler - Tickler and scheduled tasks job

Scope: current task; findings CR-1 and CR-2 from `reviews/gtd-tickler/review.md` (both P3, both approved by the parent worker).

## Fixed findings

### CR-1 — KDoc links to a non-existent `gtd.infra.TicklerJob`

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskService.kt`

Problem:
The `releaseDue` KDoc linked `[ch.sonensei.cockpit.gtd.infra.TicklerJob]`, but `TicklerJob` lives in `ch.sonensei.cockpit.gtd.domain`, so the link did not resolve.

Fix:
Changed the link to `[TicklerJob]` (same package). Comment-only change.

Validation:
- `cd server && ./gradlew ktlintFormat ktlintCheck` — Pass

### CR-2 — `getTask` API description does not mention the on-read release

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/TaskController.kt`
- `api/openapi.yaml` (regenerated)

Problem:
GET /api/v1/tasks/{id} releases due ticklers and scheduled tasks before reading, so the returned task can have a different list, cleared dates and a new ETag; the contract only said "One task".

Fix:
Added a `description` to the `getTask` `@Operation`: "Due ticklers move back to the INBOX and due SCHEDULED tasks to NEXT before the task is read, so its list, dates and ETag may differ from an earlier read." (wording consistent with `listTasks`). Regenerated `api/openapi.yaml`; the only diff is the new `description` on `get` of `/api/v1/tasks/{id}`. `npm --prefix web run api:gen` produced no changes in `web/src/app/api/generated/` (descriptions are not emitted for this operation).

Validation:
- `cd server && UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'` — Pass (spec regenerated)
- `npm --prefix web run api:gen` — Pass, no changes to generated client
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest'` (without UPDATE_OPENAPI, so the contract test compares against the committed-to-be spec) — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat ktlintCheck` — Pass, no formatting changes beyond the edits
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest'` — BUILD SUCCESSFUL; 225 tests, 0 failures, 0 errors, 0 skipped (from `build/test-results/test/*.xml`)

Full test suite:
- Not run (changes are a KDoc link and an OpenAPI description; the gtd package and contract test cover them)

Lint/type-check/build:
- ktlint as above — Pass; compilation as part of the test run — Pass

`.taskcheck`:
- Not run by the fixer; left to the parent worker

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
