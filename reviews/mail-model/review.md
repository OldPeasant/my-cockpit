# Code Review

Date: 2026-10-20

Task: mail-model — Mail data model

Scope: committed changes of task `mail-model`, `git diff 0d8a31f..dc391ff` (production code at `dc391ff`). Uncommitted test-file changes from the parallel test-writer were not reviewed as production code.

Requirements:
- `tasks/mail-model.md` acceptance criteria and the five assumptions in its Notes
- SPEC.md §4.2, §4.3, §7.1, §7.2 (plus §4.4/§10 to check scope boundaries against `mail-search` and `backup-contrib-mail`)
- docs/ARCHITECTURE.md (code and persistence conventions), docs/QUALITY.md, AGENTS.md

Git range:
- Branch: main
- HEAD: dc391ff64a686a636bbf71743cca157162f86150
- Base/start: 0d8a31f3ded6b972b403bf664177f5c7e7f31266
- Commits reviewed: 4 (bbcfc97, 4445a4b, 7621d6e, dc391ff)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Body cache eviction sorts the whole table on every put; the LRU index is unused**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailRepositories.kt:96` (called from `MailBodyCache.kt:41`); index in `server/src/main/resources/db/migration/mail/V202610201402__mail_model.sql:142`
    - Category: Quality
    - Problem: `evictBeyond` computes `sum(size_bytes) over (order by (message_id = :keep) desc, last_accessed_at desc, id desc)` over every `mail_body` row on every `put`, also when the total is well below the budget. The leading boolean expression means `mail_body_lru (last_accessed_at, id)` cannot provide that order, so each put does a full scan and sort. No other query orders by `last_accessed_at`, so the index currently only adds write cost (every `get` updates `last_accessed_at`).
    - Why it matters: with the default 2 GiB budget the table can hold tens of thousands of rows. Every body fetch then pays an O(n log n) sort. This does not affect correctness.
    - Suggested fix: skip eviction when `totalSizeBytes() + new size <= maxBytes` (cheap aggregate), or order the window by `last_accessed_at desc, id desc` and handle `keep` separately (e.g. subtract its size from the budget), so the index can serve the scan. Alternatively, drop the unused index.

## Notes (not findings)

- AC1: all required columns exist (account type/hosts/ports/TLS, encrypted `password` via the auto-applied `EncryptedSecretConverter`, `oauth_connection_id`, signature, notify flag; identity; folder path/role/uidvalidity/uidnext/highestmodseq; message account/folder/uid/Message-ID/headers/flags/size/has_attachments/snippet/thread_key; `mail_body` with UTF-8 size accounting). Table layout follows the persistence conventions (UUID id, timestamps, version), and the migration name follows `V<ts>__<module>_<desc>`. The migration was edited by fix commit 7621d6e, but it has never been on main, so this is allowed.
- AC2: `MailItemResolver` and `MailMessageLocator` re-resolve a non-located row by account and Message-ID. They prefer the same folder and never use another account. Stale UIDs left behind by a deleted folder are excluded (`isLocated` requires both folder and UID, and the copy query filters on both). The resolver data matches `ResolvedItem`, and only short plain text is exposed (subject and sender).
- Package `mail.model` instead of `mail.{api,domain,infra}` is a recorded assumption that matches the task's `touches` and the board's per-feature mail packages. Search indexing (`mail-search`) and backup (`backup-contrib-mail`) are owned by other tasks and are correctly out of scope.
- Evidence: `python tasks.py verify mail-model` shows all three criteria still unverified at review time. The parent must record the evidence before `done`. `reviews/mail-model/tests.md` at `dc391ff` describes an earlier revision (base 8507047, HEAD 927e3c6, one failing test). It must be refreshed for the final revision, which the running test-writer is expected to do.

## Review conclusion

No material issues found.
