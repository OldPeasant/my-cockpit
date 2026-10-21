# Code Review

Date: 2026-10-22

Task: mail-search - Mail search

Scope: committed task changes `git diff 61e58a59fc78a624024950883a5af5c1cc09c69c..fc43c419647f6df418ba0c972eb28982eba36d59` (review round 3, on the final code). Production code is unchanged since the fix commit a0301d6ae (`git diff --stat a0301d6ae HEAD` touches only test files and `reviews/`). This round re-reads the whole task and pays particular attention to the test-only commits after round 2 (5317111ab, fc43c4196).

Requirements:
- tasks/mail-search.md acceptance criteria 1-3 and the five assumptions in its Notes (hook placement outside `touches`, document content and recency, endpoint scope/limit/hasMore/server failures, reconciler, folder-deletion unindex)
- SPEC.md §7.3 (mail search over cached headers and bodies, optional IMAP SEARCH) and §4.4 (`search_document`)
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions, docs/QUALITY.md review artifact contract
- Previous rounds: reviews/mail-search/review.md (rounds 1 and 2), reviews/mail-search/fixes.md, reviews/mail-search/tests.md

Git range:
- Branch: main
- HEAD: fc43c419647f6df418ba0c972eb28982eba36d59
- Base/start: 61e58a59fc78a624024950883a5af5c1cc09c69c (`git merge-base HEAD origin/main`)
- Commits reviewed: 7 (41d157832, 461be158f, 0350729f9, d85506cb5, a0301d6ae, 5317111ab, fc43c4196)
- Uncommitted changes: none at review start

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Commands run (read-only):
- `git log --oneline 61e58a59..HEAD`, `git diff --stat 61e58a59 HEAD` -> 26 files outside the generated web client, +2171/-10
- `git diff --stat a0301d6ae HEAD` -> only `MailActionServiceTests.kt`, `MailSearchWebTests.kt`, `MailSearchWordsTests.kt` and `reviews/` changed after the fix commit
- `git diff --stat 2dd9badb7 HEAD -- server api web` -> only `MailSearchWebTests.kt` (+27): the revision tests.md measured plus the then-uncommitted test that fc43c4196 committed, so tests.md describes the final test code
- Read in full: `MailSearchService.kt`, `MailSearchIndex.kt`, `MailSearchController.kt`, `MailSearchReconciler.kt`; the hook diffs in `MailHeaderStore`, `MailHeaderSyncService.searchServer`, `RemoteFolder.search`, `ImapRemoteMailbox` (`UID SEARCH [CHARSET UTF-8] TEXT ...` via `Argument`), `MailContentLoader`, `MailActionService.remove`, `MailFolderService.store`, core `SearchIndexer` (optional recency); test `application.properties`
- Read the new and changed tests: `MailSearchWebTests` (fake mailbox incl. `attempts` and `onOpen`, setup, and every test added after round 2), `MailSearchWordsTests`, the new `MailActionServiceTests` case
- `python tasks.py verify mail-search` -> criterion 3 verified (OpenApiContractTest without the flag), criteria 1 and 2 not yet recorded (the parent records them)
- Gradle was not run by this reviewer; the parent reports `test --tests 'ch.sonensei.cockpit.mail.search.*'` on HEAD -> 36 tests, 0 failures, the mail suite + OpenApiContractTest -> 1145 tests, 0 failures (before the last test commit), `integrationTest --tests '*MailSearchGreenMailTests'` -> 1 test, 0 failures, ktlintCheck passes

Checked with no finding:
- Criterion 1: headers are indexed on cache (`MailHeaderStore.upsert`, new rows without body, reattached rows with their cached body), bodies when loaded (`MailContentLoader.load`, in the same transaction as the cache put); documents leave the index on vanish/detach, action removal, UIDVALIDITY detach, folder deletion (`unindexFolder` before `folders.delete`) and, for FK cascades, via the reconciler. `server=true` runs IMAP SEARCH per selectable folder of the scope under the folder lock, caches the newest `limit` uncached hits, and merges them under the same scope and `LOCATED` predicates; unreachable accounts are reported once in `server.failures` with the fixed error message.
- Query safety: words are restricted to `[\p{L}\p{N}]+`, lowercased, deduplicated and capped at 20 before they are quoted into the bound tsquery parameter or written as IMAP strings; `MailSearchWordsTests` and the new web test with `'`, `&`, `!`, `:*`, `|`, `\` confirm operators never reach the query.
- Validation and errors: blank / over-200-character `q` (after trimming), `limit` outside 1..200 -> 400 Problem Details; unknown account/folder -> 404; folder of another account -> 400. The new bounds test checks that 200 characters (also with surrounding spaces) and limits 1 and 200 are accepted.
- Logging: only ids and error codes at INFO; exception causes (possible server responses) at DEBUG; no mail content logged.
- New tests (round 3 focus):
  - `server search skips a folder removed from the cache while the search runs`: relies on folders being searched in path order (`findByAccountIdOrderByPathAsc`, `Archive` before `INBOX`); the `onOpen` hook deletes INBOX while Archive is open, so the following `sync.searchServer(INBOX)` hits `NotFoundException`, which `MailSearchService.searchServer` catches. The assertions (only Archive searched, `searchedFolders == 1`, `loaded == 1`, no failures, the unlocated INBOX row not returned) prove the race path and the `LOCATED` filter. `onOpen` is reset in `setUp`, so it cannot leak into other tests.
  - `an unreachable account is tried once, not once per folder`: `attempts` is recorded before the failure is thrown, so the count of 1 for an account with two folders really checks the `folder.accountId in failures` skip.
  - `a message found again after a UIDVALIDITY change ...`: checks the reattached row keeps its id and is re-indexed with its cached body (the `searchIndex.index(message)` branch of `upsert`).
  - `MailActionServiceTests` case: verifies `unindex` for a dropped and a detached row and no `unindex` for a row relocated via COPYUID, replacing the earlier relaxed-mock-only coverage.
  - The strengthened failure test asserts `message == MailConnectionError.CONNECTION_FAILED.message`, so the exception cause cannot leak into the API.
- Tests assert behavior through the HTTP API and the database (Testcontainers Postgres via `PostgresTestBase`), not implementation details; no existing test was weakened or skipped.
- OpenAPI and generated web client match the controller (criterion 3 evidence recorded); `SearchIndexer` change is backward compatible.

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
