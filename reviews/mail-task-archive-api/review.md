# Code Review

Date: 2026-11-14

Task: mail-task-archive-api - Mail → Next action & archive: server call

Scope: committed changes of the task, `git diff e7419167b..fcd6b0a21` (static review; Gradle not run by request, another helper was running it)

Requirements:
- tasks/mail-task-archive-api.md acceptance criteria and recorded assumption (POST /api/v1/mail/messages/to-tasks, ids 1..100, list NEXT/INBOX/SOMEDAY, unknown id -> 404 with nothing created, duplicate ids share one task)
- SPEC.md §7.3, §7.4, §4.2 (Problem Details)
- AGENTS.md project notes (module boundaries via services, generated OpenAPI/client, lint)
- Existing code: MailActionService (ARCHIVE action, outcomes), MailMessageLocator, MailGtdService.toTask

Git range:
- Branch: main
- HEAD: fcd6b0a21b8ae2c322853352ea8560a23ffc300b
- Base/start: e7419167baed7fc2a04732c5ca0a764e975ffa6d
- Commits reviewed: 6 (0c5b460bb, 5804e0186, c82d480d0, dcb18d7df, 6cc27fe4c, fcd6b0a21)
- Uncommitted changes: `MailTaskArchiveWebTests.kt` is modified in the working tree (the parallel test-writer's work); not part of this review.

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Acceptance criteria check

1. One call with ids (1..n), list (default NEXT), archive (default true); per mail a subject-titled task linked like `to-task`, then ARCHIVE: met. `MailGtdService.toTasks` resolves all ids, creates tasks via `TaskService.create` and links via the same `linked` helper as `toTask`, then calls `MailActionService.apply(..., MailAction.ARCHIVE, null)`. Defaults applied in `MailTaskArchiveController.toTasks`.
2. Per-mail result (task id/title/list, archived, reason); failed archive keeps task; unknown id -> Problem Details, nothing created: met. Resolution and creation run in one transaction, so a `NotFoundException` from `current` rolls back every task and link; archiving runs after the commit and `apply` turns connection, rejection and missing-folder failures into outcomes rather than exceptions. A row moved or removed by archiving is relocated or kept detached while linked (`MailActionService.remove`), so the link stays valid. Tested for an unknown id (404, problem+json, 0 tasks/links) and for NO_ROLE_FOLDER.
3. Link resolves after archiving, also without MOVE: met. The GreenMail test's `NoMoveImapStore` overrides `hasCapability("MOVE")`, which is the exact check `MailActionService.move` uses to pick the COPY+EXPUNGE path, and the test asserts the hook was hit.
4. `to-task` unchanged: met. `MailGtdController` and `MailGtdService.toTask` are untouched in the diff; the only constructor change is an added dependency.
5. OpenAPI and generated client regenerated; SPEC §7.3/§7.4 updated: met. See CR-1 and CR-2 for small contract-text inaccuracies.
6. Tests for single, multiple, partial archive failure and link: present in both test classes. Running `./gradlew check` / `scripts/check.sh` was not checked in this static review.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | OpenAPI request schema advertises values the server rejects**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailTaskArchiveController.kt:80` (generated: `api/openapi.yaml:13209-13219`, `web/src/app/api/generated/models/mail-to-tasks-request.ts:19`)
    - Category: Integration
    - Problem: `list` is typed as the full `TaskList` enum, so the contract and the generated TypeScript type allow `WAITING` and `SCHEDULED`, which `MailGtdService.toTasks` rejects with 400. `ids` comes out as `minItems: 0` although `@NotEmpty` rejects an empty list.
    - Why it matters: the generated client type lets frontend code compile calls that always fail. Only the description text states the real limits.
    - Suggested fix: restrict the schema, e.g. `@field:Schema(allowableValues = ["NEXT", "INBOX", "SOMEDAY"])` on `list` and `@field:Size(min = 1, max = MAX_IDS)` (or an `ArraySchema` with `minItems = 1`) on `ids`, then regenerate `api/openapi.yaml` and the web client.

- [ ] **CR-2 | SPEC §7.4 response summary does not match the response shape**
    - File: `SPEC.md:424`
    - Category: Quality
    - Problem: the line reads `-> per mail {task, archived, archiveErrorCode?}`. The real result also has `id`, `messageId`, `linkId` and `archiveDetail`, and `archiveErrorCode` is always present and nullable, not optional (the `?` suggests it is omitted). Results are also wrapped in `{results: [...]}`.
    - Why it matters: SPEC.md is the source of truth that client tasks (web, Android) will read for this endpoint.
    - Suggested fix: describe it as `-> {results: [{id, messageId, task {id, title, list}, linkId, archived, archiveErrorCode|null, archiveDetail|null}]}`, or point to the OpenAPI schema.

## Review conclusion

No material issues found. The two P3 findings are contract-text precision issues and can be fixed cheaply with the next regeneration. They do not block the acceptance criteria.
