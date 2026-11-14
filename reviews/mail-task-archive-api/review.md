# Code Review

Date: 2026-11-14

Task: mail-task-archive-api - Mail → Next action & archive: server call

Scope: committed changes of the task, `git diff 6d395d2bd..8a2a1bdb9` (commits eb4075cc9..8a2a1bdb9). Static review; Gradle was not run by request (a parallel test-writer was using the build directory). This is a re-review after the fixes for CR-1 and CR-2 of the previous review round.

Requirements:
- tasks/mail-task-archive-api.md acceptance criteria and the recorded assumption (POST /api/v1/mail/messages/to-tasks, ids 1..100, list NEXT/INBOX/SOMEDAY, unknown id gives 404 with nothing created, ids resolving to the same message share one task)
- SPEC.md §7.3, §7.4, §4.2 (Problem Details)
- AGENTS.md project notes (module boundaries via services, generated OpenAPI and client, lint)
- reviews/mail-task-archive-api/fixes.md
- Existing code: MailActionService (ARCHIVE action, per-id outcomes), MailMessageLocator, MailGtdService.toTask, MailGtdController

Git range:
- Branch: main
- HEAD: 8a2a1bdb9be5438b0901bd4b505a66bb4c80ca41
- Base/start: 6d395d2bd84636c15ff3750b26c75f8016031359
- Commits reviewed: 10 (eb4075cc9, 240c0e31d, 5bff30f56, b18de39c9, 0d6c4a47b, 27f232fb8, 8764922ca, f3ad794b2, 6f65ebca5, 8a2a1bdb9)
- Uncommitted changes: none at the start of the review

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of previous findings

- CR-1 (P3, OpenAPI request schema advertised values the server rejects): resolved. `MailToTasksRequest.list` now carries `@field:Schema(allowableValues = ["NEXT", "INBOX", "SOMEDAY"])` and `ids` uses `@field:NotNull` plus `@field:Size(min = 1, max = MAX_IDS)` (`MailTaskArchiveController.kt:82-90`). The regenerated `api/openapi.yaml` has `minItems: 1`, `maxItems: 100` and `enum: [NEXT, INBOX, SOMEDAY]`; the generated type is `list?: 'NEXT' | 'INBOX' | 'SOMEDAY'`. Runtime behaviour is unchanged: missing and empty `ids` still give 400 Problem Details on field `ids`, and WAITING/SCHEDULED are still rejected by `MailGtdService.toTasks`. `MailTaskArchiveWebTests` now asserts content type and field for the empty and missing cases (lines 189-196).
- CR-2 (P3, SPEC §7.4 response summary did not match the response shape): resolved. `SPEC.md` §7.4 now lists `{results: [{id, messageId, linkId, task {id, title, list}, archived, archiveErrorCode, archiveDetail}]}`, notes that the error fields are null unless archiving failed (true also for `archive=false`), and documents the 404-nothing-created behaviour. This matches `MailToTasksResponse` and `MailToTaskResultResponse` field for field.

## Acceptance criteria check

1. One call with ids (1..n), list (default NEXT), archive (default true); per mail a subject-titled task linked like `to-task`, then ARCHIVE: met in code. Defaults are applied in `MailTaskArchiveController.toTasks`; `MailGtdService.toTasks` uses the same `titleOf` and `linked` helpers as `toTask` and then calls `MailActionService.apply(..., MailAction.ARCHIVE, null)`.
2. Per-mail result with task id/title/list, archived flag and reason; failed archive keeps the task; unknown or no-longer-present id gives Problem Details and creates nothing: met in code. All ids are resolved inside the creating transaction before the first task is created, so a `NotFoundException` rolls back everything; archiving runs after the commit and `MailActionService.apply` reports failures as per-id outcomes. Outcomes are keyed by the linked current message id, which is exactly the id passed to `apply`, so the lookup in `toTasks` is consistent. Tests cover unknown id, detached (gone) row, and NO_ROLE_FOLDER both without a server and against GreenMail with a second account.
3. Link resolves after archiving, also without MOVE: met and verified by the recorded GreenMail run. `NoMoveImapStore` overrides `hasCapability("MOVE")` and the test asserts that the hook was hit, then checks the link resolution before and after a re-sync.
4. `to-task` unchanged: met. `MailGtdController` and `MailGtdService.toTask` are not modified in the diff; the only change to the service is an added constructor dependency on `MailActionService` (no cycle: `MailActionService` does not depend on the GTD integration).
5. OpenAPI and generated client regenerated; SPEC §7.3 and §7.4 updated: met. The generated files contain only the new operation and its four schemas. The 200 response also advertising `application/problem+json` and the generated `accept: 'application/problem+json'` match the existing `to-task` endpoint and other controllers using `produces = [APPLICATION_JSON, PROBLEM_JSON]`, so this is project convention, not a new inconsistency.
6. Tests cover single, multiple, partial archive failure and the link: present (`MailTaskArchiveWebTests` 10 tests, `MailTaskArchiveGreenMailTests` 4 tests). Whether `./gradlew check` and `sh scripts/check.sh` pass on HEAD was not checked in this static review; criteria 1, 2, 4 and 6 still need their `tasks.py verify` evidence recorded by the parent worker before `done`.

## Other checks

- Security: endpoint is under the authenticated API with CSRF (tested: missing token gives 403); no logging of subjects or bodies added; error details come from the existing `MailActionOutcome` texts.
- Validation: null elements in `ids` give 400 via `InvalidRequestException`; malformed UUIDs, unknown list values and more than 100 ids give 400; tested.
- Duplicate and moved ids: deduplicated by requested id and by resolved message, one task per message, one result per distinct requested id in request order; tested with the old id of a moved message.
- Partial failure semantics: an unexpected exception thrown by `apply` after the commit would surface as 500 with the tasks kept. This is the same failure mode as the actions endpoint and is within the "no rollback" requirement; not reported as a finding.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. Both previous findings (CR-1, CR-2) are resolved in 6f65ebca5 and 8a2a1bdb9. The remaining step for the parent worker is recording evidence for criteria 1, 2, 4 and 6 (including `./gradlew check` and `sh scripts/check.sh` on the final code).
