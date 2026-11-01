# Code Review

Date: 2026-11-01

Task: mail-folder-manage-api - Mail folders: create, rename and delete (API)

Scope: `git diff 22e2962f8..HEAD` (task commits 9d116fdb0, 44001f74b), committed changes only

Requirements:
- tasks/mail-folder-manage-api.md acceptance criteria 1-5 and the four recorded assumptions (`python tasks.py assumptions`)
- SPEC.md §4.2 conventions (Problem Details, ETag/If-Match), AGENTS.md project notes (logging, generated files), docs/ARCHITECTURE.md conventions
- Existing code: `MailFolderService.sync/store` (vanished-folder handling), `MailConnectionAdvice`, `ETags`, `MailSearchIndex.unindexFolder`

Git range:
- Branch: main
- HEAD: 44001f74b87d5d73bf27a4e3e9a706a27172343b
- Base/start: 22e2962f847549c4cc4cef171f05286c66d52fe5
- Commits reviewed: 2 (9d116fdb0 mail: create, rename and delete folders (API); 44001f74b mail: folder management GreenMail tests, regenerated API and web client)

Summary: 0 P0, 0 P1, 1 P2, 1 P3

Reviewed files: `MailFolderService.kt`, `MailAccountController.kt`, `MailSessions.kt` (read in full where changed), `MailFolderManageGreenMailTests.kt`, the `api/openapi.yaml` diff and the regenerated `web/src/app/api/generated` files (create/delete functions, request models, `SERVER_REJECTED` enum additions).

Criteria check (code reading):
- AC1 create: CREATE + best-effort SUBSCRIBE, parent path + server delimiter, cached, 201 + ETag. Met. A parallel sync is handled by the `findByAccountIdAndPath` lookup under the account lock.
- AC2 rename: RENAME under the same parent, subtree paths rewritten in place (ids kept), INBOX refused, role untouched for a name-only body, If-Match enforced. UIDVALIDITY handling is left to the existing sync. Met, apart from CR-1.
- AC3 delete: recursive server delete, cache rows deleted via the same path as `store()` (unindex, then delete, then DELETED event; FK sets `folder_id` null), INBOX/role/role-holding subtree refused with 409, If-Match enforced. Met.
- AC4 errors: blank/delimiter -> 400 (`InvalidRequestException` on `name`), taken -> 409, 404, 412/428 via `ETags.checkIfMatch`, refusals -> `SERVER_REJECTED` and unreachable -> 502 through the shared `MailConnectionAdvice`, `MAIL_FOLDER` events per changed folder (consistent with sync). Met.
- AC5: OpenAPI and client regenerated and consistent (`roleSent`/`changesRole` correctly hidden by `@JsonIgnore`). GreenMail tests cover create (top level, nested, before any sync), rename with subfolders and messages (ids and links kept, later sync and header sync keep rows), delete (messages detached, linked one kept), and the refused cases. The reviewer could not run the tests here because Bash permission for Gradle was denied. No acceptance-criterion evidence was recorded yet at review time (`tasks.py verify` lists 5 unticked criteria). The parent worker must record actual runs of `./gradlew check` and `./gradlew integrationTest --tests '*Folder*GreenMail*'`.

Security/logging: refusals and pool errors are logged at DEBUG only. No credentials, folder contents or message data reach logs above DEBUG (`MailConnectionAdvice` logs only the error code at INFO). Test data is fictitious. No secrets in the diff.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Rename races with a parallel folder sync and can lose the "ids are kept" guarantee**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailFolderService.kt:202-235` (remote RENAME outside the account lock, cache update at 222-235); `sync` at 59-68
    - Category: Correctness
    - Problem: `rename` runs the IMAP `RENAME` first and only takes the account lock (`lockAccount`) afterwards, to rewrite the cached paths. `sync` lists the remote folders outside the lock and applies the list under it. A folder poll that overlaps the rename (the scheduler runs these) can interleave in two ways:
      (a) Sync lists after RENAME and stores before the rename transaction. `store()` treats `oldPath` as vanished: it deletes the cached folder and its subfolders (messages detached, unindexed) and creates new rows with new ids for `newPath`. The rename transaction then finds no subtree and throws `NotFoundException`, so the client gets a 404 although the server rename succeeded.
      (b) Sync lists before RENAME and stores after the rename commits. The cache now holds `newPath`, which is missing from the stale list, so sync deletes the renamed rows and recreates `oldPath`. The next sync swaps it back, again with new ids.
      In both cases the folder ids change and the cached messages are detached. That is exactly what AC2 requires to avoid ("cached messages keep their ids, so links stay valid"; folder ids are what open clients hold). `delete` has a smaller variant: the INBOX/role check at line 251 runs before the lock, so a role assigned in between (detection or `overrideRole`) is not re-checked at line 270.
    - Why it matters: in the race window a rename silently detaches every message of the renamed subtree and answers 404 to a successful operation. The window is short, but folder polls are periodic, and the failure is silent data/link degradation rather than an error.
    - Suggested fix: serialize folder-changing operations per account. Options: have `sync` re-read and compare within the lock, or make `rename` hold a per-account in-process mutex (or the pessimistic account lock in a transaction) across the remote `RENAME` and the cache update, which `sync` takes too. Also re-run the INBOX/role check of `delete` inside the locked transaction. Add a regression test if practical, for example a sync triggered between the remote rename and the cache update via a spy.

## P3 Low

- [ ] **CR-2 | `{"name": null}` clears the folder's role**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailAccountController.kt:605` (`changesRole`), used at 298
    - Category: Correctness
    - Problem: `changesRole` is `roleSent || automatic != null || name == null`. An explicit `{"name": null}` is not told apart from an absent name, so it falls into the legacy branch and calls `overrideRole(role = null)`. That clears the role and sets `roleOverridden = true`. Assumption #2 only covers the absent name, and the OpenAPI description says a name body renames "without touching its role".
    - Why it matters: a client that serializes an unset optional name as `null` (common with generated clients and form models) would strip the folder's role by accident.
    - Suggested fix: track `nameSent` the same way as `roleSent`. Then reject an explicit null name with 400, or treat it as "no rename, no role change". Alternatively, document the behaviour in the assumption and the OpenAPI description.

## Review conclusion

Material issues found; fix before completing the task.

CR-1 is a resilience defect that can break the AC2 id-preservation guarantee under a concurrent folder poll. The parent worker decides whether to fix it in this task or record it as an accepted risk or follow-up task. The other acceptance criteria are implemented as specified. Criterion evidence still has to be recorded from actual test runs.
