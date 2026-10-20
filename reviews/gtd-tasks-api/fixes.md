# Fix Report

Date: 2026-10-20

Task: gtd-tasks-api - Tasks API

Scope: Code-review findings CR-1..CR-4 from `reviews/gtd-tasks-api/review.md` (reviewed revision 3ac3056).

Git scope: `git diff a67434529b8fa4cd07205312c877dfda021e9b5d..HEAD -- server api web/src/app/api/generated`, HEAD 4b839e5. Task commits are f98c23a, c438ac2 and 9d13a3c, plus merge 4b839e5 (main merged in, `api/openapi.yaml` and the web client regenerated). The merge changes nothing in the gtd module. Compared with 9d13a3c, it adds only reminders paths and models to the contract and the client.

Who changed what: the parent worker (dell26/agent-6) applied all four fixes in commit 9d13a3c, before the fixer ran. The fixer read the code, the regenerated contract and the generated client against each finding, then ran the validation below. The fixer found nothing incomplete or wrong and made no further code changes. The fixer wrote only this report.

## Fixed findings

### CR-1 - Changing a task whose project is in the Trash fails with 400 "projectId does not exist"

Files changed (by the parent in 9d13a3c):
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/TaskWebTests.kt`

Problem:
`validate` checked after every change that the task's project is active. That made PATCH, complete, uncomplete and move fail for tasks whose project is in the Trash.

Fix:
`validate(task, checkProject)` now checks the project only on create (`checkProject = true`) and in `modify` when `task.projectId != oldProject`. Every path that changes a task goes through `modify`: update, complete, uncomplete and move. So a task in a trashed project can still be changed, completed and moved. Setting a new, unknown or trashed project is still rejected. New test: `a task whose project is in the Trash can still be changed, completed and moved`. It covers PATCH, complete, uncomplete, move that keeps the project, move into an active project, and the 400 errors for PATCH or create pointing at a trashed or unknown project.

Fixer verification: read `create`, `modify`, `move` and `validate` in full. Confirmed that no change path skips `modify`. Confirmed that the test ran and passed (it appears in the JUnit XML).

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest'` - Pass

### CR-2 - `maxMinutes` is documented as `type: string`

Files changed (by the parent in 9d13a3c):
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/TaskController.kt`
- `api/openapi.yaml`
- `web/src/app/api/generated/fn/tasks/list-tasks.ts`

Problem:
`@Parameter(schema = Schema(minimum = "0"))` dropped the inferred integer type.

Fix:
The annotation is now `Schema(type = "integer", format = "int32", minimum = "0")`. At HEAD, `api/openapi.yaml` (line 1013) shows `type: integer, format: int32, minimum: 0` for `maxMinutes`, and the generated client has `maxMinutes?: number`.

Fixer verification: `OpenApiContractTest` passes without `UPDATE_OPENAPI`, so the contract matches the server. Running `npm run api:gen` again left the working tree clean, so the client matches the contract.

Validation:
- `OpenApiContractTest` (no flag) - Pass
- `npm --prefix web run api:gen`, then `git status --short` - no changes

### CR-3 - The PATCH schema does not allow `null`, although `null` clears a field

Files changed (by the parent in 9d13a3c):
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/TaskDtos.kt`
- `api/openapi.yaml`
- `web/src/app/api/generated/models/task-patch-request.ts`

Problem:
The clearable `TaskPatchRequest` fields were not nullable in the OpenAPI 3.1 schema, so the generated TypeScript model could not express a `null` value.

Fix:
Every clearable property now has `@get:Schema(types = [..., "null"])`: notesMd, projectId, energy, creativity, timeEstimateMin, dueDate, dueTime, scheduledDate, waitingForContactId, waitingForText, waitingSince, recurrenceRule and recurrenceMode. A new `TaskPatchNullEnums` OpenApiCustomizer adds `null` to the enum values of energy, creativity and recurrenceMode, because in 3.1 an `enum` without `null` would still reject null. It follows the existing `SettingsPatchNullEnums` pattern. `title`, `position` and `contextIds` stay non-nullable, which matches the documented contract that they cannot be cleared. The generated model now has `(... | null)` for all clearable fields.

Fixer verification: read the DTO, the customizer and the regenerated `task-patch-request.ts` at HEAD. The contract and client are current, as shown under CR-2.

Validation:
- `OpenApiContractTest` (no flag) - Pass
- `npm --prefix web run api:gen` - no diff

### CR-4 - Create and PATCH can break the list rules that `/move` enforces

Files changed (by the parent in 9d13a3c):
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/TaskDtos.kt` (description of `CreateTaskRequest.list`)
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/TaskWebTests.kt`

Problem:
`validate`, which create and PATCH use, did not reject `scheduledDate` outside SCHEDULED or waiting-for fields and `waitingSince` outside WAITING.

Fix:
`validate` now enforces these rules on every path:
- `scheduledDate` only on SCHEDULED;
- `waitingForContactId`, `waitingForText` and `waitingSince` only on WAITING.

`move` clears these fields when a task leaves the list, so moves still pass `validate`. Create on WAITING sets `waitingSince` before `validate`. New test: `create and PATCH enforce the list rules of move`. It covers 400 on create and PATCH for each field, an unchanged version after a rejected PATCH, and successful PATCHes on WAITING and SCHEDULED tasks. The existing `move validates the list rules` test still passes.

Fixer verification: read `validate` and `move`. Confirmed that the invariants match assumption #2 and that the tests ran and passed.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest'` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest'` (Postgres via Testcontainers, Docker available) - BUILD SUCCESSFUL. 55 tests, 0 failures, 0 skipped: OpenApiContractTest 1, GtdItemResolverTests 8, ChecklistWebTests 3, GtdRepositoryTests 22, TaskWebTests 21.

Full test suite:
- Not run by the fixer. `.taskcheck` (`sh scripts/check.sh`) is left to the parent worker before `tasks.py done`.

Lint/type-check/build:
- `cd server && ./gradlew ktlintCheck` - BUILD SUCCESSFUL
- `npm --prefix web run api:gen` - generated client unchanged (clean `git status`)

`.taskcheck`:
- Not run (the parent worker runs it).

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed. The fixes are the parent's commit 9d13a3c. The fixer verified them and made no further code changes.
