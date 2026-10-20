# Fix Report

Date: 2026-10-20

Task: gtd-projects-api - Projects, areas, contexts API and next-step rules

Scope: current task; the three approved P3 findings CR-1, CR-2, CR-3 in `reviews/gtd-projects-api/review.md` (base bc7f6be1, HEAD e0e20d06)

## Fixed findings

### CR-1 — Purging a project changes its tasks without a version bump or change event

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/infra/ProjectTrashContributor.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/infra/GtdRepositories.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/ProjectWebTests.kt`

Problem:
`purge` relied on the FK `on delete set null` to detach the project's tasks, so their version/ETag stayed the same and no `TASK UPDATED` event fired.

Fix:
New `TaskRepository.findByProjectIdOrderByPositionAscIdAsc` (also returns deleted tasks). Before deleting the project row, `purge` sets `projectId = null` on each task in code and saves it, which bumps the version. Tasks not in the Trash also move to the end of their list, the same way `TaskService` treats a task that leaves its project, and get a `TASK UPDATED` event. This matches `AreaService.delete` and `ContextService.delete`. The `maxPositionInList` query runs before the entity is changed. Otherwise the query's JPA auto-flush bumped the version twice; the regression test caught this. The purge test now covers this. It asserts that the version goes up by exactly 1, that the task moves to the end of the list, that a task in the Trash is detached with a new version, and that the only events are one `TASK UPDATED` for the live task and one `PROJECT DELETED`.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest'` — Pass

### CR-2 — Area PATCH silently ignores `null` and unknown fields

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/AreaContextControllers.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/AreaContextWebTests.kt`
- `api/openapi.yaml` (regenerated: operation description only)

Problem:
`AreaController.update` bound a typed `UpdateAreaRequest`, so `{"title":null}`, `{"position":null}` or an unknown field returned 200 and published an `AREA UPDATED` event without changing anything.

Fix:
The endpoint now binds `Map<String, Any?>` and uses the shared `jsonMapper.parsePatch(body, UpdateAreaRequest::class.java, {title, position}, {title, position})`, the same way `ContextController.update` does. The request schema is still documented through `@DocumentedBody`. New test: `null` title, `null` position, a misspelled field and a wrongly typed value each give 400 Problem Details with the field. The area keeps ETag "0", and no `AREA UPDATED` event fires.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest'` — Pass

### CR-3 — A next-step body without `taskId` clears the next step

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/ProjectController.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/ProjectDtos.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/ProjectWebTests.kt`
- `api/openapi.yaml` (regenerated: `NextStepRequest.required: [taskId]`, description)
- `web/src/app/api/generated/models/next-step-request.ts` (regenerated: `taskId` is now required, still nullable)

Problem:
`{}` or a misspelled key such as `{"taskID": ...}` was handled like an explicit `{"taskId": null}` and cleared the project's next step.

Fix:
`setNextStep` now binds `Map<String, Any?>`. Unknown keys give 400 (`is not a field of this request`), and a missing `taskId` key gives 400 (`taskId`: `must be sent (null clears the next step)`). The value is converted with the shared `parsePatch`, so a value that is not a UUID is also 400. An explicit `"taskId": null` still clears the next step (the existing test still passes). `NextStepRequest.taskId` is documented as required and nullable. New test: `{}`, `{"taskID":null}`, `{"taskId":null,"extra":1}`, `{"taskId":"nope"}` and `{"taskID":"<id>"}` all give 400 Problem Details, and the project keeps its next step and version.

Validation:
- `UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'` (regeneration), then `cd web && npm run api:gen` — Pass
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest'` — BUILD SUCCESSFUL, 149 tests, 0 failures

Full test suite:
- `sh scripts/check.sh` (lint + unit tests of server, web, android) — exit 0, "all checks passed"

Lint/type-check/build:
- `./gradlew ktlintFormat` then `ktlintCheck` — Pass; web and android lint are covered by `scripts/check.sh` — Pass

`.taskcheck`:
- Pass (`sh scripts/check.sh`, exit 0)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
