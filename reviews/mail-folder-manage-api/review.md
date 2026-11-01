# Code Review

Date: 2026-11-01

Task: mail-folder-manage-api - Mail folders: create, rename and delete (API)

Scope: `git diff 6ba9af35dab9cc0c621ddb0f737c04d16ad9adf5..427f62045`, committed changes only (re-review of the final code, including the fixes for CR-1 and CR-2 of the previous review)

Requirements:
- tasks/mail-folder-manage-api.md acceptance criteria 1-5 and the six recorded assumptions in its Notes (including the two added with 41a47b156: per-account in-process lock, 400 for an explicit `{"name": null}`)
- SPEC.md §4.2 conventions (Problem Details, ETag/If-Match), AGENTS.md project notes (logging, generated files), docs/ARCHITECTURE.md conventions
- Existing code: `MailFolderService.sync/store` (vanished-folder handling), `MailHeaderSyncService`/`MailHeaderStore` (folder sync state), `MailPushService` (callers of `sync`), `MailConnectionAdvice`, `ProblemDetailsAdvice`, `ETags`, `MailSearchIndex.unindexFolder`

Git range:
- Branch: main
- HEAD: 427f62045
- Base/start: 6ba9af35dab9cc0c621ddb0f737c04d16ad9adf5
- Commits reviewed: 4 (7f1214d99 API; ecf9c14be GreenMail tests, regenerated OpenAPI and web client; 41a47b156 per-account folder lock and refusal of an explicit null name, plus tests; 427f62045 lock and null-name tests, test report)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

Reviewed files: `MailFolderService.kt` (in full), the `MailAccountController.kt` and `MailSessions.kt` diffs, `MailFolderManageGreenMailTests.kt` (rename/delete/refusal tests), `MailFolderManageTests.kt` (delete and serialization tests), the test names of `MailFolderManageWebTests.kt`, the `api/openapi.yaml` diff and the regenerated `web/src/app/api/generated` files. Uncommitted changes: none.

Verification of the previous findings:
- Previous CR-1 (rename/sync race; delete role check outside the lock): fixed. `sync`, `overrideRole`, `create`, `rename` and `delete` now all run through `serialized(accountId)` (`MailFolderService.kt:56-61`). It holds a per-account `ReentrantLock` across the IMAP commands and the cache transaction. The delete INBOX/role subtree check (`deleteSerialized`) runs under that lock, and so do all role writers (`overrideRole`, and `sync` through role detection). So no role can be assigned between the check and the delete. No deadlock with the DB row lock: every lock taker takes the in-process lock first and the `PESSIMISTIC_WRITE` account row lock second. The other holder of that row lock, `MailIdentityService`, never calls into the folder service. The callers of `sync` (`MailPushService` poll and `inbox()`, `MailAccountController`, the test-support controller) do not run inside a transaction that holds the row lock. Both interleavings are covered by tests: a sync waiting for a running RENAME, and a rename waiting for a sync that is listing (`MailFolderManageTests.kt:488-567`). Another test checks that the lock is per account, not global (`:570`). Header syncs of a renamed folder that run at the same time open the old path, get `RemoteFolderNotFoundException` and are skipped, so no messages are lost. One remaining, much narrower edge case is CR-1 below.
- Previous CR-2 (`{"name": null}` clears the role): fixed. The `name` setter records `nameSent`, and the controller rejects `nameSent && name == null` with 400 on `name` before any service call (`MailAccountController.kt` `updateFolder`). `{}` and `{"role": null}` keep the legacy behaviour (assumption 2). Covered by `MailFolderManageWebTests` "an explicit null name is 400 on name and neither renames nor changes the role".

