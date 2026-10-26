# Code Review

Date: 2026-10-26

Task: perf-tuning - Performance baseline and tuning

Scope: second review on the final code. `git diff d7388b759..HEAD -- server docs`, limited to the perf-tuning commits. The range also has the merged `pdf-fulltext` task of another agent (commits 6dcd5b89b..46b2f4a9c: `files/fulltext/*`, `FilesService.kt`, the files migration, `server/build.gradle.kts`, test `application.properties`, one ARCHITECTURE.md line). That task was not reviewed here. The task's own changes are the same as in `git diff 2baefbb45..HEAD -- server docs`. Committed changes only; the working tree was clean.

Requirements:
- tasks/perf-tuning.md acceptance criterion and its four recorded assumptions
- SPEC.md, docs/ARCHITECTURE.md ("Persistence conventions", commands table), AGENTS.md project notes
- Earlier review of this task (CR-1..CR-3) and reviews/perf-tuning/fixes.md
- Results the parent ran on HEAD (Gradle is blocked for sub-agents and was not re-run by the reviewer): `./gradlew ktlintCheck integrationTest --tests '*PerfBaselineTests' --rerun` -> 2 tests passed, all 24 endpoints p95 below 200 ms (worst 96.5 ms); `./gradlew test --tests '*Search*' --tests '*PerfPercentileTests'` -> 112 tests, 0 failures

Git range:
- Branch: main
- HEAD: acaab5f01c363ff45c08b7b14df342d86f82cf2c
- Base/start: d7388b75900291fb47a101514794598f367571eb
- Commits reviewed: 4 perf-tuning code/doc commits (6c5f6a55c, 961a67561, ca0d11ce2, acaab5f01). The `assume:`/heartbeat commits only touch tasks/, and the pdf-fulltext commits belong to another task.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Tuning section quotes "after" timings that differ from the final results table**
    - File: `docs/perf.md:89` (also `docs/perf.md:91`)
    - Category: Quality
    - Problem: The tuning section gives the effect of the `mail_message_sort` index as "21 → 7 ms p95" for the unified Inbox and "100 → 9 ms p95" for mail search on a common word. The final results table in the same file shows 5.3 ms and 5.5 ms for these endpoints. The "after" figures come from an earlier run on the earlier seed and are not labelled that way. The line above the final table labels its own earlier-run figure ("an earlier run ... measured up to 118 ms").
    - Why it matters: Small. A reader comparing the two sections sees two different "after" values for one change and cannot tell which run is which. The improvement claim itself still holds.
    - Suggested fix: Quote the final-table values (21 → 5.3, 100 → 5.5), or note that the arrows come from the intermediate run.

## Status of the earlier findings

- CR-1 (thread grouping), fixed. `thread_key` is now `'thread-' || g % 2 || '-' || g / 6`. In Postgres, `%` and `/` bind tighter than `||`, so this groups as intended. The account is `a.n = g % 2`, with `n = row_number() - 1` ordered by email, so the parity in the key is the account. In every block of 6 `g` values, the 3 values of each parity share one key within one account. `MailMessageListQuery` partitions by `(account_id, thread_key)`, so account-wide conversations have 3 messages. The edge blocks are as fixes.md describes: g = 1..5 gives 3 + 2 messages, g = 49 998..50 000 gives 2 + 1. The PerfSeed comment and docs/perf.md ("conversations of 3 messages within one account") match. In a single Inbox list, a conversation shows 1-3 messages because the folder is chosen by `g % 10`. That is realistic, and the docs do not claim otherwise.
- CR-2 (reproducibility), fixed. No `gen_random_uuid()` remains in PerfSeed.kt. Every id is `md5(prefix || row)::uuid`, and Postgres accepts the 32-hex-digit md5 output as uuid input. Ids are unique within each table: area, context, project, task, note, account, message and event use distinct per-table prefixes plus a unique `g`. Folders use account id + path, which is unique per account. Calendars use source id + `g`. The two `row_number() over (order by id)` assignments (`task_context`, `note_tag`) are now deterministic. These ids are not UUIDv7, as was the earlier `gen_random_uuid()`. The KDoc says so, and the parent's run shows that every endpoint taking such an id as a query parameter (`context`, `projectId`, `accountId`, `folderId`) answers 200. The KDoc of PerfSeed and PerfBaselineTests and docs/perf.md now say "same rows, ids and context/tag assignments, dates relative to the day of the run", which matches the code.
- CR-3 (migration folder), accepted. The task's `touches` name `db/migration/perf/`, and the folder choice is recorded as an assumption in tasks/perf-tuning.md, which is one of the remedies the finding offered. The file comment says what the index is for. No further action.

## Verified without findings

- Acceptance criterion: the seed creates exactly 10 000 tasks, 2 000 notes, 50 000 mail headers, 5 000 events and 67 000 search documents, and `seeded volumes match the baseline` asserts this. The p95 check covers 24 list and search endpoints and asserts p95 < 200 ms. It is repeatable (fixed seed, row-derived ids, cleaned before and after) and tagged `@ExternalTest`, so it runs only in `integrationTest`, not in `check`/`.taskcheck`. The new index is a Flyway migration with a valid unique version that matches the naming convention. docs/perf.md has the method, the before/after tables, the tuning and the remaining hot spots. The parent's run on HEAD supports the timing part (worst p95 96.5 ms).
- docs/perf.md vs. the final run: the after-tuning table's worst value (unified Inbox threaded, 96.5 ms) matches the parent's reported worst p95. The hot-spot ranges (global search 60-120 ms across runs, threaded unified Inbox ~90-100 ms) are consistent with the table and with the earlier 118 ms run. The before-tuning table is labelled as coming from the earlier random-id seed. The endpoint list, request URLs ({id}/{date} masking via the test's regexes), volumes and distributions match PerfSeed and PerfBaselineTests. 67 000 documents and the ~95 % share for `meeting` (64 000 rows in EXPLAIN) are consistent.
- SearchDocumentRepository.fullText: `as materialized` changes only how the query is evaluated. `date_part('epoch', ...)` keeps the boost formula, and the score is only used for ordering. The test-writer's two new SearchEdgeCaseTests pin the recency order at a fixed `now` and the clamp at 0 for future `updated_at`. The expected orders follow from the formula: without the clamp, a document 60 days in the future would get a boost of 0 and one 29 days in the future a boost of about 31. With the clamp, both get 2.
- PerfPercentileTests: the nearest-rank formula `sorted[ceil(p/100*n) - 1]`, coerced into range, gives the expected values for n = 50 (p95 = 48th, p50 = 25th), for the boundaries p0/p1/p100, for ranks that fall between two samples, and for a single sample.
- Seed SQL vs. schema (re-checked after the fix): enum and check constraints, the unique `(folder, uid)` (uid = g), one default calendar, and the timed/all-day event shapes are unchanged by the fix. Cleanup clears every seeded table directly or by cascade.
- ARCHITECTURE.md: the new commands-table row (command, volumes, ~1.5 min) matches docs/perf.md and the test.
- Security: test-only data is fictitious (example.org/example.com, placeholder names). Nothing is logged or exposed in production code. The production change is a read-only query rewrite and one index.

## Review conclusion

No material issues found. The earlier CR-1 and CR-2 are fixed and CR-3 is accepted by a recorded assumption. The remaining P3 is a documentation accuracy nit and does not block completion.
