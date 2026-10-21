# Code Review

Date: 2026-10-21

Task: mail-actions-api - Mail actions (re-review of the final code after the fixes in ae534351)

Scope: `git diff origin/main...HEAD` as supplied by the parent worker: task commits 02831e50, b47aa0f3, f00d6973, ae534351 on top of task base ff67f337. Production code: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionController.kt`, `MailActionService.kt`, `api/openapi.yaml` and the generated web client models. Bash was not available to this reviewer, so the commit range could not be listed with git here. The review is based on reading the current files in full: the two production files, the generated models `mail-action-request.ts`, `mail-action-response.ts` and `mail-action-result-response.ts`, `MailActionServiceTests.kt`, `MailActionGreenMailTests.kt`, plus the context `MailMessageLocator`, `ChangeEventPublisher`, `SseEventHub` and `application.properties`.

Requirements:
- `tasks/mail-actions-api.md`: acceptance criteria 1-3 and the five recorded assumptions
- SPEC.md §4.2 and §7.3, as cited in the task and in the earlier review
- docs/ARCHITECTURE.md and the AGENTS.md project notes (logging rules, generated files, module boundaries)
- The earlier review `reviews/mail-actions-api/review.md` (CR-1..CR-4) and `reviews/mail-actions-api/fixes.md`

Git range:
- Branch: main
- HEAD: 8707665c (from the parent; could not be confirmed with git here)
- Base/start: ff67f337
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of the earlier findings

- **CR-1 (P2, cache-update failure aborts the bulk request): resolved.** `updateCache` (`MailActionService.kt:326-347`) runs `applyToRows` in its own `TransactionTemplate` transaction for each source folder. It catches `OptimisticLockingFailureException` and `DataIntegrityViolationException` and retries once on rows it reads again. If both attempts fail, it leaves the cache to the next sync, and the ids stay ok. `spring.jpa.open-in-view=false` (`application.properties:10`) and the non-transactional controller and service mean every attempt starts with a fresh persistence context, so the retry really reads the stored state. On a retry, `relocate` checks the destination `(folder_id, uid)` again (`:397`). The `MAIL_FOLDER` events are now published outside the transaction. `SseEventHub.onChange` uses `@TransactionalEventListener(fallbackExecution = true)` (`SseEventHub.kt:73`), so these events are still delivered. Three unit tests cover the paths: a version conflict that is retried, a version conflict that persists, and a duplicate destination row on retry.
- **CR-2 (P2, fallback and edge paths untested): resolved.** `MailActionServiceTests` stubs `IMAPStore`/`IMAPFolder` and covers: no MOVE, no UIDPLUS, no COPYUID for a linked and an unlinked row, COPYUID with another UIDVALIDITY or a UID already cached, a partial COPYUID, a plain EXPUNGE in Trash, read-only folders, NO answers, connection errors, a linked permanent delete, a source UIDVALIDITY mismatch and a target folder missing on the server. `MailActionGreenMailTests` adds a linked permanent delete, REJECTED, UIDVALIDITY, a source folder that is gone, partial moves, multi-folder requests and detached-id resolution against a real server.
- **CR-3 (P3, COPY fallback failing after the copy): resolved.** `move` (`:297-307`) logs a cleanup failure after a successful `UID COPY` (INFO without details, DEBUG with the cause) and keeps the `COPYUID` result. The class KDoc documents this. The test `without MOVE a failing cleanup after a successful copy keeps the COPYUID and reports ok` covers it.
- **CR-4 (P3, OpenAPI nullability and required fields): resolved.** `MailActionController.kt:84-102` marks `results`, `id` and `ok` as `REQUIRED`, and `errorCode`/`detail` as required with `types = ["string", "null"]`. The generated TypeScript matches: `results: Array<...>`, `id: string`, `ok: boolean` and `errorCode`/`detail: (string | null)`.

The fixes also added one more production change, which I checked as well: `remote()` (`:267-270`) now maps a `FolderNotFoundException` during the action (target folder deleted on the server, `NO [TRYCREATE]`) to `Remote.Rejected`. Before, it was reported as a connection failure, which also marked a healthy pooled connection as broken. `FolderNotFoundException` is caught before the general `MessagingException` handler, and a missing source folder is still handled earlier, at `open` (`:239-240`, `Remote.Gone`).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.

Acceptance criteria as implemented:
1. All eight actions are implemented. `operation()` (`MailActionService.kt:199-224`) maps DELETE to a move to Trash, or to a permanent expunge when the message is already in Trash. The server command runs first, then the cache update for each source folder. Each requested id gets one result, in request order, as the outcome map in `apply` (`:128-144`) builds it. This matches assumption #1 (200, one result per distinct id) and assumption #2 (NO_ROLE_FOLDER, a no-op when the message is already in the target, SPAM only moves). Validation errors (`targetFolderId` missing or not allowed, non-selectable target, 1000 ids at most, null ids) are 400. An unknown target folder is 404.
2. `MailActionGreenMailTests` exercises READ, UNREAD, FLAG, UNFLAG, MOVE, ARCHIVE, SPAM, DELETE to Trash and DELETE from Trash, checking both the server and the cache. The parent reports 16/16 passing. I did not run them, because Bash was not available to me.
3. The generated contract and web client match the controller annotations. The parent reports that `OpenApiContractTest` passes without `UPDATE_OPENAPI`. The fixes regenerated `api/openapi.yaml` with `./gradlew updateOpenApi`, not with the env-prefixed command named in the criterion. Both commands regenerate the same file, and the contract test passing without the flag is the part of the criterion that matters.

Notes for the parent worker, not findings:
- Logging follows the project rules: server responses and exceptions are logged only at DEBUG, and the INFO lines carry fixed text and error codes only.
- I verified the generated web client models, but not the `MailAction*` schemas inside `api/openapi.yaml` itself. That rests on the passing `OpenApiContractTest`.
- `tasks.py verify` evidence for criteria 1-3, and `tests.md` from the test-writer, were still open according to the task notes. They must be recorded before `done`.
