# Code Review

Date: 2026-10-21

Task: mail-actions-api - Mail actions

Scope: implementation commits of the task, `git diff d38346e1..b8283a43` (741bacce, b8283a43). The uncommitted test file `MailActionControllerTests.kt` (test-writer, in progress) is not part of this review.

Requirements:
- `tasks/mail-actions-api.md` acceptance criteria and recorded assumptions (#1-#5)
- SPEC.md §4.2, §4.3, §7.2, §7.3, §7.4
- docs/ARCHITECTURE.md, AGENTS.md project notes
- Existing mail module code: `MailHeaderSyncService`, `MailHeaderStore`, `MailMessageLocator`, `ImapConnectionPool`, `ProblemDetailsAdvice`

Git range:
- Branch: main
- HEAD: b8283a43bca22e4e3cdb939842776ad3c8919972
- Base/start: d38346e130716e1f25ef76e0b936238d4fadc475
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 2 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A cache-update failure aborts the whole bulk request after the IMAP changes are already applied**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt:182` (call), `:305-321` (`updateCache`), `:334-340` (`relocate`)
    - Category: Correctness
    - Problem: `applyInFolder` catches only `MailConnectionException`. The cache update after a successful server command runs in its own transaction on versioned `MailMessage` rows (`BaseEntity.@Version`), without the per-folder lock that `MailHeaderSyncService` uses for syncs (`lock(folderId)`, `MailHeaderSyncService.kt:237`). A concurrent writer of the same rows, for example a second action request (double submit, web and Android) or a folder sync (`MailHeaderStore.reconcile`/`upsert`), makes `messages.flush()` throw `OptimisticLockingFailureException`. `ProblemDetailsAdvice` turns that into a 409 for the whole request. If a sync inserts a row at the destination `(folder_id, uid)` between the `findByFolderIdAndUid` check (`:339`) and the flush, the unique index `mail_message_folder_uid` gives a `DataIntegrityViolationException`, which becomes a 500. Either way the server state has already changed for that folder group, and any groups that ran before it are applied and committed. The client still gets no per-id results. The endpoint contract ("200 ... also when some or all of them failed") does not mention a 409 or 500 in this situation.
    - Why it matters: Acceptance criterion 1 requires partial failures to be reported per id and IMAP and cache to stay consistent. SPEC §7.2 plans IDLE on INBOX, and IDLE fires exactly when an action expunges from INBOX, so this race becomes a normal path once sync scheduling exists. Concurrent requests can already trigger it today.
    - Suggested fix: Run each folder group's cache update so that a failure stays inside that group. One way is to catch `OptimisticLockingFailureException`/`DataIntegrityViolationException` per group, then retry the update once on fresh rows or fall back to `remove` (detach) so the next sync repairs the row. Report the ids as ok, because the server action succeeded. Another option is to serialize with the sync by sharing its per-folder lock for the source and destination folders. Add a test for at least the version-conflict path.

- [ ] **CR-2 | The fallback and edge paths of moves and deletes are not tested**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailActionGreenMailTests.kt` (whole file, commit b8283a43); production paths in `MailActionService.kt:236`, `:257-260`, `:276-286`, `:294`, `:334-345`
    - Category: Tests
    - Problem: The GreenMail tests always run against GreenMail's own capability set. Only the `UID MOVE` path or the `UID COPY` + `\Deleted` + expunge path runs, whichever GreenMail advertises, and the other never does. The UIDPLUS-less plain `EXPUNGE` is never exercised either. Several paths have no test: the missing or unusable `COPYUID` branch of `relocate` (the row is removed, or detached when linked, then brought back by the next destination sync, as assumption #3 describes); a permanent delete of a linked message (it must stay detached, not be deleted); the destination row already cached at the new UID; a source UIDVALIDITY mismatch (`Remote.Gone`); and a server rejection (`REJECTED`). Assumption #3 makes the no-COPYUID behavior an explicit design decision, but nothing verifies it.
    - Why it matters: These are the branches where cache consistency can go wrong, for example a duplicate or lost row, or a link left pointing at nothing. Acceptance criterion 1 requires IMAP and cache to be applied "consistently".
    - Suggested fix: Add tests for these branches. A focused unit or integration test with a stubbed `IMAPStore`/`IMAPFolder` (capabilities without MOVE or UIDPLUS, `copyUIDMessages` returning `null`) can cover the fallback branches. Add an external test that links the message to an item, then deletes it twice and checks that the row is detached rather than deleted. If GreenMail's capabilities can be configured, cover both move paths there too.

## P3 Low

- [ ] **CR-3 | A COPY fallback that fails after the copy reports a failure and leaves a duplicate on retry**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt:280-283`
    - Category: Correctness
    - Problem: Without MOVE, `copyUIDMessages` succeeds first, then `setFlags(\Deleted)` and `expunge` run. If either of them fails (`CommandFailedException` gives `REJECTED`, an I/O error gives `CONNECTION_FAILED`), every id of the group is reported as failed and the cache is left untouched. The message already exists in the destination, and the source copy may still carry `\Deleted`. If the user retries, a second copy is made in the destination.
    - Why it matters: This is a rare path, but it leaves duplicate mail in Archive or Trash and reports a failure for a copy that partly succeeded.
    - Suggested fix: When the copy succeeded but the cleanup failed, keep the `COPYUID` result. Either report the ids as ok and leave the source expunge to the next sync, or at least do not retry the copy blindly. Document the chosen behavior in the KDoc.

- [ ] **CR-4 | The OpenAPI result schema does not match the actual response nullability and required fields**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionController.kt:473-484`; `api/openapi.yaml` (`MailActionResultResponse`, `MailActionResponse`); `web/src/app/api/generated/models/mail-action-result-response.ts`
    - Category: Integration
    - Problem: The generated contract marks `errorCode` and `detail` as required, non-null `string`, but the server sends `null` for both on success. `nullable = true` is lost in the OpenAPI 3.1 output. Meanwhile `id`, `ok` and `results` are optional (`id?`, `ok?`, `results?` in the generated TypeScript), although the server always sends them. Some existing DTOs have the same generator behavior, but this endpoint's clients (web triage, Android) will be written against this contract.
    - Why it matters: Typed clients get the wrong guarantees. For example, `errorCode` is typed as never null, and `ok` has to be null-checked.
    - Suggested fix: Mark `id`, `ok` and `results` with `requiredMode = REQUIRED`. Make `errorCode`/`detail` nullable in the generated 3.1 schema (`types = ["string", "null"]`) or make them not required, then regenerate `api/openapi.yaml` and the web client.

## Review conclusion

Material issues found; fix before completing the task.

The implementation covers all eight actions, per-id results in request order, role-folder resolution, same-account checks, COPYUID-based relocation that keeps row ids, UIDVALIDITY guards, and pooled-connection health (rejections and missing folders are returned instead of thrown, so the connection is not dropped). It follows the logging rules (no server responses above DEBUG, fixed safe failure messages). CR-1 (consistency and per-id reporting under concurrent cache writes) and CR-2 (untested fallback paths) should be resolved or explicitly dispositioned before `done`. No acceptance-criterion evidence was recorded at the time of review (`tasks.py verify mail-actions-api` shows all three criteria open).
