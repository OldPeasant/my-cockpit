# Code Review

Date: 2026-10-22

Task: mail-search - Mail search

Scope: committed task changes `git diff c672967d65dd52c9c094eb811910ca88ceb7c022..6ad9a27cc3d00e1d011440707d689cd268aed93f` (review round 2: checks that the round 1 findings CR-1..CR-3 are resolved and looks for new issues)

Requirements:
- tasks/mail-search.md acceptance criteria and the five recorded assumptions (`python tasks.py assumptions`, mail-search entries #1-#5)
- SPEC.md §4.4 (global search, `search_document`), §7.3 (mail search: cached headers/bodies, optional IMAP SEARCH), §14 conventions
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- Previous round: reviews/mail-search/review.md (round 1) and reviews/mail-search/fixes.md
- Existing code conventions in server/.../mail, server/.../core/search, api/openapi.yaml

Git range:
- Branch: main
- HEAD: 6ad9a27cc3d00e1d011440707d689cd268aed93f
- Base/start: c672967d65dd52c9c094eb811910ca88ceb7c022
- Commits reviewed: 5 (bdd5a8ce, d10332fe, 567fdaeb, a8044f22, 6ad9a27c)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Commands run (read-only):
- `git log --oneline c672967d..6ad9a27c`, `git diff --stat` -> 31 files, +2011/-10
- `git diff c672967d..6ad9a27c -- server/src/main` -> read in full; changed test files, test config and the `api/openapi.yaml` and generated web client diffs read
- Read nearby code: `MailHeaderStore` (upsert, reconcile, detachFolder, pruneDetached), `MailFolderService.store`, `MailActionService.relocate/remove`, `ImapRemoteMailbox.withFolder`, `SearchIndexer`, the `search_document` migration, `MailBackupContributor`
- `python tasks.py verify mail-search` -> 3 criteria, none recorded yet (the parent worker records evidence after the quality workflow)
- Tests were not executed by this reviewer: running Gradle was denied by the sandbox permissions. The fix report claims `./gradlew test --tests 'ch.sonensei.cockpit.mail.*'`, `integrationTest --tests '*MailSearchGreenMailTests'`, `./gradlew check` and `sh scripts/check.sh` pass; the parent worker has to re-run them for the evidence.

Round 1 findings, verified against the current code:
- CR-1 (deleted-folder messages returned and causing 500): resolved. `MailSearchIndex.LOCATED` (`m.folder_id is not null and m.uid is not null`, same as `MailMessage.isLocated`) is used in `MailSearchService.localIds` and both reconcile queries; `MailFolderService.store` calls `unindexFolder` before deleting a vanished folder (recorded as assumption #5). Regression tests: `MailSearchWebTests` "messages of a folder deleted from the cache are not found" (account and unscoped), "reconcile drops documents of messages whose folder was deleted ...", and `MailFolderSyncTests` asserts the document is removed by the folder sync.
- CR-2 (reconciler miscounting and repeated delete): resolved. `removeStale()` runs once per run, `indexMissing` selects located rows only and returns the indexed count, so the loop cannot spin on unindexable rows; `MailSearchReconcilerTests` covers once-per-run removal, batching, the bound and failure handling.
- CR-3 (UTF-8 IMAP SEARCH path unproven): resolved. `MailSearchGreenMailTests` now asserts `searchedFolders == 1` and empty `failures` for `müsli`, and `zürich` only matches an uncached message older than the sync window, found only via `server=true` with `loaded == 1`.

Checked with no new finding:
- Criterion 1: headers are indexed in `MailHeaderStore.upsert` (new and reattached rows), bodies in `MailContentLoader.load`; documents are removed on vanish/detach (`MailHeaderStore.remove`, `detachFolder`), on action removal (`MailActionService.remove`), on folder deletion, and by the reconciler for FK-cascaded account deletion (assumption #4). `server=true` runs `UID SEARCH [CHARSET UTF-8] TEXT <word>...` per selectable folder of the scope, caches found headers (most recent `limit`), merges them via `or m.id in (:serverIds)` under the same scope predicates, and reports failing accounts in `server.failures`.
- Query safety: words restricted to `[\p{L}\p{N}]+` before quoting into the tsquery (bound parameter) and before `Argument.writeString`; stop-word-only English branch is harmless because the `simple` branch is OR-ed.
- Index-recency change in core `SearchIndexer` is backward compatible (optional parameter, default unchanged).
- Problem Details for blank/too long `q`, `limit` range, missing `q`, folder of another account (400) and unknown account/folder (404); no secrets or mail content logged at INFO (only ids and error codes).
- Concurrency: `searchServer` holds the folder lock like `loadOlder`; no DB transaction spans IMAP I/O; `MailConnectionException`, `RemoteFolderNotFoundException` and `NotFoundException` are handled per folder.
- OpenAPI: `/api/v1/mail/search` and its schemas are present in `api/openapi.yaml` and the generated web client; optional/required marking follows the project convention (only nullable fields are marked required explicitly).
- Backup/restore: mail content is not backed up (`MailBackupContributor`); restored refs are detached and get indexed when the sync reattaches them, so no round-trip change is needed.

Note on the working tree: uncommitted test changes exist (`MailActionServiceTests.kt`, `MailSearchWebTests.kt` modified, `MailSearchWordsTests.kt` new), apparently from a concurrent test-writer run. They are outside the committed scope and were not reviewed; among other things they add verification of `MailActionService.remove` unindexing, which the committed tests only exercise through a relaxed mock. If they are committed, they only touch tests, but the evidence should name the final revision.

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
