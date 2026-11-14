# Fix Report

Date: 2026-11-14

Task: mail-task-archive-api - Mail → Next action & archive: server call

Scope: current task; findings CR-1 and CR-2 (both P3) from `reviews/mail-task-archive-api/review.md`. Base commit 6d395d2bd, task commits eb4075cc9..6f65ebca5. The main fixes were applied by the parent worker in commit 6f65ebca5; this fixer verified them and added a regression assertion and a one-phrase SPEC wording correction (uncommitted working-tree changes, for the parent worker to commit).

## Fixed findings

### CR-1 — OpenAPI request schema advertises values the server rejects

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailTaskArchiveController.kt` (6f65ebca5)
- `api/openapi.yaml` (6f65ebca5, regenerated)
- `web/src/app/api/generated/models/mail-to-tasks-request.ts` (6f65ebca5, regenerated)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd/MailTaskArchiveWebTests.kt` (this fixer, regression assertion)

Problem:
`MailToTasksRequest.list` was published with the full `TaskList` enum (including WAITING and SCHEDULED, which the server rejects with 400), and `ids` was published with `minItems: 0` although an empty list is rejected.

Fix:
In 6f65ebca5: `list` got `@field:Schema(allowableValues = ["NEXT", "INBOX", "SOMEDAY"])`; `ids` changed from `@NotEmpty` + `@Size(max = 100)` to `@NotNull` + `@Size(min = 1, max = 100)`. The regenerated `api/openapi.yaml` now has `minItems: 1`, `maxItems: 100` and `enum: [NEXT, INBOX, SOMEDAY]`; the generated web type is `list?: 'NEXT' | 'INBOX' | 'SOMEDAY'`. No non-generated web code uses this request type, so nothing else needed updating.

Verified that the annotation swap keeps the same runtime behaviour: `@NotNull` rejects a missing `ids`, `@Size(min = 1)` rejects an empty list (Bean Validation, 400 Problem Details), and WAITING/SCHEDULED are still rejected by the service. To lock this in, the fixer extended the existing test `invalid requests give 400 and create nothing`: for both the empty and the missing `ids` body it now asserts status 400, content type `application/problem+json` and `$.errors[0].field == "ids"` (previously only the status was checked).

Validation:
- `cd server && ./gradlew test --tests '*MailTaskArchive*' --tests '*OpenApiContractTest' ktlintCheck` — Pass (MailTaskArchiveWebTests 10/10, OpenApiContractTest 1/1, so the committed `api/openapi.yaml` matches what the server generates; ktlint clean)
- `cd server && ./gradlew integrationTest --tests '*MailTaskArchive*'` — Pass (MailTaskArchiveGreenMailTests 4/4)

### CR-2 — SPEC §7.4 response summary does not match the response shape

Files changed:
- `SPEC.md` (6f65ebca5, plus a wording correction by this fixer)

Problem:
SPEC §7.4 described the response as `per mail {task, archived, archiveErrorCode?}`, missing the `results` wrapper, `id`, `messageId`, `linkId`, `archiveDetail`, and suggesting `archiveErrorCode` is omitted rather than nullable.

Fix:
In 6f65ebca5 the line became `-> {results: [{id, messageId, linkId, task {id, title, list}, archived, archiveErrorCode, archiveDetail}]}`, which matches `MailToTasksResponse` / `MailToTaskResultResponse` field for field. The follow-up note said "error fields null when archived"; the DTO also returns null error fields when `archive=false` (no archive attempted), so the fixer changed it to "error fields null unless archiving failed", matching the schema descriptions ("`null` otherwise").

Validation:
- Manual comparison of SPEC.md §7.4 with `MailTaskArchiveController.kt` response classes — matches
- `cd server && ./gradlew test --tests '*MailTaskArchive*' --tests '*OpenApiContractTest' ktlintCheck` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests '*MailTaskArchive*' --tests '*OpenApiContractTest' ktlintCheck` — BUILD SUCCESSFUL; MailTaskArchiveWebTests 10 tests, 0 failures; OpenApiContractTest 1 test, 0 failures; ktlintCheck pass
- `cd server && ./gradlew integrationTest --tests '*MailTaskArchive*'` — BUILD SUCCESSFUL; MailTaskArchiveGreenMailTests 4 tests, 0 failures

Full test suite:
- Not run by the fixer (left to the parent worker's `.taskcheck` run; changes are one test assertion and one SPEC phrase)

Lint/type-check/build:
- `cd server && ./gradlew ktlintCheck` — Pass

`.taskcheck`:
- Not run (parent worker runs it before completion)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
