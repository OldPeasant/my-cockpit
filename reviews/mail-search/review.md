# Code Review

Date: 2026-10-22

Task: mail-search - Mail search

Scope: task commits 4f55e16c, 3d57eadf, 91de57ca (`git diff ca8c3523..91de57ca`), committed changes only

Requirements:
- tasks/mail-search.md acceptance criteria and recorded assumptions
- SPEC.md §4.2, §4.4, §7.3, §7.4 (as referenced by the parent worker)
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- Existing code conventions in server/.../mail and server/.../core/search

Git range:
- Branch: main
- HEAD: 91de57ca8e3437746478eb3e35cfd0af2bcf5337
- Base/start: ca8c3523db2b215dbf5a7038b65e6c874ddf9377
- Commits reviewed: 3

Summary: 0 P0, 1 P1, 0 P2, 2 P3

Commands run (read-only):
- `git diff --stat ca8c3523..91de57ca` -> 25 files, +1451/-8
- `git diff ca8c3523..91de57ca -- server/src/main` -> read in full
- `git grep` over the mail module for detach/delete paths, the mail migrations for the `folder_id` FK, core web Problem Details handlers, core search queries
- `python tasks.py verify mail-search` -> 3 criteria, none verified yet (expected before review; the parent worker records evidence)
- Tests were not executed by this reviewer (the shell was limited to read-only git/grep inspection).

Checked with no finding: tsquery construction (words are restricted to `[\p{L}\p{N}]+` before being quoted, so no quote, backslash or operator reaches `to_tsquery`; values are bound parameters); IMAP SEARCH arguments (same word filter, sent via `Argument.writeString`, `CHARSET UTF-8` only for non-ASCII, no raw string concatenation); the folder lock is held for the whole `searchServer` like `loadOlder`, and no DB transaction is held across IMAP I/O; Problem Details for blank/too long `q`, `limit` out of range, missing `q`, bad scope (400/404); logging (only ids and error codes at INFO, server causes at DEBUG); index hooks run inside the existing `MailHeaderStore`/`MailContentLoader` transactions.

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Messages whose folder was deleted are returned by search and make the response fail with 500**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/search/MailSearchService.kt:161` (also `server/src/main/kotlin/ch/sonensei/cockpit/mail/search/MailSearchIndex.kt:62` and `:71`)
    - Category: Correctness
    - Problem: `mail_message.folder_id` is `on delete set null` (`V202610201402__mail_model.sql:92`). A folder removed by the folder list sync (`MailFolderService.kt:119`, e.g. renamed or deleted on the server) leaves its rows with `folder_id = null` but `uid` still set. `MailMessage.isLocated` requires both (`MailMessage.kt:125`), but `localIds` only filters `m.uid is not null`, and nothing unindexes these rows (no hook on folder deletion; the reconciler delete at `MailSearchIndex.kt:62` also only checks `m.uid is not null`, so their documents stay). An account-scoped or unscoped search matching such a message returns it, and `MailSearchController` maps it with `toSummary`, which does `folderId = checkNotNull(folderId)` (`MailMessageController.kt:381`): `IllegalStateException`, so the whole search answers 500. The folder scope is unaffected (`m.folder_id = :folderId`). The `MailSearchIndex` class doc states "Only located messages (in a folder) are searchable", so the implementation contradicts its own contract.
    - Why it matters: after a common server-side action (renaming or deleting a folder), every global or account mail search that hits one of the old messages fails permanently, which breaks criterion 1 in normal use. The global search (`/api/v1/search`) also keeps returning these EMAIL documents, which the user cannot open.
    - Suggested fix: use the located predicate `m.folder_id is not null and m.uid is not null` in `localIds` and in both reconcile queries, so the reconciler removes the stale documents. Also consider unindexing a folder's messages when the folder is deleted. Add a test that deletes a folder with cached, indexed messages and then searches with the account scope and with no scope.

## P2 Medium

None.

## P3 Low

- [ ] **CR-2 | Reconciler counts unindexable rows as added and selects them again in every batch**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/search/MailSearchIndex.kt:66-80`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/search/MailSearchReconciler.kt:39-45`
    - Category: Quality
    - Problem: the "missing" query selects `m.uid is not null` rows that have no document. `index(it)` then unindexes the rows that are not located (folder deleted, see CR-1), but `missing.size` is still returned as "added". Such rows never get a document, so with 500 or more of them (e.g. mail cached before this feature in folders deleted since) every run loops through all 200 batches over the same rows and logs a false "N added" at INFO every hour. The unbounded `delete ... not exists` also runs again on every batch, although only the first one can remove anything.
    - Why it matters: wasted DB work every hour and misleading operational logs.
    - Suggested fix: the CR-1 predicate removes the cause. Also return the number of documents actually indexed and run the delete once per run.

- [ ] **CR-3 | GreenMail test does not prove the UTF-8 IMAP SEARCH path**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/mail/search/MailSearchGreenMailTests.kt:155-157`
    - Category: Tests
    - Problem: the `müsli` server search runs after the message is already cached (its snippet contains the word), and the test only asserts `items` and `loaded = 0`. If the server rejected `CHARSET UTF-8` or the literal, the account would be listed in `server.failures`, and the local hit would still satisfy the assertions.
    - Why it matters: the only non-ASCII IMAP path (`ImapRemoteMailbox.search` with `CHARSET UTF-8` and literals) is effectively untested.
    - Suggested fix: assert that `server.failures` is empty and `searchedFolders == 1` for that call, or search for a non-ASCII word that only appears in an uncached message, so the result depends on the server match.

## Review conclusion

Material issues found; fix before completing the task.