Criteria check (code reading):
- AC1 create: CREATE then best-effort SUBSCRIBE, under the parent's path with the server delimiter or at the top level, cached under the account lock (it also accepts a row a parallel sync stored first), 201 with ETag and `MailFolderResponse`. Met.
- AC2 rename: RENAME under the same parent, best-effort re-SUBSCRIBE. The subtree paths are rewritten in place, so the folder and message ids stay. INBOX is refused, a name-only body leaves the role alone, If-Match is enforced, and a name plus role uses the renamed folder's new ETag for the role step. A changed UIDVALIDITY is left to the existing header sync (`syncFolder` compares `uidValidity`). Met.
- AC3 delete: recursive server delete with best-effort UNSUBSCRIBE. Cache rows are removed the same way `store()` removes vanished folders (unindex, delete with FK `folder_id` set to null, DELETED event). A folder already gone on the server is only uncached. INBOX, role folders and folders holding one are refused with 409. Met.
- AC4 errors: blank name or delimiter -> 400 on `name`; name taken -> 409 (INBOX in any case at the top level); unknown account or folder -> 404; If-Match -> 412/428 via `ETags.checkIfMatch`; server refusals -> `SERVER_REJECTED`; unreachable server -> 502 with `errorCode` through the shared `MailConnectionAdvice` (pool exhaustion -> 503, as for all mail controllers). Every changed folder publishes `MAIL_FOLDER`. Met.
- AC5: OpenAPI and web client are regenerated and consistent (`createMailFolder`, `deleteMailFolder`, `CreateMailFolderRequest` with maxLength 200, `name` on `UpdateMailFolderRequest`, `SERVER_REJECTED` in the errorCode enums; `nameSent`/`roleSent`/`changesRole` hidden by `@JsonIgnore`). The GreenMail tests cover create (top level, nested, before any sync), rename with subfolders and messages (ids and links kept, later folder and header sync keep the rows), delete (messages detached, linked one kept), already-gone delete, and the refused cases including 412/428/404/502. I did not run Gradle, at the parent's request: it is running `./gradlew check` and the GreenMail suite itself. The existing result files show 0 failures: `MailFolderManageTests` 23, `MailFolderManageWebTests` 11, `MailFolderSyncTests` 25 (written 2026-11-01 05:51Z, after 41a47b156), and `MailFolderManageGreenMailTests` 13 (05:27Z, about 30 s before the 41a47b156 commit). `tasks.py verify` still lists all 5 criteria as unticked. The parent must record its current runs as evidence.

Security/logging: refusals log at DEBUG only, and `MailConnectionAdvice` logs only the error code at INFO. No folder contents, credentials or message data in logs. The endpoints sit under the existing authenticated `/api/v1/mail/accounts` controller, and account ownership of folders is checked (`folder()` compares `accountId`, tested). Test data is fictitious. No secrets in the diff.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A rename whose cache step fails after the server RENAME leaves the cache diverged**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailFolderService.kt:267-276` (rename cache transaction); concurrent writer `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderStore.kt:151-160` (`finish` saves the folder row, bumping its `@Version`)
    - Category: Correctness
    - Problem: header syncs (`MailHeaderSyncService.syncFolder`) are not serialized by the new account lock, and `finish()` updates the versioned `mail_folder` row. If `finish()` commits between the rename transaction's `subtree(...)` read and its `folders.flush()`, the flush fails with an optimistic lock exception. `ProblemDetailsAdvice` turns that into 409, but the server has already renamed the folder. The cache still holds the old paths, so the next folder sync treats the subtree as vanished and recreates it with new ids, detaching its messages. The window is milliseconds and needs a header sync of the same folder to finish at that moment, so the risk is low.
    - Why it matters: this is the same outcome that AC2 and the per-account lock aim to prevent, through a rarer path. The client also gets a 409 for a rename that actually happened.
    - Suggested fix: make the path rewrite independent of the folder version. For example, retry the cache step once on `OptimisticLockingFailureException`, or rewrite the paths with a bulk JPQL update (and bump the version explicitly). Alternatively, record the remaining risk as an assumption.

- [ ] **CR-2 | `accountLocks` keeps a lock for every account id ever requested**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailFolderService.kt:56-61`
    - Category: Quality
    - Problem: `serialized` runs `computeIfAbsent` before the account is checked, so each request with an unknown account UUID (404) adds a `ReentrantLock` to the map for good. Entries of deleted accounts are never removed either.
    - Why it matters: the growth is small and needs an authenticated caller (single-owner app), so this is a minor leak, not a security risk. Still, the map is unbounded.
    - Suggested fix: check that the account exists before taking the lock (the operations re-check it under the lock anyway), or use a striped or weak-valued lock holder.

## Review conclusion

No material issues found.

The fixes for both earlier findings are correct and complete, and covered by tests. All five acceptance criteria are implemented as specified. The two P3 items are optional hardening; the parent decides whether to fix them or record them. Before `done`, the parent must still record acceptance-criterion evidence from its current runs of `cd server && ./gradlew check` and `./gradlew integrationTest --tests '*Folder*GreenMail*'`.
